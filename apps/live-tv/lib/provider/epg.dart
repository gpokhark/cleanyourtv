import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:isolate';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';

import '../model/channel.dart';

/// One programme: start and end in milliseconds since the epoch.
class Programme {
  final int start;
  final int end;
  final String title;
  const Programme(this.start, this.end, this.title);

  List<Object> toJson() => [start, end, title];
  factory Programme.fromJson(List j) => Programme(j[0] as int, j[1] as int, j[2] as String);
}

/// Programme guide: "Now" and "Next" for the channels that an XMLTV guide covers. Off unless guides are named at build
/// time with --dart-define=EPG_URLS=https://host/guide.xml.gz,https://other/guide.xml (comma separated; .gz is unpacked).
///
/// A guide can be 30 MB of text, so it is never held in memory: it is downloaded, unpacked and scanned as a stream in a
/// background isolate, and only the programmes of the channels this app lists, from half an hour ago to twelve hours
/// ahead, are kept. A channel is matched to a guide channel by its id and then by its name, the first guide that has it
/// winning. The result is cached on disk and refreshed when it is six hours old.
class Epg {
  static const urlsSetting = String.fromEnvironment('EPG_URLS');
  static final urls = urlsSetting.split(',').map((u) => u.trim()).where((u) => u.startsWith('http')).toList();
  static bool get enabled => urls.isNotEmpty;

  static final instance = Epg._();
  Epg._();

  static const _cacheFile = 'epg_v1.json';
  static const _maxAge = Duration(hours: 6);

  final Map<String, List<Programme>> _byKey = {};
  bool _busy = false;
  List<Channel>? _again;

  /// What is on [channel] at [now] and what follows, or nulls when the guide has nothing for it.
  (Programme?, Programme?) nowNext(Channel channel, DateTime now) {
    final list = _byKey[epgKey(channel)];
    return list == null ? (null, null) : pickNowNext(list, now.millisecondsSinceEpoch);
  }

  /// Loads the cache, then refreshes from the guides when it is stale or was made for other channels. Never throws.
  Future<void> load(List<Channel> channels) async {
    if (!enabled) return;
    if (_busy) {
      _again = channels;
      return;
    }
    _busy = true;
    try {
      final wanted = wantedKeys(channels);
      // Which channels the guide was matched for: their count and a checksum of their keys, the same on every run.
      var sum = 0;
      for (final k in wanted.keys.toList()..sort()) {
        for (final u in k.codeUnits) {
          sum = (sum * 31 + u) & 0x7fffffff;
        }
      }
      final signature = '${wanted.length}:$sum';
      final file = File('${(await getApplicationSupportDirectory()).path}/$_cacheFile');
      var fresh = false;
      try {
        if (await file.exists()) {
          final j = jsonDecode(await file.readAsString()) as Map<String, dynamic>;
          final at = DateTime.fromMillisecondsSinceEpoch(j['at'] as int);
          _byKey
            ..clear()
            ..addAll((j['p'] as Map<String, dynamic>)
                .map((k, v) => MapEntry(k, [for (final p in v as List) Programme.fromJson(p as List)])));
          fresh = j['sig'] == signature && DateTime.now().difference(at) < _maxAge;
        }
      } catch (_) {
        _byKey.clear();
      }
      if (fresh) return;
      final nowMs = DateTime.now().millisecondsSinceEpoch;
      final raw = await Isolate.run(() => refreshFromUrls(urls, wanted, nowMs));
      if (raw.isEmpty) return;   // every guide failed: keep what the cache gave
      _byKey
        ..clear()
        ..addAll({for (final e in raw.entries) e.key: [for (final p in e.value) Programme.fromJson(p)]});
      await file.writeAsString(jsonEncode({'at': nowMs, 'sig': signature, 'p': raw}));
    } catch (e) {
      debugPrint('EPG load failed: $e');
    } finally {
      _busy = false;
      final again = _again;
      _again = null;
      if (again != null) unawaited(load(again));
    }
  }
}

