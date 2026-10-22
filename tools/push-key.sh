#!/usr/bin/env bash
# Copy a search API key from secrets/<provider>.key to the launcher's private folder on the TV, where the
# Search screen reads it. The key is never printed and never leaves your computer except to the TV.
#
# Usage: TV_HOST=192.168.1.50 tools/push-key.sh [provider]     (default: watchmode)
#        TV_HOST=192.168.1.50 tools/push-key.sh --remove [provider]
set -euo pipefail
REMOVE=0
if [ "${1:-}" = --remove ]; then REMOVE=1; shift; fi
PROVIDER="${1:-watchmode}"
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=com.gathin.tvhome
DIR=/sdcard/Android/data/$PKG/files/keys
REMOTE="$DIR/$PROVIDER.key"
tv() { "$PY" -I tools/tv.py "$@"; }

if [ "$REMOVE" = 1 ]; then
  tv "rm -f $REMOTE; echo removed" | tail -n 1
  exit 0
fi
KEY="secrets/$PROVIDER.key"
[ -s "$KEY" ] || { echo "no $KEY: save your $PROVIDER API key there first (see docs/SEARCH.md)" >&2; exit 1; }
chmod 600 "$KEY" 2>/dev/null || true
tv "mkdir -p $DIR" >/dev/null
tv --push "$KEY" "$REMOTE" >/dev/null
echo "$PROVIDER key is on the TV ($(tv "wc -c < $REMOTE" | tr -d '[:space:]') bytes)."
