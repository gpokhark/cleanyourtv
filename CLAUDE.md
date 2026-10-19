# CLAUDE.md

Guidance for working in this repo. It covers the generic project only; keep anything specific to one TV out of it.

## What this repo is

Scripts and an Android TV launcher (`com.gathin.tvhome`) that strip bloatware from an Android/Google TV over
Wi-Fi ADB and replace the home screen. Nothing is uninstalled; every step is reversible. See `README.md`,
`PROMPT.md`, `CONTRIBUTING.md`.

## Layout

| Path | What |
|---|---|
| `launcher/` | Gradle project; Java sources in `launcher/app/src/main/java/com/gathin/tvhome/` |
| `tools/` | `tv.py` (ADB client, used instead of an `adb` binary), `disable.sh`/`restore.sh`/`check.sh` (drift check after a firmware update), `theme.sh` (push config overlay), `push-key.sh`, `push-shows.sh`, `pluto-catalog.py`, `b64push.py` |
| `docs/` | Feature docs: `SEARCH.md`, `LIVE.md`, `MEMORY.md`, `SHOWS.md`, `TROUBLESHOOTING.md`, `modules.md` |
| `examples/`, `themes/` | Per-TV lists and launcher themes |
| `tests/` | `test_debloat_paths.py` |

Launcher features: Search pill (`TitleSearch.Provider`, Watchmode), Live pill (`LiveActivity`), Memory pill
(`MemoryActivity`), Shows tile (`ShowsActivity`, `ShowStore`, `MediaWatcher`). Each is switched by a `show_*`
setting and configured through `Config.java`; the config overlay is JSON pushed with `tools/theme.sh`.

## Build and run

- Needs Java 17 and the Android command-line tools (`JAVA_HOME`, `ANDROID_HOME`).
- Build and install on a TV: `TV_HOST=<ip> launcher/build-and-install.sh` (`STOCK_LAUNCHER=none` to leave the stock
  launcher alone).
- ADB: `tools/venv/bin/python -I tools/tv.py "<shell command>"`. Create the venv from `tools/requirements.txt`.
  Commands over about 120 s time out, so split long work.
- After reinstalling the launcher, re-bind the notification listener:
  `cmd notification disallow_listener com.gathin.tvhome/com.gathin.tvhome.MediaWatcher`, then `allow_listener` for the same.
- Permissions granted over ADB: `WRITE_SECURE_SETTINGS` (keyboard guard) and `appops ... GET_USAGE_STATS allow` (Memory pill).
- `screencap` is blank while video plays; use `dumpsys media_session` to confirm playback.

## Conventions

- Match the surrounding Java: comment density, naming, idiom. Config keys are snake_case in `Config.java`.
- New feature: generic code in the launcher, a separate `docs/<FEATURE>.md`, a `show_*` switch. Do not add TV-specific
  content to `README.md` or the shared code; keep it in config.
- Keep the repo free of device data: no serials, IPs, android_ids, API keys, or `backup/` dumps (`.gitignore` covers
  `backup/`, `secrets/`, `*.key`).

## Git

- `main` mirrors `upstream/main` (fast-forward only). Work goes on `dev`; push with `--force-with-lease` after rebasing.
- Commit only when asked. End commit messages with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## Secrets and privacy

- API keys live in `secrets/` (git-ignored). Never read, print or ask to see them. Only scripts (`tools/push-key.sh`)
  handle them, and they print no key material.
- Do not choose consent, profile or sign-in prompts on the user's behalf.
- Do not build tools that download or rip streamed content from services such as Pluto TV, Netflix or Prime.