/// The programme running at [ms] and the one after it, from [list] sorted by start.
(Programme?, Programme?) pickNowNext(List<Programme> list, int ms) {
  Programme? current, next;
  for (final p in list) {
    if (p.start <= ms && ms < p.end) {
      current = p;
    } else if (p.start >= ms && (current == null || p.start >= current.end)) {
      next = p;
      break;
    }
  }
  return (current, next);
}

/// The key a channel's programmes are stored under: its id when it has one, else its name.
String epgKey(Channel c) => c.tvgId.isNotEmpty ? epgIdKey(c.tvgId) : 'n${epgNameKey(c.name)}';

/// "AajTak.in@HD", "Aaj.Tak.in", "&TV.HD.in" -> letters and digits only, without the feed and an HD part.
String epgIdKey(String id) =>
    'i${id.toLowerCase().split('@').first.replaceAll('.hd.', '.').replaceAll(RegExp(r'[^a-z0-9]'), '')}';

/// "Aaj Tak HD (1080p) [Geo-blocked]" -> "aajtak"
String epgNameKey(String name) => name
    .toLowerCase()
    .replaceAll(RegExp(r'\(.*?\)|\[.*?\]'), '')
    .replaceAll(RegExp(r'\bhd\b'), '')
    .replaceAll(RegExp(r'[^a-z0-9]'), '');

/// Lookup key -> epgKey for every channel: by id and by name.
Map<String, String> wantedKeys(List<Channel> channels) {
  final out = <String, String>{};
  for (final c in channels) {
    final key = epgKey(c);
    if (c.tvgId.isNotEmpty) out.putIfAbsent(epgIdKey(c.tvgId), () => key);
    final name = epgNameKey(c.name);
    if (name.isNotEmpty) out.putIfAbsent('n$name', () => key);
  }
  return out;
}

/// Downloads each guide in turn and returns epgKey -> [[start, end, title], ...] for the wanted channels. A guide that
/// cannot be fetched is skipped.
Future<Map<String, List<List<Object>>>> refreshFromUrls(List<String> urls, Map<String, String> wanted, int nowMs) async {
  final result = <String, List<List<Object>>>{};
  for (final url in urls) {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 15);
    try {
      final req = await client.getUrl(Uri.parse(url));
      req.headers.set(HttpHeaders.userAgentHeader, 'Mozilla/5.0');
      final resp = await req.close().timeout(const Duration(seconds: 30));
      if (resp.statusCode != 200) {
        await resp.drain<void>();
        continue;
      }
      Stream<List<int>> bytes = resp;
      if (url.toLowerCase().contains('.gz')) bytes = bytes.transform(gzip.decoder);
      final text = bytes.transform(const Utf8Decoder(allowMalformed: true));
      final got = await parseGuide(text, wanted, result.keys.toSet(), nowMs).timeout(const Duration(seconds: 300));
      result.addAll(got);
    } catch (e) {
      debugPrint('EPG guide $url failed: $e');
    } finally {
      client.close(force: true);
    }
  }
  return result;
}

const _before = 30 * 60 * 1000;
const _after = 12 * 60 * 60 * 1000;
const _perChannel = 14;

final _attrId = RegExp(r'\bid="([^"]*)"');
final _attrChannel = RegExp(r'\bchannel="([^"]*)"');
final _attrStart = RegExp(r'\bstart="([^"]*)"');
final _attrStop = RegExp(r'\bstop="([^"]*)"');
final _displayName = RegExp(r'<display-name[^>]*>(.*?)</display-name>', dotAll: true);
final _title = RegExp(r'<title[^>]*>(.*?)</title>', dotAll: true);

