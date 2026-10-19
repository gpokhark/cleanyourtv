#!/usr/bin/env bash
# Check that a debloat file still holds on the TV. A firmware update can switch disabled packages back on or put the
# stock launcher back as Home. Lists what drifted; with --fix, disables the packages again (never uninstalls).
# Exit status: 0 = nothing drifted, 1 = something did, 2 = the TV could not be reached.
#
# Usage: TV_HOST=192.168.1.50 tools/check.sh [--fix] [--home your.launcher.package] examples/xiaomi-mi-tv-4a/debloat.txt
set -euo pipefail
FIX=0
HOME_PKG=com.gathin.tvhome
while [ $# -gt 0 ]; do
  case "$1" in
    --fix) FIX=1; shift ;;
    --home) HOME_PKG="${2:?--home needs a package}"; shift 2 ;;
    -*) echo "unknown option: $1"; exit 2 ;;
    *) break ;;
  esac
done
LIST="${1:?usage: check.sh [--fix] [--home package] <debloat list>}"
# Resolve the list against the caller's directory before moving into the repo.
LIST_DIR="$(cd "$(dirname "$LIST")" && pwd)"
LIST="$LIST_DIR/$(basename "$LIST")"
cd "$(dirname "$0")/.."
PY=tools/venv/bin/python
[ -x "$PY" ] || PY=tools/venv/Scripts/python.exe
export MSYS_NO_PATHCONV=1
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt"; exit 1; }
tv() { "$PY" -I tools/tv.py "$@"; }
unreachable() { echo "Cannot reach the TV at ${TV_HOST:-<TV_HOST not set>}. Is it on, and is USB debugging still enabled?"; exit 2; }

STATE="$(tv "pm list packages -d; echo '#--installed'; pm list packages; echo '#--home'; cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1" 2>/dev/null | tr -d '\r')" || unreachable
DISABLED="$(printf '%s\n' "$STATE" | sed -n '1,/^#--installed/p' | sed -n 's/^package://p')"
INSTALLED="$(printf '%s\n' "$STATE" | sed -n '/^#--installed/,/^#--home/p' | sed -n 's/^package://p')"
HOME_NOW="$(printf '%s\n' "$STATE" | sed -n '/^#--home/,$p' | tail -n +2 | tail -n 1)"

drift=()
while read -r pkg; do
  [ -n "$pkg" ] || continue
  printf '%s\n' "$INSTALLED" | grep -qx "$pkg" || continue    # not on this TV: nothing to hold
  printf '%s\n' "$DISABLED" | grep -qx "$pkg" || drift+=("$pkg")
done < <(grep -vE '^\s*(#|$)' "$LIST" | sed -E 's/\s+#.*$//' | awk '{print $1}')

status=0
if [ ${#drift[@]} -gt 0 ]; then
  status=1
  echo "Switched back on (${#drift[@]}):"
  printf '  %s\n' "${drift[@]}"
else
  echo "All listed packages are still disabled."
fi

case "$HOME_NOW" in
  "$HOME_PKG"/*) echo "Home is $HOME_PKG." ;;
  *) status=1; echo "Home is ${HOME_NOW:-unknown}, not $HOME_PKG."
     echo "  Disable that package (setup wizard first, then the stock launcher) or run the launcher's install again." ;;
esac

if [ "$FIX" = 1 ] && [ ${#drift[@]} -gt 0 ]; then
  for pkg in "${drift[@]}"; do echo "pm disable-user --user 0 $pkg"; done | tv --batch >/dev/null
  echo "Disabled them again."
fi
exit $status
