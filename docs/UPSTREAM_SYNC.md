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
| Pills | Search, Live, Inputs, Wi-Fi, Memory, Settings; `firstTopButton()` goes Search, Live, Inputs, Wi-Fi. | `MainActivity`, layouts |
| Themes | Text and glass colours come from theme attributes (`?attr/text_*`, `Appearance`, `ThemeStyle`, `theme_style`, `theme_mode`); `DockDecoration` and `GlassView` read them. New upstream colours should use the attributes, not hard-coded values | `docs/THEMES.md` |
| Sky | Upstream's `Sun` sunrise/sunset maths, plus `SkyView.setLocation` that prefers the theme's `sky_latitude`/`sky_longitude`. No location permission | `docs/sky.md` |
| Crash safety | `CrashWatchdog`, `WatchdogApp`, safe mode. Safe mode also switches off upstream's wallpaper system and `memory_adb`. On `dev` it is always on. Pull request #844 to upstream makes it opt-in (`crash_watchdog`, default off, message in English only): if that is merged, the next sync brings the switch, and our `config.xml` must set it to `true` | `docs/WATCHDOG.md` |
| Close apps | `memory_adb`: `AdbClient`, `LocalAdb`, wired in `MemoryActivity`, `MemoryTrim`. Off by default, loopback only, force-stop only | `docs/MEMORY.md`, `PROMPT.md` exception |
| Memory pill | Two lines, RAM and swap, each "x free of y · n% free" (totals as 1 GB, 2 GB), from MemTotal, MemAvailable and the swap lines of /proc/meminfo; `memory_ram`, `memory_swap`, `memory_swap_none` strings | `MemoryActivity`, `docs/MEMORY.md` |
| Remote buttons | `key_apps` (button number to app) next to upstream's `app_keys`; the pass-through line in `InputKeyService.onKeyEvent` must stay exactly `if (!isInputKey(event.getKeyCode())) return false;` (a test reads it) | `docs/KEYS.md` |
| Grid | `AppAdapter` types: EMPTY = 3, SHOWS = 4 | `AppAdapter` |
| Config | Our keys in `Config.java` (our constructor arguments appended, theme sync before safe mode, `readOverlay`) | `Config.java` |
| Live TV app | `apps/live-tv` with fvp audio, iptv-org categories, the `name=` filter, Referer and User-Agent headers, the programme guide (`epg.dart`), the sidebar that scrolls with the remote, the small green dot, block list and speed notes (the committed copies of both stay EMPTY) | `docs/LIVETV_APP.md` |
| English-only build | `launcher/app/build.gradle` reads `launcherLocales` (`resourceConfigurations`), unset keeps everything; `my-tv/apply.sh` sets it to `en`. A sync must keep that block | `CLAUDE.md` |
| Privacy | `tools/check-privacy.py`, the pre-commit hook, `.gitignore` guard for `my-tv/` and `my-firetv/` | `CONTRIBUTING.md` |

## Two implementations that stay ours

- **Crash watchdog and safe mode** (`CrashWatchdog`, `WatchdogApp`). If upstream adds its own crash handling, keep ours
  and use theirs only if it is a better alternative (it recovers from more cases, or costs less) and does not drop what
  ours does: crash-loop detection, safe mode, and switching off `memory_adb` and the wallpaper system there.
- **Close apps through adb** (`memory_adb`: `AdbClient`, `LocalAdb`). The TV's firmware ignores the launcher's own kill
  request, so this is how Close all works here, and the user confirmed it works. If upstream adds another way to close
  apps, keep ours until theirs is shown to work on this TV and to be better; do not swap it in on the strength of the
  code alone.

Confirmed working on the TV on 2026-10-23 after the last sync (English-only build): Home with the date in full, all six pills, the two-line Memory pill, and the four remote buttons (`key_apps`). Close all and Close selected through adb were last confirmed on 2026-10-21 and not repeated after this sync.

## Sync of 2026-10-23 (472 upstream commits, up to `0cd7c271`)

Where both sides changed the same lines, both were kept: `Config` (upstream's library apps, tile menu, cinematic and wallpaper
options with our keys, the safe-mode guard on upstream's on-TV wallpaper pick), `InputKeyService` (upstream's `onUnbind` repair
beside our `key_apps` and pause/resume), `LauncherSettingsActivity`, `MainActivity` (`Appearance.apply` before the layout, then
upstream's library selection and service repair), `DockDecoration` (our `Context` and theme radii with upstream's `evenPadding`),
`AppAdapter.cornerRadius` (a two-argument overload for upstream's test) and the Hebrew layout (which was then removed, see below). Declined: upstream's date beside the clock (`date_format_home`, `date_single_line`); ours
stays under the clock. Taken as they came: `AGENTS.md`, the font system (`font_*` strings), the wallpaper collection system, the
library, tile menu and cinematic Home, the new tools and tests. Where upstream's tests wanted something our own features lack:
Keys-table rows and README tool rows were added, and our own keys are listed in a second block under `SCREENSHOT_WANTED` in
`tests/test_switches.py` (keep it when that file changes). The release APK is now larger than upstream's 480 KB gate (CI there
does not apply to `dev`): measure it with `launcherLocales=en` when it matters. All translations were removed on purpose and stay removed: the `values-<language>` folders, `layout-iw` (Hebrew),
`tests/test_hebrew_resources.py`, the translated READMEs with their links, and the Hebrew screenshots. If upstream edits one of those
files the sync reports a modify/delete conflict: delete the file. `locales_config.xml` lists only English, and
`tests/test_readme_translations.py` accepts no translations.

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
