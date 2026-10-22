#!/usr/bin/env bash
# Build the optional Live TV app (apps/live-tv, Flutter) and install it on the TV. See docs/LIVETV_APP.md.
# Most TVs are 32-bit ARM (armeabi-v7a); for a 64-bit TV set TARGET=android-arm64. Check with:
#   tools/venv/bin/python -I tools/tv.py "getprop ro.product.cpu.abilist"
#
# Usage: TV_HOST=192.168.1.50 tools/build-live-tv.sh
#        TV_HOST=192.168.1.50 IPTV_PLAYLISTS=countries/in,languages/mar IPTV_MIN_HEIGHT=720 tools/build-live-tv.sh
#        IPTV_PLAYLISTS=countries/in,https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8 IPTV_GROUPS=India ...
set -euo pipefail
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
command -v flutter >/dev/null || { echo "Flutter is needed: brew install --cask flutter (or https://docs.flutter.dev/get-started/install)" >&2; exit 1; }
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export MSYS_NO_PATHCONV=1

# IPTV_PLAYLISTS (iptv-org paths or full playlist URLs, default countries/in), IPTV_GROUPS (group-titles to keep from
# full-URL playlists, e.g. India), IPTV_SKIP (words to leave out, e.g. pluto) and IPTV_MIN_HEIGHT (default 1080) pick
# the channels. tools/check-live-channels.py writes the dead links the app skips (assets/blocked_streams.txt).
(cd apps/live-tv && flutter build apk --release --target-platform "${TARGET:-android-arm}" --android-skip-build-dependency-validation \
  --dart-define=IPTV_PLAYLISTS="${IPTV_PLAYLISTS:-countries/in}" --dart-define=IPTV_GROUPS="${IPTV_GROUPS:-}" \
  --dart-define=IPTV_SKIP="${IPTV_SKIP:-}" \
  --dart-define=IPTV_MIN_HEIGHT="${IPTV_MIN_HEIGHT:-1080}")
APK=apps/live-tv/build/app/outputs/flutter-apk/app-release.apk
[ -f "$APK" ] || { echo "the build did not produce $APK" >&2; exit 1; }

"$PY" -I tools/b64push.py "$APK" /data/local/tmp/livetv.apk | tail -n 1
"$PY" -I tools/tv.py "pm install -r /data/local/tmp/livetv.apk; rm /data/local/tmp/livetv.apk"
echo "Live TV installed (com.nk.live_tv). Open it from the app grid."
