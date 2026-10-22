import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';

import '../model/channel.dart';

/// Sidebar order. Categories with no channels are hidden.
const categoryOrder = [
  'News',
  'Entertainment',
  'Movies',
  'Music',
  'Sports',
  'Kids',
  'Infotainment',
  'Business',
  'Devotional',
  'Other',
];

/// Loads the iptv-org playlists named by [ChannelsProvider.playlists], keeps streams of at least [minHeight],
/// adds logos and categories from the iptv-org India index, and caches the result on disk.
class ChannelsProvider {
  /// Playlists to load, comma separated. An entry is either a path under https://iptv-org.github.io/iptv/ without
  /// ".m3u" ("countries/in", "languages/mar", "categories/news") or a full http(s) URL of any M3U playlist, such as
  /// Free-TV's https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8. Set at build time:
  /// --dart-define=IPTV_PLAYLISTS=countries/in,languages/mar. Avoid "index": it holds every channel. An iptv-org path
  /// can carry filters after a "?": country=in keeps only channels of that country (by the tvg-id, "NDTV24x7.in@SD" is
  /// Indian), not=us drops the channels of that country, cat=Movies|Sports keeps only those sidebar categories,
  /// min=720 sets this list's lowest height. So
  /// "languages/eng?country=in" is Indian English, and "languages/eng?cat=Movies&min=720" is English movie channels
  /// from anywhere.
  static const playlists = String.fromEnvironment('IPTV_PLAYLISTS', defaultValue: 'countries/in');

  /// For playlists given as a full URL, which group-title values to keep, comma separated ("India"); such lists
  /// are grouped by country and cover the whole world. Empty keeps everything. Build setting --dart-define=IPTV_GROUPS=India.
  static const groups = String.fromEnvironment('IPTV_GROUPS');

  /// Channels to leave out, comma separated, each a word matched (ignoring case) against the channel's name and link:
  /// IPTV_SKIP=pluto drops every Pluto TV channel, for someone who has the Pluto TV app. Build setting.
  static const skip = String.fromEnvironment('IPTV_SKIP');

  /// Lowest stream height kept: 1080 keeps only (1080p) streams, 720 also keeps (720p) ones. Build setting
  /// --dart-define=IPTV_MIN_HEIGHT=720.
  static const minHeight = int.fromEnvironment('IPTV_MIN_HEIGHT', defaultValue: 1080);

  static const _iptvBase = 'https://iptv-org.github.io/iptv/';

  /// Carries logos and group-titles (categories) for Indian channels.
  static const logoIndexUrl = 'https://iptv-org.github.io/iptv/countries/in.m3u';

  static const _cacheFile = 'channels_v2.json';
  static const _timeout = Duration(seconds: 20);

  /// Last result saved on disk, so the app can show channels instantly on start.
  Future<List<Channel>?> loadCached() async {
    try {
      final f = File('${(await getApplicationSupportDirectory()).path}/$_cacheFile');
      if (!await f.exists()) return null;
      final list = await compute(_decodeChannels, await f.readAsString());
      return list.isEmpty ? null : list;
    } catch (_) {
      return null;
    }
  }

  /// Links that tools/check-live-channels.py found dead, from assets/blocked_streams.txt ("URL  # reason" per line).
  static Future<List<String>> _blockedStreams() async {
    try {
      final text = await rootBundle.loadString('assets/blocked_streams.txt');
      return [
        for (final line in text.split('\n'))
          if (line.trim().isNotEmpty && !line.trim().startsWith('#')) line.trim().split(RegExp(r'\s+')).first,
      ];
    } catch (_) {
      return const [];
    }
  }

