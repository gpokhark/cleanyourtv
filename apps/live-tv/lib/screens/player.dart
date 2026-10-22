import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:fvp/fvp.dart' show FVPControllerExtensions;
import 'package:video_player/video_player.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../model/channel.dart';
import '../widgets/channel_icon.dart';
import 'home.dart' show isSelectKey;

/// Full-screen live player.
/// Up/Down (or Channel +/-) changes channel, OK shows the info banner, Back returns.
/// A stream that fails or stalls on start falls back to the channel's backup streams.
class Player extends StatefulWidget {
  final List<Channel> channels;
  final int index;

  const Player({super.key, required this.channels, required this.index});

  @override
  State<Player> createState() => _PlayerState();
}

class _PlayerState extends State<Player> {
  static const _startTimeout = Duration(seconds: 12);

  late int index = widget.index;
  int streamIdx = 0;
  VideoPlayerController? controller;
  bool failed = false;
  bool showInfo = true;
  Timer? infoTimer, startTimer, zapTimer;
  int _generation = 0;
  final focus = FocusNode();

  Channel get channel => widget.channels[index];

  @override
  void initState() {
    super.initState();
    WakelockPlus.enable();
    _open();
  }

  @override
  void dispose() {
    infoTimer?.cancel();
    startTimer?.cancel();
    zapTimer?.cancel();
    controller?.dispose();
    focus.dispose();
    WakelockPlus.disable();
    super.dispose();
  }

  /// How many audio tracks the stream has, and what the last pick was; left and right change the track when there is more than one.
  int audioCount = 1;
  String? audioNote;

  Future<void> _open() async {
    final gen = ++_generation;
    final old = controller;
    controller = null;
    failed = false;
    audioCount = 1;
    audioNote = null;
    showInfo = true;          // banner stays up until the video actually starts
    infoTimer?.cancel();
    setState(() {});
    old?.dispose();

    final c = VideoPlayerController.networkUrl(Uri.parse(channel.streamUrls[streamIdx]));
    controller = c;
    startTimer?.cancel();
    startTimer = Timer(_startTimeout, () {
      if (gen == _generation && !c.value.isInitialized) _nextStream(gen);
    });
    c.addListener(() {
      if (gen == _generation && c.value.hasError) _nextStream(gen);
    });
    try {
      await c.initialize();
      if (gen != _generation) return;
      startTimer?.cancel();
      _preferStereo(c);
      // The track list can be empty right at the start of a live stream: look again after it has been playing a moment.
      Timer(const Duration(seconds: 3), () {
        if (gen == _generation && mounted) _preferStereo(c);
      });
      await c.play();
      if (mounted) setState(_flashInfo);
    } catch (_) {
      if (gen == _generation) _nextStream(gen);
    }
  }

  /// A stream can carry a 5.1 track and a stereo track that are different mixes: Movies Now HD's 5.1 track has silent centre
  /// and surround channels and is 6 dB quieter, and with it playing the music is heard but speech is not. When the stream
  /// also carries a stereo track, play that one; Left and Right switch.
  void _preferStereo(VideoPlayerController c) {
    try {
      final audio = c.getMediaInfo()?.audio;
      final active = c.getActiveAudioTracks();
      debugPrint('AUDIO ${channel.name}: tracks=${audio == null ? 'none' : [for (final a in audio) '${a.index}:${a.codec.channels}ch'].toString()} active=$active');
      if (audio == null || audio.length < 2) return;
      if (mounted && audioCount != audio.length) setState(() => audioCount = audio.length);
      final stereo = audio.indexWhere((a) => a.codec.channels == 2);
      if (stereo < 0) return;
      if (active != null && active.length == 1 && active.first == stereo) return;
      c.setAudioTracks([stereo]);
      debugPrint('AUDIO ${channel.name}: chose track $stereo, active now ${c.getActiveAudioTracks()}');
    } catch (e) {
      debugPrint('AUDIO ${channel.name}: track choice failed: $e');
    }
  }

  /// Left and right: the previous or next audio track, shown in the banner ("Audio 2 of 5  ·  stereo  ·  hin").
  void _cycleAudio(int delta) {
    final c = controller;
    if (c == null || !c.value.isInitialized) return;
    try {
      final audio = c.getMediaInfo()?.audio;
      if (audio == null || audio.length < 2) return;
      final active = c.getActiveAudioTracks();
      final now = (active != null && active.isNotEmpty) ? active.first : 0;
      final next = ((now + delta) % audio.length + audio.length) % audio.length;
      c.setAudioTracks([next]);
      final ch = audio[next].codec.channels;
      final lang = audio[next].metadata['language'] ?? audio[next].metadata['title'];
      setState(() {
        audioCount = audio.length;
        audioNote = 'Audio ${next + 1} of ${audio.length}  ·  ${ch == 2 ? 'stereo' : ch == 1 ? 'mono' : '$ch channels'}'
            '${lang != null && lang.isNotEmpty ? '  ·  $lang' : ''}';
        _flashInfo();
      });
    } catch (_) {}
  }

