import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:fvp/fvp.dart' show FVPControllerExtensions;
import 'package:path_provider/path_provider.dart';
import 'package:video_player/video_player.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../model/channel.dart';
import '../provider/channels_provider.dart';

/// Test mode, started from a computer with tools/tv-smoke-test.py: a file named "smoke" in the app's external files
/// folder holds a comma-separated list of categories to test ("Movies,Sports"), or "all", or the word "urls" on the first
/// line followed by one stream link per line to test only those. The file is deleted as soon
/// as it is read, so a crash cannot leave the app stuck in test mode. Returns the request, or null for a normal start.
Future<String?> smokeRequest() async {
  try {
    final dir = await getExternalStorageDirectory();
    if (dir == null) return null;
    final f = File('${dir.path}/smoke');
    if (!await f.exists()) return null;
    final text = (await f.readAsString()).trim();
    await f.delete();
    return text.isEmpty ? 'all' : text;
  } catch (_) {
    return null;
  }
}

/// Plays every stream of the chosen categories for a few seconds, one at a time, and logs one line each:
/// SMOKE|n|total|channel|stream index|result|init ms|stalls|buffering ms|seconds played of 8|audio tracks|url
/// Result is OK (starts and plays smoothly), SLOW (starts but stalls or falls behind), STALL (starts, hardly plays),
/// or FAIL (does not start, or errors). tools/tv-smoke-test.py reads these lines over adb.
class SmokeScreen extends StatefulWidget {
  final String request;
  const SmokeScreen({super.key, required this.request});

  @override
  State<SmokeScreen> createState() => _SmokeScreenState();
}

class _SmokeScreenState extends State<SmokeScreen> {
  static const _watch = Duration(seconds: 8);
  static const _initTimeout = Duration(seconds: 15);

  VideoPlayerController? controller;
  String status = 'Loading channels ...';

  /// The links to test when the request is a list of them, else null.
  late final Set<String>? _links = () {
    final lines = widget.request.split('\n').map((l) => l.trim()).where((l) => l.isNotEmpty).toList();
    return lines.isNotEmpty && lines.first == 'urls' ? lines.skip(1).toSet() : null;
  }();

  @override
  void initState() {
    super.initState();
    WakelockPlus.enable();
    _run();
  }

  @override
  void dispose() {
    WakelockPlus.disable();
    super.dispose();
  }

  Future<List<Channel>> _channels() async {
    final provider = ChannelsProvider();
    List<Channel> all;
    try {
      all = await provider.refresh();
    } catch (_) {
      all = await provider.loadCached() ?? const [];
    }
    if (_links != null) return all;
    final want = widget.request.split(',').map((c) => c.trim().toLowerCase()).where((c) => c.isNotEmpty).toSet();
    if (want.contains('all')) return all;
    return [for (final c in all) if (want.contains(c.category.toLowerCase())) c];
  }

  Future<void> _run() async {
    final channels = await _channels();
    final streams = [
      for (final c in channels)
        for (var i = 0; i < c.streamUrls.length; i++)
          if (_links == null || _links.contains(c.streamUrls[i])) (c, i),
    ];
    var ok = 0, slow = 0, bad = 0;
    for (var n = 0; n < streams.length; n++) {
      if (!mounted) return;
      final (ch, si) = streams[n];
      setState(() => status = '${n + 1} of ${streams.length}  ·  ${ch.name}');
      final result = await _test(ch, si, n + 1, streams.length);
      if (result == 'OK') {
        ok++;
      } else if (result == 'SLOW') {
        slow++;
      } else {
        bad++;
      }
    }
    debugPrint('SMOKE|done|${streams.length}|ok=$ok|slow=$slow|bad=$bad');
    await Future.delayed(const Duration(seconds: 1));
    SystemNavigator.pop();
  }

  Future<String> _test(Channel ch, int si, int n, int total) async {
    final url = ch.streamUrls[si];
    final c = VideoPlayerController.networkUrl(Uri.parse(url));
    final sw = Stopwatch()..start();
    var result = 'FAIL', initMs = 0, stalls = 0, bufMs = 0;
    var played = 0.0;
    var audio = '-';
    try {
      await c.initialize().timeout(_initTimeout);
      initMs = sw.elapsedMilliseconds;
      if (mounted) setState(() => controller = c);
      await c.play();
      final startPos = c.value.position;
      var buffering = false;
      var bufSince = DateTime.now();
      for (var i = 0; i < _watch.inMilliseconds ~/ 500; i++) {
        await Future.delayed(const Duration(milliseconds: 500));
        final v = c.value;
        if (v.hasError) throw Exception(v.errorDescription);
        if (v.isBuffering && !buffering && i >= 4) {   // the first two seconds are start-up, not a stall
          buffering = true;
          bufSince = DateTime.now();
          stalls++;
        } else if (!v.isBuffering && buffering) {
          buffering = false;
          bufMs += DateTime.now().difference(bufSince).inMilliseconds;
        }
      }
      if (buffering) bufMs += DateTime.now().difference(bufSince).inMilliseconds;
      played = (c.value.position - startPos).inMilliseconds / 1000;
      try {
        final tracks = c.getMediaInfo()?.audio;
        audio = tracks == null || tracks.isEmpty ? 'none' : tracks.map((t) => '${t.codec.channels}ch').join('+');
      } catch (_) {}
      result = played >= 6.5 && stalls <= 1 ? 'OK' : (played >= 3 ? 'SLOW' : 'STALL');
    } on TimeoutException {
      result = 'FAIL';
      audio = 'timeout';
    } catch (e) {
      result = 'FAIL';
      audio = 'error';
    } finally {
      if (mounted) setState(() => controller = null);
      await c.dispose();
    }
    debugPrint('SMOKE|$n|$total|${ch.name}|$si|$result|init=$initMs|stalls=$stalls|buf=$bufMs|played=${played.toStringAsFixed(1)}|audio=$audio|$url');
    return result;
  }

  @override
  Widget build(BuildContext context) {
    final c = controller;
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(fit: StackFit.expand, children: [
        if (c != null && c.value.isInitialized)
          Center(child: AspectRatio(aspectRatio: c.value.aspectRatio > 0 ? c.value.aspectRatio : 16 / 9, child: VideoPlayer(c))),
        Align(
          alignment: Alignment.bottomLeft,
          child: Container(
            color: const Color(0xAA000000),
            padding: const EdgeInsets.all(12),
            child: Text('Test mode  ·  $status', style: const TextStyle(color: Colors.white, fontSize: 16)),
          ),
        ),
      ]),
    );
  }
}