  /// Speed of each link, from assets/stream_notes.txt ("URL  # name: slow 0.9x"): link -> [label, ratio].
  static Future<Map<String, String>> _streamNotes() async {
    try {
      final text = await rootBundle.loadString('assets/stream_notes.txt');
      final out = <String, String>{};
      for (final line in text.split('\n')) {
        final t = line.trim();
        if (t.isEmpty || t.startsWith('#')) continue;
        final url = t.split(RegExp(r'\s+')).first;
        final m = RegExp(r':\s*(fast|marginal|slow)\s*([\d.]+)?x?\s*$').firstMatch(t);
        if (m != null) out[url] = '${m.group(1)} ${m.group(2) ?? ''}'.trim();
      }
      return out;
    } catch (_) {
      return const {};
    }
  }

  /// Downloads everything in parallel, parses off the UI thread, saves the cache.
  Future<List<Channel>> refresh() async {
    final sources = [
      for (final entry in playlists.split(',').map((n) => n.trim()).where((n) => n.isNotEmpty)) _Source.parse(entry),
    ];

    final responses = await Future.wait([
      http.get(Uri.parse(logoIndexUrl)).timeout(_timeout).then((r) => r.statusCode == 200 ? r.body : '',
          onError: (_) => ''),
      for (final s in sources)
        http.get(Uri.parse(s.url)).timeout(_timeout).then((r) => r.statusCode == 200 ? r.body : '',
            onError: (_) => ''),
    ]);

    final args = <String, dynamic>{
      'index': responses.first,
      'playlists': [
        for (var i = 0; i < sources.length; i++)
          {
            'name': sources[i].name,
            'full': sources[i].full,
            'country': sources[i].country,
            'not': sources[i].notCountry,
            'cats': sources[i].cats,
            'min': sources[i].minHeight ?? minHeight,
            'body': responses[i + 1],
          },
      ],
      'groups': groups.split(',').map((g) => g.trim().toLowerCase()).where((g) => g.isNotEmpty).toList(),
    };
    args['skip'] = skip.split(',').map((t) => t.trim().toLowerCase()).where((t) => t.isNotEmpty).toList();
    args['blocked'] = await _blockedStreams();
    args['notes'] = await _streamNotes();
    final json = await compute(_buildChannels, args);
    if (json.isEmpty) throw Exception('No channels found');

    try {
      final f = File('${(await getApplicationSupportDirectory()).path}/$_cacheFile');
      await f.writeAsString(jsonEncode(json));
    } catch (_) {}
    return json.map(Channel.fromJson).toList();
  }
}

/// One entry of [ChannelsProvider.playlists]: where to fetch it and which of its channels to keep.
class _Source {
  final String name;
  final String url;
  final bool full;
  final String country;
  final String notCountry;
  final List<String> cats;
  final int? minHeight;

  const _Source(this.name, this.url, this.full, this.country, this.notCountry, this.cats, this.minHeight);

  factory _Source.parse(String entry) {
    if (entry.startsWith('http')) return _Source(entry, entry, true, '', '', const [], null);
    final q = entry.indexOf('?');
    final path = q < 0 ? entry : entry.substring(0, q);
    final params = q < 0 ? <String, String>{} : Uri.splitQueryString(entry.substring(q + 1));
    return _Source(
      path,
      '${ChannelsProvider._iptvBase}$path.m3u',
      false,
      (params['country'] ?? '').toLowerCase(),
      (params['not'] ?? '').toLowerCase(),
      (params['cat'] ?? '').split('|').map((c) => c.trim().toLowerCase()).where((c) => c.isNotEmpty).toList(),
      int.tryParse(params['min'] ?? ''),
    );
  }
}

List<Channel> _decodeChannels(String text) {
  final list = jsonDecode(text) as List;
  return list.map((e) => Channel.fromJson(e as Map<String, dynamic>)).toList();
}

// ---------------------------------------------------------------- parsing (runs in an isolate)

final _tvgId = RegExp(r'tvg-id="([^"]*)"');
final _tvgLogo = RegExp(r'tvg-logo="([^"]*)"');
final _group = RegExp(r'group-title="([^"]*)"');

