#!/usr/bin/env python3
"""Check which channel links of the Live TV app (apps/live-tv) answer, and write the dead ones to a block list.

Reads the same IPTV_PLAYLISTS, IPTV_GROUPS, IPTV_SKIP and IPTV_MIN_HEIGHT settings as tools/build-live-tv.sh (environment or
flags), fetches those playlists, and for each stream checks that:
  1. the playlist URL answers 200 and looks like an HLS playlist (#EXTM3U),
  2. its first variant playlist answers and lists segments,
  3. the newest segment answers (a dead channel often still serves a playlist, but no video).
Only a definite failure blocks a stream: a 404 or 410, an answer that is not a playlist, or a playlist with no video.
A network error (timeout, connection refused, DNS) or another HTTP error (403, 4xx) may just mean this computer cannot
reach the server the way the TV can, so those streams are reported as "unsure" and kept; --strict blocks them too.
Blocked streams are written to the block list, one URL per line with the reason as a comment. The app skips every
URL in apps/live-tv/assets/blocked_streams.txt. Run it from a computer on the same network as the TV: a link that
answers here should answer there, and a geo-blocked one will not.

--speed also measures how fast each live stream delivers video compared with how fast it plays, from this computer, and
writes a note per stream ("fast", "marginal" or "slow") to apps/live-tv/assets/stream_notes.txt, which the app shows. A
stream that delivers at about real-time speed or below (ratio 1.0x) freezes whenever the connection hiccups, however
fast your own internet is. A few streams are fetched at a time so the test itself does not slow them; run it twice at
different times of day, since servers vary.

--deep also runs ffprobe (needs ffmpeg) on the live ones and lists streams whose audio is MP2-only or missing, which
some TVs cannot decode (the app plays them anyway when it uses the fvp player).

Usage: tools/check-live-channels.py [--out FILE] [--deep] [--playlists SPEC] [--groups LIST] [--min-height N]
Needs only Python 3.
"""
import argparse
import concurrent.futures as cf
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

IPTV_BASE = "https://iptv-org.github.io/iptv/"
UA = {"User-Agent": "Mozilla/5.0"}
TIMEOUT = 10


def get(url, limit=None):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        data = r.read(limit) if limit else r.read()
        return r.status, data.decode("utf8", "ignore")


def parse_m3u(text):
    out, info = [], None
    for raw in text.splitlines():
        line = raw.strip()
        if line.startswith("#EXTINF"):
            info = line
        elif line and not line.startswith("#") and info:
            out.append((info, line))
            info = None
    return out


def attr(info, key):
    m = re.search(key + r'="([^"]*)"', info)
    return m.group(1) if m else ""


def name_of(info):
    return info.rsplit(",", 1)[-1].strip()


def id_country(tvg_id):
    base = tvg_id.split("@")[0]
    return base.rsplit(".", 1)[-1].lower() if "." in base else ""


def selected(spec, groups, min_height, skip):
    """Yield (name, url) of the channels the app would list for one IPTV_PLAYLISTS entry (cat= approximated by group)."""
    full = spec.startswith("http")
    params = {}
    if full:
        url = spec
    else:
        path, _, query = spec.partition("?")
        url = f"{IPTV_BASE}{path}.m3u"
        params = dict(urllib.parse.parse_qsl(query))
    cats = [c.strip().lower() for c in params.get("cat", "").split("|") if c.strip()]
    country, not_country = params.get("country", "").lower(), params.get("not", "").lower()
    lowest = int(params["min"]) if params.get("min", "").isdigit() else min_height
    try:
        entries = parse_m3u(get(url)[1])
    except Exception as e:
        print(f"  could not fetch {spec}: {e}", file=sys.stderr)
        return
    for info, link in entries:
        if "youtube.com" in link or "youtu.be" in link:
            continue
        if any(t in link.lower() or t in name_of(info).lower() for t in skip):
            continue
        if full and groups and attr(info, "group-title").lower() not in groups:
            continue
        cid = id_country(attr(info, "tvg-id"))
        if country and cid != country:
            continue
        if not_country and cid == not_country:
            continue
        tag = re.search(r"\((\d+)[pi]\)", name_of(info))
        if tag is not None:
            if int(tag.group(1)) < lowest:
                continue
        elif not full:
            continue
        if cats and attr(info, "group-title").split(";")[0].strip().lower() not in cats:
            continue
        yield name_of(info), link


