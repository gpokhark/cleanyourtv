#!/usr/bin/env python3
"""Play every stream of the Live TV app on the TV, one by one, and report which ones really work there.

Pushes a flag file that switches the app (apps/live-tv, installed on the TV) into test mode, starts it, and reads the
one line per stream it logs over Wi-Fi ADB: does it start, how many of 8 seconds actually played, did it stall, how many
audio tracks it has. A link that answers on a computer can still fail on the TV (or the other way round), so this is
the real check. A run takes about 12 seconds per stream.

Usage: TV_HOST=192.168.1.50 tools/tv-smoke-test.py [categories] [--out FILE] [--max-minutes N]
  categories   "all" (default) or a comma-separated list such as "Movies,Sports"
  --out FILE   write the results as CSV (default my-tv/live-tv-smoke.csv if my-tv exists, else live-tv-smoke.csv)
The TV screen shows the test while it runs; close the app on the TV to stop early. Results so far are still written.
"""
import argparse
import csv
import os
import subprocess
import sys
import time

PKG = "com.nk.live_tv"
FILES = f"/sdcard/Android/data/{PKG}/files"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PY = os.path.join(ROOT, "tools", "venv", "bin", "python")
if not os.path.exists(PY):
    PY = os.path.join(ROOT, "tools", "venv", "Scripts", "python.exe")


def tv(command, timeout=100):
    r = subprocess.run([PY, "-I", os.path.join(ROOT, "tools", "tv.py"), command], capture_output=True, text=True,
                       timeout=timeout, env=os.environ)
    return r.stdout


def read_lines():
    out = tv("logcat -d -s flutter:I | grep 'SMOKE|'")
    rows = {}
    done = None
    for line in out.splitlines():
        i = line.find("SMOKE|")
        if i < 0:
            continue
        parts = line[i:].strip().split("|")
        if len(parts) >= 2 and parts[1] == "done":
            done = parts
        elif len(parts) >= 12:
            rows[(parts[1], parts[4])] = parts[1:]
    return rows, done


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("categories", nargs="?", default="all")
    ap.add_argument("--out")
    ap.add_argument("--max-minutes", type=int, default=150)
    a = ap.parse_args()
    if not os.environ.get("TV_HOST"):
        sys.exit("set TV_HOST to the TV's IP address")
    out = a.out or ("my-tv/live-tv-smoke.csv" if os.path.isdir(os.path.join(ROOT, "my-tv")) else "live-tv-smoke.csv")

    tv(f"mkdir -p {FILES}; echo '{a.categories}' > {FILES}/smoke; logcat -c; am force-stop {PKG}; "
       f"am start -n {PKG}/.MainActivity")
    print(f"test started for: {a.categories}", file=sys.stderr)

    rows, done, last_change, seen = {}, None, time.time(), 0
    deadline = time.time() + a.max_minutes * 60
    while time.time() < deadline and not done:
        time.sleep(30)
        try:
            rows, done = read_lines()
        except Exception as e:
            print(f"  (could not read the log: {e})", file=sys.stderr)
            continue
        if len(rows) != seen:
            seen, last_change = len(rows), time.time()
            last = sorted(rows.values(), key=lambda r: int(r[0]))[-1]
            print(f"  {last[0]} of {last[1]}: {last[2]} {last[4]}", file=sys.stderr)
        elif time.time() - last_change > 240:
            print("no progress for 4 minutes: the app may have stopped", file=sys.stderr)
            break

    ordered = sorted(rows.values(), key=lambda r: int(r[0]))
    with open(out, "w", newline="", encoding="utf8") as f:
        w = csv.writer(f)
        w.writerow(["n", "of", "channel", "stream", "result", "init_ms", "stalls", "buffering_ms", "played_s", "audio", "url"])
        for r in ordered:
            w.writerow([r[0], r[1], r[2], r[3], r[4]] + [x.split("=", 1)[-1] for x in r[5:10]] + [r[10] if len(r) > 10 else ""])
    count = {}
    for r in ordered:
        count[r[4]] = count.get(r[4], 0) + 1
    print(f"{len(ordered)} streams tested: " + ", ".join(f"{k} {v}" for k, v in sorted(count.items())) + f"; written to {out}")
    for r in ordered:
        if r[4] != "OK":
            print(f"  {r[4]:5} {r[2]} (stream {r[3]})  {r[6]} {r[8]}")


if __name__ == "__main__":
    main()
