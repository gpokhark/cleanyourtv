#!/usr/bin/env python3
"""How many of your Live TV channels a programme guide (XMLTV) covers, so guides can be compared before one is used.

Reads the same IPTV_PLAYLISTS, IPTV_GROUPS, IPTV_SKIP and IPTV_MIN_HEIGHT settings as the app and
tools/check-live-channels.py, picks the channels the app would list, then matches each to a guide channel the way the
app does: by its id first, then by its name. Prints each guide alone and all of them together (the first guide that has
a channel wins, as in the app), and a few channels nobody covers.

Usage:
  tools/check-epg-coverage.py --guide https://host/epg-in.xml.gz --guide https://other/guide.xml
  EPG_URLS=a.xml.gz,b.xml.gz IPTV_PLAYLISTS="languages/hin,languages/mar" tools/check-epg-coverage.py

A guide is read once and only its channels and programme counts are kept. Nothing is sent anywhere but the requests for
the playlists and the guides. Needs Python 3.
"""
import argparse
import collections
import gzip
import importlib.util
import io
import os
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))


def load_checker():
    spec = importlib.util.spec_from_file_location("check_live_channels", os.path.join(HERE, "check-live-channels.py"))
    mod = importlib.util.module_from_spec(spec)
    argv, sys.argv = sys.argv, ["check-live-channels.py"]
    try:
        spec.loader.exec_module(mod)
    finally:
        sys.argv = argv
    return mod


def id_key(tvg_id):
    """"AajTak.in@HD", "Aaj.Tak.in", "&TV.HD.in" -> letters and digits only, without the feed and an HD part (as the app)."""
    base = tvg_id.lower().split("@")[0].replace(".hd.", ".")
    return "i" + re.sub(r"[^a-z0-9]", "", base)


def name_key(name):
    """"Aaj Tak HD (1080p) [Geo-blocked]" -> "aajtak" (as the app)."""
    name = re.sub(r"\(.*?\)|\[.*?\]", "", name.lower())
    return "n" + re.sub(r"[^a-z0-9]", "", re.sub(r"\bhd\b", "", name))


def channel_key(name, tvg_id):
    return id_key(tvg_id) if tvg_id else name_key(name)


def read_guide(url):
    """The guide's channels as (id, [display names]) and the set of channel ids that have programmes."""
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=120) as r:
        data = r.read()
    if url.lower().split("?")[0].endswith(".gz") or data[:2] == b"\x1f\x8b":
        data = gzip.decompress(data)
    channels, with_programmes = [], set()
    for _, el in ET.iterparse(io.BytesIO(data), events=("end",)):
        if el.tag == "channel":
            channels.append((el.get("id") or "", [(d.text or "") for d in el.findall("display-name")]))
        elif el.tag == "programme":
            with_programmes.add(el.get("channel"))
            el.clear()
    return channels, with_programmes


def covered(mine, guide):
    """The channel keys of [mine] ({key: (name, tvg_id)}) that [guide] covers."""
    channels, with_programmes = guide
    wanted = {}
    for key, (name, tvg_id) in mine.items():
        if tvg_id:
            wanted.setdefault(id_key(tvg_id), key)
        wanted.setdefault(name_key(name), key)
    hit = set()
    for gid, names in channels:
        if gid not in with_programmes:
            continue
        key = wanted.get(id_key(gid)) or next((wanted[name_key(n)] for n in names if name_key(n) in wanted), None)
        if key:
            hit.add(key)
    return hit


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--guide", action="append", default=[], help="an XMLTV guide URL (.gz is unpacked); repeat for several")
    ap.add_argument("--playlists", default=os.environ.get("IPTV_PLAYLISTS", "countries/in"))
    ap.add_argument("--groups", default=os.environ.get("IPTV_GROUPS", ""))
    ap.add_argument("--min-height", type=int, default=int(os.environ.get("IPTV_MIN_HEIGHT", "1080")))
    ap.add_argument("--skip", default=os.environ.get("IPTV_SKIP", ""))
    a = ap.parse_args()
    guides = a.guide or [u.strip() for u in os.environ.get("EPG_URLS", "").split(",") if u.strip()]
    if not guides:
        sys.exit("name at least one guide: --guide URL, or set EPG_URLS")

    chk = load_checker()
    groups = {g.strip().lower() for g in a.groups.split(",") if g.strip()}
    skip = [t.strip().lower() for t in a.skip.split(",") if t.strip()]
    mine = {}
    for spec in [s.strip() for s in a.playlists.split(",") if s.strip()]:
        for info, _link in chk.selected(spec, groups, a.min_height, skip, with_info=True):
            name = re.sub(r"\s*\(.*?\)|\s*\[.*?\]", "", chk.name_of(info)).strip()
            tvg_id = chk.attr(info, "tvg-id")
            mine.setdefault(channel_key(name, tvg_id), (name, tvg_id))
    indian = {k for k, (_n, i) in mine.items() if i.split("@")[0].lower().endswith(".in")}
    print(f"{len(mine)} channels the app would list ({len(indian)} Indian, by id ending .in)")

    seen = set()
    for url in guides:
        try:
            guide = read_guide(url)
        except Exception as e:
            print(f"\n{url}\n  could not be read: {e}")
            continue
        hit = covered(mine, guide)
        extra = hit - seen
        seen |= hit
        print(f"\n{url}\n  {len(guide[0])} channels, {len(guide[1])} with programmes")
        print(f"  covers {len(hit)} of ours ({len(hit & indian)} Indian); {len(extra)} of them no earlier guide had")
    print(f"\nall guides together, first one wins: {len(seen)} of {len(mine)} channels ({len(seen & indian)} of {len(indian)} Indian)")
    missing = [mine[k][0] for k in mine if k not in seen]
    by_cat = collections.Counter("Indian" if k in indian else "other" for k in mine if k not in seen)
    print(f"not covered: {len(missing)} ({by_cat['Indian']} Indian, {by_cat['other']} other), for example: " + ", ".join(sorted(missing)[:12]))


if __name__ == "__main__":
    main()