def probe(item):
    """Return (name, url, None) when the stream answers, else (name, url, (reason, definite))."""
    name, url = item
    try:
        status, body = get(url, 20000)
        if status != 200 or "#EXTM3U" not in body[:200]:
            return name, url, (f"not a playlist (status {status})", True)
        base = url
        variant = None
        for line in body.splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                variant = urllib.parse.urljoin(base, line)
                break
        if variant is None:
            return name, url, ("empty playlist", True)
        if "#EXTINF" not in body:
            status, body = get(variant, 40000)
            if status != 200 or "#EXTINF" not in body:
                return name, url, ("variant playlist has no segments", True)
            base = variant
        segments = [urllib.parse.urljoin(base, l.strip()) for l in body.splitlines() if l.strip() and not l.startswith("#")]
        if not segments:
            return name, url, ("no segments", True)
        req = urllib.request.Request(segments[-1], headers={**UA, "Range": "bytes=0-2047"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            if not r.read(1024):
                return name, url, ("segment is empty", True)
        return name, url, None
    except urllib.error.HTTPError as e:
        return name, url, (f"HTTP {e.code}", e.code in (404, 410))
    except Exception as e:
        return name, url, (type(e).__name__, False)


def media_segments(url):
    """The (segment url, seconds) pairs of a stream's media playlist, newest last."""
    status, body = get(url, 60000)
    base = url
    if "#EXTINF" not in body:
        variant = next(urllib.parse.urljoin(base, l.strip()) for l in body.splitlines() if l.strip() and not l.startswith("#"))
        base = variant
        status, body = get(variant, 60000)
    lines = body.splitlines()
    out = []
    for i, l in enumerate(lines):
        m = re.match(r"#EXTINF:([\d.]+)", l)
        if m and i + 1 < len(lines):
            out.append((urllib.parse.urljoin(base, lines[i + 1].strip()), float(m.group(1))))
    return out


def speed_of(item):
    """Return (name, url, ratio or None): seconds of video delivered per second spent downloading it (newest 4 segments)."""
    name, url = item
    try:
        segs = media_segments(url)[-4:]
        spent = played = 0.0
        for seg, dur in segs:
            t = time.time()
            req = urllib.request.Request(seg, headers=UA)
            with urllib.request.urlopen(req, timeout=30) as r:
                r.read()
            spent += time.time() - t
            played += dur
        return name, url, (played / spent if spent > 0 else None)
    except Exception:
        return name, url, None


def audio_of(url):
    try:
        r = subprocess.run(
            ["ffprobe", "-v", "quiet", "-user_agent", "Mozilla/5.0", "-rw_timeout", "12000000", "-analyzeduration", "5000000",
             "-probesize", "5000000", "-show_entries", "stream=codec_type,codec_name", "-of", "compact", url],
            capture_output=True, text=True, timeout=30)
        return sorted({re.search(r"codec_name=(\w+)", l).group(1) for l in r.stdout.splitlines()
                       if "codec_type=audio" in l and re.search(r"codec_name=(\w+)", l)})
    except Exception:
        return None


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--playlists", default=os.environ.get("IPTV_PLAYLISTS", "countries/in"))
    ap.add_argument("--groups", default=os.environ.get("IPTV_GROUPS", ""))
    ap.add_argument("--min-height", type=int, default=int(os.environ.get("IPTV_MIN_HEIGHT", "1080")))
    ap.add_argument("--skip", default=os.environ.get("IPTV_SKIP", ""), help="words to leave out, as IPTV_SKIP")
    ap.add_argument("--out", default="apps/live-tv/assets/blocked_streams.txt")
    ap.add_argument("--strict", action="store_true", help="also block streams that fail only with a network error or 403")
    ap.add_argument("--speed", action="store_true", help="also label each live stream fast, marginal or slow")
    ap.add_argument("--unsure-out", help="also write the links that were unsure (not blocked) to this file, for tools/tv-smoke-test.py")
    ap.add_argument("--notes", default="apps/live-tv/assets/stream_notes.txt")
    ap.add_argument("--deep", action="store_true", help="also report streams with MP2-only or no audio (needs ffprobe)")
    ap.add_argument("--workers", type=int, default=16)
    a = ap.parse_args()
    if a.deep and not shutil.which("ffprobe"):
        sys.exit("--deep needs ffprobe (brew install ffmpeg)")

    groups = [g.strip().lower() for g in a.groups.split(",") if g.strip()]
    skip = [t.strip().lower() for t in a.skip.split(",") if t.strip()]
    items, seen = [], set()
    for spec in [s.strip() for s in a.playlists.split(",") if s.strip()]:
        for name, url in selected(spec, groups, a.min_height, skip):
            if url not in seen:
                seen.add(url)
                items.append((name, url))
    print(f"checking {len(items)} streams ...", file=sys.stderr)

    with cf.ThreadPoolExecutor(a.workers) as ex:
        results = list(ex.map(probe, items))
    failed = [(n, u, r) for n, u, r in results if r]
    dead = [(n, u, r[0]) for n, u, r in failed if r[1] or a.strict]
    unsure = [(n, u, r[0]) for n, u, r in failed if not (r[1] or a.strict)]
    alive = [(n, u) for n, u, r in results if not r] + [(n, u) for n, u, _ in unsure]

    lines = ["# Streams the Live TV app skips. Written by tools/check-live-channels.py; do not edit by hand."]
    lines += [f"{u}  # {n}: {r}" for n, u, r in sorted(dead, key=lambda x: x[0].lower())]
    os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
    with open(a.out, "w", encoding="utf8") as f:
        f.write("\n".join(lines) + "\n")
    if a.unsure_out:
        with open(a.unsure_out, "w", encoding="utf8") as f:
            f.write("# Links this computer could not judge. tools/tv-smoke-test.py --unsure tests them on the TV.\n")
            f.writelines(f"{u}  # {n}: {r}\n" for n, u, r in sorted(unsure, key=lambda x: x[0].lower()))
    print(f"{len(results) - len(failed)} answer, {len(dead)} are dead (blocked), {len(unsure)} unsure (kept: network error or "
          f"403, may work from the TV); block list written to {a.out}")

    if a.speed:
        with cf.ThreadPoolExecutor(4) as ex:
            speeds = list(ex.map(speed_of, alive))
        notes = ["# How fast each stream delivers video (1.0x = exactly as fast as it plays). Written by tools/check-live-channels.py --speed."]
        counts = {"fast": 0, "marginal": 0, "slow": 0, "unmeasured": 0}
        for n, u, ratio in sorted(speeds, key=lambda x: (x[2] is None, x[2] or 0)):
            if ratio is None:
                label = "unmeasured"
            else:
                label = "slow" if ratio < 1.5 else "marginal" if ratio < 3 else "fast"
            counts[label] += 1
            notes.append(f"{u}  # {n}: {label} {'' if ratio is None else '%.1fx' % ratio}".rstrip())
        with open(a.notes, "w", encoding="utf8") as f:
            f.write("\n".join(notes) + "\n")
        print("speed: " + ", ".join(f"{k} {v}" for k, v in counts.items()) + f"; notes written to {a.notes}")
        for n, u, ratio in sorted(speeds, key=lambda x: (x[2] is None, x[2] or 0)):
            if ratio is not None and ratio < 1.5:
                print(f"  slow {ratio:.1f}x  {n}")

    if a.deep:
        with cf.ThreadPoolExecutor(a.workers) as ex:
            audio = list(ex.map(lambda x: (x[0], audio_of(x[1])), alive))
        odd = [(n, c) for n, c in audio if c is not None and (not c or set(c) <= {"mp2"})]
        print("MP2-only or no audio (a TV without an MP2 decoder plays these silent unless the app uses fvp):")
        for n, c in sorted(odd):
            print(f"  {n}: {c or 'no audio stream'}")


if __name__ == "__main__":
    main()
