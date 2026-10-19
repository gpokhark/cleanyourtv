#!/usr/bin/env python3
"""Write a show's season and episode list from Pluto TV's public catalogue, for the launcher's Shows screen.

    tools/pluto-catalog.py one-piece-eng --id one-piece --out my-tv/shows
    tools/pluto-catalog.py --search "one piece"      # list matching series slugs

Each episode gets the link that opens it in the Pluto TV app. The catalogue is Pluto's own unofficial web API, so
this can stop working after a change on their side. Push the result with tools/push-shows.sh.
"""
import argparse
import json
import os
import sys
import urllib.parse
import urllib.request
import uuid

UA = {"User-Agent": "Mozilla/5.0"}


def get(url, token=None):
    headers = dict(UA)
    if token:
        headers["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
        return json.loads(r.read().decode())


def token():
    q = urllib.parse.urlencode({
        "appName": "web", "appVersion": "9.12.0", "deviceVersion": "120.0.0", "deviceModel": "web",
        "deviceMake": "chrome", "deviceType": "web", "clientID": str(uuid.uuid4()), "clientModelNumber": "1.0.0",
        "serverSideAds": "false", "drmCapabilities": "widevine:L3", "marketingRegion": "US", "country": "US"})
    return get("https://boot.pluto.tv/v4/start?" + q)["sessionToken"]


def search(tok, text):
    url = "https://service-media-search.clusters.pluto.tv/v1/search?" + urllib.parse.urlencode({"q": text, "limit": 40})
    return [d for d in get(url, tok)["data"] if d.get("type") == "series"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("slug", nargs="?", help="Pluto series slug, e.g. one-piece-eng")
    ap.add_argument("--search", help="list series whose name matches, instead of writing a catalogue")
    ap.add_argument("--id", help="short id for the show (file name); default: the slug")
    ap.add_argument("--title", help="name shown on the TV; default: Pluto's name for the series")
    ap.add_argument("--order", type=int, default=0, help="position in the Shows list (0 = first); ties sort by title")
    ap.add_argument("--out", default="shows", help="folder to write <id>.json into")
    a = ap.parse_args()
    tok = token()
    if a.search:
        for d in search(tok, a.search):
            print(f'{d["slug"]:45} {d["name"]}')
        return
    if not a.slug:
        sys.exit(__doc__)
    hits = [d for d in search(tok, a.slug.replace("-", " ")) if d["slug"] == a.slug]
    if not hits:
        sys.exit(f"no Pluto series with slug {a.slug}; try --search")
    series = get(f'https://service-vod.clusters.pluto.tv/v4/vod/series/{hits[0]["id"]}/seasons?offset=1000&limit=1000', tok)
    seasons = []
    for s in series["seasons"]:
        eps = [{"n": e["number"], "name": e["name"],
                "url": f'https://pluto.tv/on-demand/series/{a.slug}/season/{e["season"]}/episode/{e["slug"]}'}
               for e in s["episodes"]]
        if eps:
            seasons.append({"n": s["episodes"][0]["season"], "episodes": eps})
    seasons.sort(key=lambda s: s["n"])
    show = {"id": a.id or a.slug, "title": a.title or series["name"], "package": "tv.pluto.android", "order": a.order,
            "seasons": seasons}
    os.makedirs(a.out, exist_ok=True)
    path = os.path.join(a.out, show["id"] + ".json")
    with open(path, "w") as f:
        json.dump(show, f, separators=(",", ":"), ensure_ascii=False)
    print(f'{show["title"]}: {len(seasons)} seasons, {sum(len(s["episodes"]) for s in seasons)} episodes -> {path}')


if __name__ == "__main__":
    main()
