# CLAUDE.md

Upstream's guide for every agent, imported here; where it disagrees with this file, this file wins for this fork.

@AGENTS.md

Guidance for working in this repo. It covers the generic project only; keep anything specific to one TV out of it.

## What this repo is

Scripts and an Android TV launcher (`com.gathin.tvhome`) that strip bloatware from an Android/Google TV over
Wi-Fi ADB and replace the home screen. Nothing is uninstalled; every step is reversible. See `README.md`,
`PROMPT.md`, `CONTRIBUTING.md`.

## Layout

| Path | What |
|---|---|
| `launcher/` | Gradle project; Java sources in `launcher/app/src/main/java/com/gathin/tvhome/` |
| `apps/live-tv/` | Optional Flutter player for free public channels (package `ip_tv`, on the TV `com.nk.live_tv`); not part of the launcher build. Dart sources in `lib/`, tests in `test/` |
| `tools/` | `tv.py` (ADB client, used instead of an `adb` binary), `disable.sh`/`restore.sh`/`check.sh` (drift check after a firmware update), `theme.sh` (push config overlay), `push-key.sh`, `push-shows.sh`, `pluto-catalog.py`, `b64push.py`; for the Live TV app `build-live-tv.sh`, `check-live-channels.py` (link and speed check), `check-epg-coverage.py` (guide coverage), `tv-smoke-test.py`; `check-privacy.py` (the pre-commit scan) |
| `docs/` | Feature docs: `SEARCH.md`, `LIVE.md`, `MEMORY.md`, `SHOWS.md`, `LIVETV_APP.md`, `WATCHDOG.md`, `TROUBLESHOOTING.md`, `modules.md`; `UPSTREAM_SYNC.md` (what a sync from upstream must keep) |
| `examples/`, `themes/` | Per-TV lists and launcher themes |
| `tests/` | Python tests (`python3 -m unittest discover -s tests`), among them `test_debloat_paths.py`, `test_check_live_channels_headers.py`, `test_check_epg_coverage.py` |

Launcher features: Search pill (`TitleSearch.Provider`, Watchmode), Live pill (`LiveActivity`), Memory pill
(`MemoryActivity`: two lines, RAM and swap, each as free of total and percent free; Close all through the TV's own adb
when `memory_adb` is on), Shows tile (`ShowsActivity`, `ShowStore`, `MediaWatcher`), crash watchdog and safe mode
(`CrashWatchdog`, `WatchdogApp`, `docs/WATCHDOG.md`). Each is switched by a `show_*` setting and configured through
`Config.java`; the config overlay is JSON pushed with `tools/theme.sh`.

Live TV app (`apps/live-tv`, `docs/LIVETV_APP.md`): channels from M3U playlists chosen at build time
(`IPTV_PLAYLISTS`, `IPTV_GROUPS`, `IPTV_SKIP`, `IPTV_MIN_HEIGHT`), a link and speed check that writes the block list and the
speed notes (amber "slow" tag, small green dot for a channel measured fast), Referer and User-Agent headers for streams
that need them, and an optional programme guide (`EPG_URLS`, Now and Next in the player's info bar).

## Build and run

- Needs Java 17 and the Android command-line tools (`JAVA_HOME`, `ANDROID_HOME`).
- Build and install on a TV: `TV_HOST=<ip> launcher/build-and-install.sh` (`STOCK_LAUNCHER=none` to leave the stock
  launcher alone).
- ADB: `tools/venv/bin/python -I tools/tv.py "<shell command>"`. Create the venv from `tools/requirements.txt`.
  Commands over about 120 s time out, so split long work.
- After reinstalling the launcher, re-bind the notification listener:
  `cmd notification disallow_listener com.gathin.tvhome/com.gathin.tvhome.MediaWatcher`, then `allow_listener` for the same.
- Live TV app: `TV_HOST=<ip> tools/build-live-tv.sh` builds and installs it (needs Flutter); `BUILD_ONLY=1` builds the APK and
  stops. Settings are environment variables, see `docs/LIVETV_APP.md`.
- A smaller launcher with one language: `ORG_GRADLE_PROJECT_launcherLocales=en` before the build keeps only English
  resources (about 138 KB smaller). Unset keeps every translation.
- Permissions granted over ADB: `WRITE_SECURE_SETTINGS` (keyboard guard) and `appops ... GET_USAGE_STATS allow` (Memory pill).
- `screencap` is blank while video plays; use `dumpsys media_session` to confirm playback.

## Conventions

- Match the surrounding Java: comment density, naming, idiom. Config keys are snake_case in `Config.java`.
- Language: this fork's own builds and docs are English only (see the English-only build above); `README.vi.md` was removed. A pull request to upstream still follows upstream's rules for strings.
- New feature: generic code in the launcher, a separate `docs/<FEATURE>.md`, a `show_*` switch. Do not add TV-specific
  content to `README.md` or the shared code; keep it in config.
- Keep the repo free of device data: no serials, IPs, android_ids, API keys, or `backup/` dumps (`.gitignore` covers
  `backup/`, `secrets/`, `*.key`).

## Git

- `main` mirrors `upstream/main` (fast-forward only). Work goes on `dev`; push with `--force-with-lease` after rebasing.
- Commit only when asked. End commit messages with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.
- A pull request to upstream comes from its own branch cut from `upstream/main` (`pr/<name>`), never from `dev`. Upstream's
  CI has hard gates: the release APK stays under 480 KB, every key in `themes/README.md` needs a real-TV screenshot row in
  `docs/switches.md`, and a new switch needs a Keys-table row. Read `AGENTS.md` first (it came with the sync of 2026-10-23).

## Syncing from upstream

- Our deliberate changes live in `docs/UPSTREAM_SYNC.md`. On a conflict keep ours; take upstream only for a real bug
  fix, crash robustness or a measured gain. Do not accept changes just because they are newer. Read that file first.

## Backups

- Never overwrite, edit or delete an existing backup (`backup/` and any per-TV backup folder). A new snapshot goes in a
  new dated folder; if a file there is locked (read-only or `chflags uchg`), stop and tell the user rather than unlock it.

## Secrets and privacy

- API keys live in `secrets/` (git-ignored). Never read, print or ask to see them. Only scripts (`tools/push-key.sh`)
  handle them, and they print no key material.
- Do not choose consent, profile or sign-in prompts on the user's behalf.
- Do not build tools that download or rip streamed content from services such as Pluto TV, Netflix or Prime.