/// Scans XMLTV text as it arrives, so a large guide never sits in memory. [wanted] maps a lookup key (see [wantedKeys])
/// to the epgKey to store under; [skip] holds epgKeys an earlier guide already supplied. Returns epgKey -> programmes
/// from half an hour ago to twelve hours ahead of [nowMs], oldest first, as [start, end, title].
Future<Map<String, List<List<Object>>>> parseGuide(
    Stream<String> chunks, Map<String, String> wanted, Set<String> skip, int nowMs) async {
  final guideToKey = <String, String>{};
  final out = <String, List<List<Object>>>{};
  var buf = '';
  await for (final chunk in chunks) {
    buf += chunk;
    while (true) {
      final c = buf.indexOf('<channel ');
      final p = buf.indexOf('<programme ');
      if (c < 0 && p < 0) {
        buf = buf.length > 12 ? buf.substring(buf.length - 12) : buf;   // a tag may be cut in two
        break;
      }
      final isChannel = p < 0 || (c >= 0 && c < p);
      final from = isChannel ? c : p;
      final close = isChannel ? '</channel>' : '</programme>';
      final to = buf.indexOf(close, from);
      if (to < 0) {
        buf = buf.substring(from);
        break;
      }
      final block = buf.substring(from, to + close.length);
      buf = buf.substring(to + close.length);
      if (isChannel) {
        _readChannel(block, wanted, skip, guideToKey);
      } else {
        _readProgramme(block, guideToKey, nowMs, out);
      }
    }
  }
  for (final list in out.values) {
    list.sort((a, b) => (a[0] as int).compareTo(b[0] as int));
    if (list.length > _perChannel) list.removeRange(_perChannel, list.length);
  }
  return out;
}

void _readChannel(String block, Map<String, String> wanted, Set<String> skip, Map<String, String> guideToKey) {
  final id = _attrId.firstMatch(block)?.group(1);
  if (id == null) return;
  var key = wanted[epgIdKey(_unescape(id))];
  if (key == null) {
    for (final m in _displayName.allMatches(block)) {
      key = wanted['n${epgNameKey(_unescape(m.group(1) ?? ''))}'];
      if (key != null) break;
    }
  }
  if (key != null && !skip.contains(key)) guideToKey[id] = key;
}

void _readProgramme(String block, Map<String, String> guideToKey, int nowMs, Map<String, List<List<Object>>> out) {
  final key = guideToKey[_attrChannel.firstMatch(block)?.group(1)];
  if (key == null) return;
  final start = parseXmltvTime(_attrStart.firstMatch(block)?.group(1));
  final end = parseXmltvTime(_attrStop.firstMatch(block)?.group(1));
  if (start == null || end == null || end < nowMs - _before || start > nowMs + _after) return;
  final title = _unescape((_title.firstMatch(block)?.group(1) ?? '').trim());
  if (title.isEmpty) return;
  (out[key] ??= []).add([start, end, title]);
}

/// "20261021161000 +0530" -> milliseconds since the epoch (UTC); a missing zone means UTC. Null when it does not parse.
int? parseXmltvTime(String? s) {
  if (s == null) return null;
  final m = RegExp(r'^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4})?').firstMatch(s.trim());
  if (m == null) return null;
  final utc = DateTime.utc(int.parse(m.group(1)!), int.parse(m.group(2)!), int.parse(m.group(3)!),
      int.parse(m.group(4)!), int.parse(m.group(5)!), int.parse(m.group(6) ?? '0'));
  var offset = 0;
  final z = m.group(7);
  if (z != null) {
    offset = (int.parse(z.substring(1, 3)) * 60 + int.parse(z.substring(3, 5))) * 60 * 1000 * (z.startsWith('-') ? -1 : 1);
  }
  return utc.millisecondsSinceEpoch - offset;
}

String _unescape(String s) => s
    .replaceAll('<![CDATA[', '')
    .replaceAll(']]>', '')
    .replaceAllMapped(RegExp(r'&#(x?)([0-9a-fA-F]+);'), (m) {
      final code = int.tryParse(m.group(2)!, radix: m.group(1) == 'x' ? 16 : 10);
      return code == null ? m.group(0)! : String.fromCharCode(code);
    })
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'")
    .replaceAll('&amp;', '&');