class _Entry {
  final String info;
  final String url;
  _Entry(this.info, this.url);

  String attr(RegExp r) => r.firstMatch(info)?.group(1) ?? '';
  String get name => info.substring(info.lastIndexOf(',') + 1).trim();
}

List<_Entry> _entries(String body) {
  final out = <_Entry>[];
  String? info;
  for (final raw in body.split('\n')) {
    final line = raw.trim();
    if (line.startsWith('#EXTINF:')) {
      info = line;
    } else if (line.isNotEmpty && !line.startsWith('#') && info != null) {
      out.add(_Entry(info, line));
      info = null;
    }
  }
  return out;
}

/// "AajTak.in@HD" -> "in", "" when the id has no country.
String _idCountry(String id) {
  final base = id.split('@').first;
  final dot = base.lastIndexOf('.');
  return dot < 0 ? '' : base.substring(dot + 1).toLowerCase();
}

/// "AajTak.in@HD" -> "aajtak.in": logos and categories are shared by every feed.
String _baseId(String id) => id.split('@').first.toLowerCase();

/// "Aaj Tak HD (1080p) [Geo-blocked]" -> "aajtak"
String _nameKey(String name) => name
    .toLowerCase()
    .replaceAll(RegExp(r'\(.*?\)|\[.*?\]'), '')
    .replaceAll(RegExp(r'\bhd\b'), '')
    .replaceAll(RegExp(r'[^a-z0-9]'), '');

/// "Aaj Tak HD (1080p) [Geo-blocked]" -> "Aaj Tak HD"
String _cleanName(String name) => name
    .replaceAll(RegExp(r'\s*\(\d+[pi]\)'), '')
    .replaceAll(RegExp(r'\s*\[.*?\]'), '')
    .replaceAll(RegExp('[\u24C8\u24BC\u24CE]'), '')   // circled S, G, Y: not HD, geo-blocked, YouTube
    .trim();

String _category(String group) {
  final first = group.split(';').first.trim().toLowerCase();
  switch (first) {
    case 'news':
    case 'legislative':
      return 'News';
    case 'entertainment':
    case 'general':
    case 'comedy':
    case 'series':
      return 'Entertainment';
    case 'movies':
    case 'classic':
      return 'Movies';
    case 'music':
      return 'Music';
    case 'sports':
      return 'Sports';
    case 'kids':
    case 'animation':
    case 'family':
      return 'Kids';
    case 'documentary':
    case 'science':
    case 'travel':
    case 'culture':
    case 'education':
    case 'lifestyle':
    case 'cooking':
    case 'outdoor':
      return 'Infotainment';
    case 'business':
      return 'Business';
    case 'religious':
      return 'Devotional';
    default:
      // A full-URL list names its own groups ("VOD Movies (EN)").
      return RegExp('movie|film|cinema').hasMatch(first) ? 'Movies' : 'Other';
  }
}

