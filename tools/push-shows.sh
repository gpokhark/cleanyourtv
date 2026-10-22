#!/usr/bin/env bash
# Copy show catalogues (the .json files made by tools/pluto-catalog.py) to the launcher on the TV, where the
# Shows screen reads them. Your progress on the TV is kept.
#
# Usage: TV_HOST=192.168.1.50 tools/push-shows.sh my-tv/shows
set -euo pipefail
DIR_ARG="${1:?usage: push-shows.sh <folder with show .json files>}"
case "$DIR_ARG" in /*) ;; *) DIR_ARG="$PWD/$DIR_ARG" ;; esac
cd "$(dirname "$0")/.."
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0 my-tv/shows}"
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
REMOTE=/sdcard/Android/data/com.gathin.tvhome/files/shows
"$PY" -I tools/tv.py "mkdir -p $REMOTE" >/dev/null
n=0
for f in "$DIR_ARG"/*.json; do
  [ -e "$f" ] || { echo "no .json files in $DIR_ARG" >&2; exit 1; }
  "$PY" -I tools/b64push.py "$f" "$REMOTE/$(basename "$f")" | tail -n 1
  n=$((n + 1))
done
echo "$n show(s) on the TV."
