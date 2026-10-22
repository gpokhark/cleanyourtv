# Keeping our changes when syncing from upstream

`dev` carries deliberate changes that upstream (`Gathin23/cleanyourtv`, our `main`) does not have, made so the launcher
works on a TV with many top pills, little RAM and a flaky firmware. A sync must not undo them.

## The rule

When `main` and `dev` conflict, **keep ours**. Take upstream's version only when it is clearly better in one of these ways:

- a **bug fix** for something that is wrong on our build too;
- **crash or robustness** handling (null checks, lifecycle, permissions, errors) that ours lacks;
- a **measured** gain (less memory, fewer redraws) that does not remove a feature below.

Everything else (new looks, rearranged layouts, renamed things, new defaults) is not taken just because it is newer.
Where both sides add something (a config key, a view, a constructor argument), keep both. After each sync, say in the
summary which upstream changes were accepted and why, and which were declined.

## What must survive a sync

| Area | What we keep | Where |
|---|---|---|
| Home header | The day, date and extra clocks sit **under the clock, below the pills, full width**. Upstream puts the date in a column beside the clock; with six pills it is cut to "WE…". Same in `layout-iw` | `layout/activity_main.xml`, `layout-iw/activity_main.xml` |
| Pills | Search, Live, Inputs, Wi-Fi, Memory, Settings; `firstTopButton()` goes Search, Live, Inputs, Wi-Fi. The Hebrew layout has them too (a test checks the ids) | `MainActivity`, layouts |
| Themes | Text and glass colours come from theme attributes (`?attr/text_*`, `Appearance`, `ThemeStyle`, `theme_style`, `theme_mode`); `DockDecoration` and `GlassView` read them. New upstream colours should use the attributes, not hard-coded values | `docs/THEMES.md` |
| Sky | Upstream's `Sun` sunrise/sunset maths, plus `SkyView.setLocation` that prefers the theme's `sky_latitude`/`sky_longitude`. No location permission | `docs/sky.md` |
| Crash safety | `CrashWatchdog`, `WatchdogApp`, safe mode. Safe mode also switches off upstream's wallpaper system and `memory_adb` | `docs/WATCHDOG.md` |
| Close apps | `memory_adb`: `AdbClient`, `LocalAdb`, wired in `MemoryActivity`, `MemoryTrim`. Off by default, loopback only, force-stop only | `docs/MEMORY.md`, `PROMPT.md` exception |
| Memory pill | Reads MemAvailable and rounds as we do | `MemoryActivity` |
| Remote buttons | `key_apps` (button number to app) next to upstream's `app_keys`; the pass-through line in `InputKeyService.onKeyEvent` must stay exactly `if (!isInputKey(event.getKeyCode())) return false;` (a test reads it) | `docs/KEYS.md` |
| Grid | `AppAdapter` types: EMPTY = 3, SHOWS = 4 | `AppAdapter` |
| Config | Our keys in `Config.java` (24 constructor arguments appended, theme sync before safe mode, `readOverlay`) | `Config.java` |
| Live TV app | `apps/live-tv` with fvp audio, filters, block list (committed copies of the block list and speed notes stay EMPTY) | `docs/LIVETV_APP.md` |
| Privacy | `tools/check-privacy.py`, the pre-commit hook, `.gitignore` guard for `my-tv/` and `my-firetv/` | `CONTRIBUTING.md` |

## Taken from upstream on purpose

`tileRefresh` (back to Home without rebuilding tiles unless apps changed; replaced our `rebuildOnResume`), the app-coloured
glow in `GlassView`, `app_keys`, and the `Sky`/`Sun` maths. Add to this list when a sync accepts something.

## Checks after a sync

1. `cd launcher && ./gradlew assembleDebug testDebugUnitTest` and `python3 -m unittest discover -s tests`.
2. Install on the TV and look at Home: the date reads in full, every pill shows, Abyss and the sky draw, Memory Close all
   works, the remote buttons open their apps, no safe-mode notice.
3. `tools/check-privacy.py`, then force-push `dev` with `--force-with-lease` only after the user agrees.

Known open spot: upstream's race-weekend panel sits 158dp from the top, where our date and extra clocks now are. It only
shows when a theme sets a race calendar; check the overlap if one is used.