List<Map<String, dynamic>> _buildChannels(Map<String, dynamic> args) {
  // index: channel id / name -> logo + group
  final logoById = <String, String>{}, logoByName = <String, String>{};
  final groupById = <String, String>{}, groupByName = <String, String>{};
  for (final e in _entries(args['index'] as String)) {
    final id = _baseId(e.attr(_tvgId));
    final key = _nameKey(e.name);
    final logo = e.attr(_tvgLogo);
    final group = e.attr(_group);
    if (logo.startsWith('http')) {
      if (id.isNotEmpty) logoById.putIfAbsent(id, () => logo);
      logoByName.putIfAbsent(key, () => logo);
    }
    if (group.isNotEmpty) {
      if (id.isNotEmpty) groupById.putIfAbsent(id, () => group);
      groupByName.putIfAbsent(key, () => group);
    }
  }

  // merge all playlists; same channel in the same category -> one tile with backup streams
  final byKey = <String, Map<String, dynamic>>{};
  final notes = (args['notes'] as Map).cast<String, String>();
  for (final p in (args['playlists'] as List).cast<Map>()) {
    final full = p['full'] as bool;
    final sportsList = !full && (p['name'] as String).toLowerCase().contains('sport');
    final keepGroups = (args['groups'] as List).cast<String>();
    final skipTerms = (args['skip'] as List).cast<String>();
    final blocked = (args['blocked'] as List).cast<String>().toSet();
    final country = p['country'] as String;
    final notCountry = p['not'] as String;
    final cats = (p['cats'] as List).cast<String>();
    for (final e in _entries(p['body'] as String)) {
      if (country.isNotEmpty && _idCountry(e.attr(_tvgId)) != country) continue;
      if (notCountry.isNotEmpty && _idCountry(e.attr(_tvgId)) == notCountry) continue;
      // The player cannot open a YouTube page; those entries (marked with a circled Y in some lists) are skipped.
      if (e.url.contains('youtube.com') || e.url.contains('youtu.be')) continue;
      if (blocked.contains(e.url)) continue;
      final lowerUrl = e.url.toLowerCase(), lowerName = e.name.toLowerCase();
      if (skipTerms.any((t) => lowerUrl.contains(t) || lowerName.contains(t))) continue;
      if (full && keepGroups.isNotEmpty && !keepGroups.contains(e.attr(_group).toLowerCase())) continue;
      // 1080p only unless IPTV_MIN_HEIGHT says otherwise. iptv-org names carry the height; a list that does not
      // tag it (a full-URL playlist) is kept, since its quality is unknown, not low.
      final tag = RegExp(r'\((\d+)[pi]\)').firstMatch(e.name)?.group(1);
      if (tag != null ? int.parse(tag) < (p['min'] as int) : !full) continue;
      final id = e.attr(_tvgId);
      final base = _baseId(id);
      final key = _nameKey(e.name);
      var category = sportsList ? 'Sports' : _category(e.attr(_group));
      // A group that is not a category (a country name in a full-URL list) is looked up in the iptv-org index.
      if (category == 'Other') category = _category(groupById[base] ?? groupByName[key] ?? '');
      if (cats.isNotEmpty && !cats.contains(category.toLowerCase())) continue;
      var logo = e.attr(_tvgLogo);
      if (!logo.startsWith('http')) logo = logoById[base] ?? logoByName[key] ?? '';
      final existing = byKey['$category|$key'];
      if (existing != null) {
        final urls = existing['s'] as List<String>;
        if (!urls.contains(e.url)) urls.add(e.url);
        continue;
      }
      byKey['$category|$key'] = {
        'n': _cleanName(e.name),
        'l': logo,
        'c': category,
        'i': id,
        's': <String>[e.url],
      };
    }
  }

  // Within a channel the fastest link goes first; a channel whose best link is still slow carries a note.
  double ratioOf(String url) {
    final n = notes[url];
    if (n == null) return 5;   // not measured: assume fine, so a measured slow link sorts after it
    return double.tryParse(n.split(' ').last) ?? 5;
  }

  for (final ch in byKey.values) {
    final urls = ch['s'] as List<String>;
    urls.sort((a, b) => ratioOf(b).compareTo(ratioOf(a)));
    ch['w'] = (notes[urls.first] ?? '').startsWith('slow') ? 'slow' : '';
  }

  final list = byKey.values.toList();
  bool indian(Map m) => (m['i'] as String).contains('.in@') || (m['i'] as String).endsWith('.in');
  list.sort((a, b) {
    // Indian channels first (matters for the mostly international Sports list), then by name
    final ia = indian(a) ? 0 : 1, ib = indian(b) ? 0 : 1;
    if (ia != ib) return ia - ib;
    return (a['n'] as String).toLowerCase().compareTo((b['n'] as String).toLowerCase());
  });
  return list;
}