  /// Current stream failed: try the channel's next backup, else show the error.
  void _nextStream(int gen) {
    if (gen != _generation || !mounted) return;
    if (streamIdx + 1 < channel.streamUrls.length) {
      streamIdx++;
      _open();
    } else {
      startTimer?.cancel();
      setState(() {
        failed = true;
        showInfo = true;
      });
      infoTimer?.cancel();
    }
  }

  void _zap(int delta) {
    final n = widget.channels.length;
    setState(() {
      index = (index + delta) % n;
      if (index < 0) index += n;
      streamIdx = 0;
      showInfo = true;
    });
    infoTimer?.cancel();
    // pressing Up/Down quickly skips channels without starting each stream
    zapTimer?.cancel();
    zapTimer = Timer(const Duration(milliseconds: 350), _open);
  }

  void _flashInfo() {
    showInfo = true;
    infoTimer?.cancel();
    infoTimer = Timer(const Duration(seconds: 4), () {
      if (mounted && !failed) setState(() => showInfo = false);
    });
  }

  KeyEventResult _onKey(FocusNode _, KeyEvent e) {
    if (e is KeyUpEvent) return KeyEventResult.ignored;
    final k = e.logicalKey;
    if (k == LogicalKeyboardKey.arrowUp || k == LogicalKeyboardKey.channelUp) {
      _zap(-1);
    } else if (k == LogicalKeyboardKey.arrowDown || k == LogicalKeyboardKey.channelDown) {
      _zap(1);
    } else if (!failed && audioCount > 1 && (k == LogicalKeyboardKey.arrowLeft || k == LogicalKeyboardKey.arrowRight)) {
      if (e is KeyDownEvent) _cycleAudio(k == LogicalKeyboardKey.arrowRight ? 1 : -1);
    } else if (isSelectKey(k) || k == LogicalKeyboardKey.arrowLeft || k == LogicalKeyboardKey.arrowRight) {
      if (e is KeyDownEvent) {
        if (failed) {
          streamIdx = 0;
          _open();
        } else {
          setState(_flashInfo);
        }
      }
    } else {
      return KeyEventResult.ignored;
    }
    return KeyEventResult.handled;
  }

  @override
  Widget build(BuildContext context) {
    final c = controller;
    final ready = c != null && c.value.isInitialized;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) Navigator.of(context).pop(index);
      },
      child: Focus(
        focusNode: focus,
        autofocus: true,
        onKeyEvent: _onKey,
        child: Scaffold(
          backgroundColor: Colors.black,
          body: Stack(
            fit: StackFit.expand,
            children: [
              if (ready)
                Center(
                  child: AspectRatio(
                    aspectRatio: c.value.aspectRatio > 0 ? c.value.aspectRatio : 16 / 9,
                    child: VideoPlayer(c),
                  ),
                ),
              if (!failed)
                // only the spinner listens to playback updates, not the whole screen
                ValueListenableBuilder<VideoPlayerValue>(
                  valueListenable: c ?? ValueNotifier(const VideoPlayerValue.uninitialized()),
                  builder: (_, v, __) => (!v.isInitialized || v.isBuffering)
                      ? const Center(
                          child: SizedBox(
                              width: 34,
                              height: 34,
                              child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white70)))
                      : const SizedBox.shrink(),
                ),
              if (failed)
                Center(
                  child: Column(mainAxisSize: MainAxisSize.min, children: [
                    const Icon(Icons.signal_wifi_bad_rounded, color: Colors.white54, size: 40),
                    const SizedBox(height: 12),
                    const Text('This channel isn’t available right now',
                        style: TextStyle(color: Colors.white, fontSize: 18, fontWeight: FontWeight.w600)),
                    const SizedBox(height: 6),
                    Text('OK to retry  ·  ▲ ▼ for another channel',
                        style: TextStyle(color: Colors.white.withValues(alpha: 0.6), fontSize: 13)),
                  ]),
                ),
              AnimatedOpacity(
                opacity: showInfo ? 1 : 0,
                duration: const Duration(milliseconds: 250),
                child: Align(alignment: Alignment.bottomCenter, child: _infoBar()),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _infoBar() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(32, 40, 32, 26),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0x00000000), Color(0xCC000000)],
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          ChannelIcon(key: ValueKey(channel.logoUrl + channel.name), channel: channel, width: 96, height: 56),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(channel.name,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Colors.white, fontSize: 22, fontWeight: FontWeight.w700)),
                const SizedBox(height: 4),
                Text(
                  '${channel.category}  ·  ${index + 1} of ${widget.channels.length}  ·  1080p'
                  '${streamIdx > 0 ? '  ·  backup stream ${streamIdx + 1}' : ''}'
                  '${channel.note.isNotEmpty ? '  ·  slow source, may buffer' : ''}',
                  style: TextStyle(color: Colors.white.withValues(alpha: 0.65), fontSize: 13),
                ),
                if (audioNote != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 3),
                    child: Text(audioNote!, style: const TextStyle(color: Colors.white, fontSize: 14, fontWeight: FontWeight.w600)),
                  ),
              ],
            ),
          ),
          Text('▲ ▼  change channel${audioCount > 1 ? '\n◀ ▶  audio track' : ''}',
              textAlign: TextAlign.right, style: TextStyle(color: Colors.white.withValues(alpha: 0.5), fontSize: 12)),
        ],
      ),
    );
  }
}
