import 'package:fvp/fvp.dart' as fvp;
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import 'screens/home.dart';
import 'screens/smoke.dart';
import 'widgets/logo_cache.dart';

/// Build with --dart-define=PERF=true to log every frame's build/raster time to logcat.
const _perf = bool.fromEnvironment('PERF');

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // Plays through libmdk (FFmpeg) instead of Android's decoders, so MP2 and 5.1 AC-3 audio the TV cannot decode still
  // plays. Without this line the app uses the TV's own decoders.
  fvp.registerWith();
  await LogoCache.init();
  final smoke = await smokeRequest();
  runApp(smoke == null ? const MyApp() : MaterialApp(debugShowCheckedModeBanner: false, home: SmokeScreen(request: smoke)));
  if (_perf) {
    SchedulerBinding.instance.addTimingsCallback((timings) {
      for (final t in timings) {
        debugPrint('PERF build=${t.buildDuration.inMicroseconds} raster=${t.rasterDuration.inMicroseconds}');
      }
    });
  }
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Live TV',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: bg,
        colorScheme: const ColorScheme.dark(primary: Colors.white, surface: bg),
        // no Material ink/splash on TV: focus visuals are drawn by the widgets themselves
        splashFactory: NoSplash.splashFactory,
        highlightColor: Colors.transparent,
        focusColor: Colors.transparent,
      ),
      home: const Home(),
    );
  }
}
