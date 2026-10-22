# Live TV app (optional)

A separate Android TV app, `apps/live-tv`, that plays free public channels, Indian ones by default, from the
[iptv-org](https://github.com/iptv-org/iptv) playlists and, if you add it,
[Free-TV/IPTV](https://github.com/Free-TV/IPTV). It is independent of the launcher: it has its own
icon in the app grid, and the launcher's Live pill (`docs/LIVE.md`, favourite channels opened in YouTube
and other apps) is unaffected.

It is a player. It hosts nothing, downloads nothing to keep, and a channel can go offline at any time. The
repository does not vouch for the rights to any stream; the channel lists are maintained by others.

## What it does

- A category sidebar (All, News, Entertainment, Movies, Music, Sports, Kids, Infotainment, Business,
  Devotional, Other) with counts, and a grid of channel tiles.
- Keeps 1080p streams only; several streams of one channel become one tile with backups, and a stream that
  errors or does not start within 12 seconds falls back to the next.
- Full-screen player with a channel banner; Up/Down or Channel +/- changes channel.
- Opens instantly from a disk cache and refreshes in the background.

## Build and install

The app is built with [Flutter](https://docs.flutter.dev/get-started/install) (`brew install --cask flutter`),
then pushed over the same Wi-Fi ADB the rest of this repo uses:

```bash
TV_HOST=192.168.1.50 tools/build-live-tv.sh
```

Most TVs are 32-bit ARM, which is what the script builds. For a 64-bit TV run it with `TARGET=android-arm64`;
`getprop ro.product.cpu.abilist` on the TV tells which you have. Remove it with `pm uninstall com.nk.live_tv`.

## Choosing channels

The channel sources are build settings, passed to `tools/build-live-tv.sh` as environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `IPTV_PLAYLISTS` | `countries/in` | Comma-separated playlists. An entry is a path under `https://iptv-org.github.io/iptv/` without `.m3u` (`countries/in`, `languages/mar`, `categories/news`), or a full `http(s)` playlist URL. A path can carry filters after a `?`, see below. Avoid `index`: it lists every channel (11,000+) |
| `IPTV_GROUPS` | empty | For full-URL playlists only: the `group-title` values to keep, such as `India`. Those lists are grouped by country and cover the world |
| `IPTV_SKIP` | empty | Words to leave out, matched against each channel's name and link without regard to case. `pluto` drops every Pluto TV channel, for someone who already has that app |
| `IPTV_MIN_HEIGHT` | `1080` | Lowest stream height kept. `720` also keeps the (720p) streams, which roughly doubles the Indian list |

```bash
TV_HOST=192.168.1.50 IPTV_PLAYLISTS="countries/in,languages/mar,https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8" \
  IPTV_GROUPS=India tools/build-live-tv.sh
```

An iptv-org path can be narrowed with filters after a `?`, joined by `&`:

| Filter | Meaning |
|---|---|
| `country=in` | Keep only channels of that country, going by the id in the list (`NDTV24x7.in@SD` is Indian). Useful for a language many countries broadcast in |
| `cat=Movies\|Sports` | Keep only those sidebar categories (News, Entertainment, Movies, Music, Sports, Kids, Infotainment, Business, Devotional, Other) |
| `not=us` | Drop the channels of that country, for example the US ones from a worldwide English sports list |
| `min=720` | This list's lowest stream height, instead of `IPTV_MIN_HEIGHT` |

For example, Indian English, Hindi and Marathi channels in every category, plus English movie and sports channels
from anywhere, plus the movie channels in Free-TV:

```bash
IPTV_PLAYLISTS="languages/eng?country=in,languages/hin,languages/mar,languages/eng?cat=Movies|Sports&min=720,https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8" \
IPTV_GROUPS="VOD Movies (EN)" TV_HOST=192.168.1.50 tools/build-live-tv.sh
```

Behaviour to know about:

- A channel that appears in more than one list becomes one tile with the extra links as backups.
- iptv-org names carry the stream height, such as `(1080p)`. A full-URL list that does not tag it is kept, since its
  quality is unknown, not low.
- Links to YouTube pages are skipped; the player cannot open them.
- A list's own group (a country name) is replaced by the category from the iptv-org India index, so channels land
  in News, Movies and so on.

[Free-TV/IPTV](https://github.com/Free-TV/IPTV) is a hand-curated list (one link per channel, free channels
only) that covers the whole world; iptv-org is a larger directory of user-submitted links. For India the two overlap
heavily, so Free-TV adds a few channels and mostly adds backup links.

## Checking links before you build

Many links in public lists are dead, and some are slow. `tools/check-live-channels.py` checks them from a computer on the
same network as the TV, using the same `IPTV_*` settings as the build:

```bash
python3 tools/check-live-channels.py              # writes apps/live-tv/assets/blocked_streams.txt
python3 tools/check-live-channels.py --deep       # also lists streams with MP2-only or no audio (needs ffprobe)
```

For each stream it checks that the playlist answers, that its first variant lists segments, and that the newest segment
is served; a dead channel often still serves a playlist but no video. A definite failure (404 or 410, not a playlist,
no segments) goes on the block list the app skips. A network error, timeout or 403 is reported as unsure and kept,
because this computer may simply not reach a server the way the TV does; `--strict` blocks those too. With `--speed` it also measures how fast each live stream delivers video compared with how fast it
plays and labels it fast, marginal or slow in `assets/stream_notes.txt`: a stream at about 1.0x freezes whenever the
connection hiccups, however fast your internet is. The app reads that file: a channel whose best link is slow gets an amber "slow" tag next to its
name and a "slow source, may buffer" line in the banner, and a channel with several links tries the fastest first. The
committed `assets/stream_notes.txt` is empty; run the checker with `--speed` before a build to fill it in. The committed
block list is empty: run the checker before a build to fill it in, since links go stale within days.

## Testing on the TV

A link that answers on a computer can still fail on the TV. `tools/tv-smoke-test.py` plays every stream of the app on
the TV, one at a time, and says which really work there:

```bash
TV_HOST=192.168.1.50 tools/tv-smoke-test.py Movies,Sports     # or "all"; about 12 seconds per stream
```

Testing everything takes one to two hours. The checker can instead save the links a computer could not judge
(`--unsure-out FILE`), and the test can play only those plus the ones the speed check labelled slow:

```bash
python3 tools/check-live-channels.py --speed --unsure-out unsure.txt
TV_HOST=192.168.1.50 tools/tv-smoke-test.py --unsure unsure.txt --slow apps/live-tv/assets/stream_notes.txt
```

It pushes a flag file that puts the app in test mode, starts it, and reads one logged line per stream: whether it
starts, how many of 8 seconds actually played, stalls, and the audio tracks. The result is OK, SLOW (starts, but stalls
or falls behind), STALL (starts, hardly plays) or FAIL, and the table is written as CSV. The TV shows the test while it
runs; closing the app stops it early. The app deletes the flag file as soon as it reads it, so it cannot get stuck in
test mode.

## Network use

On start it downloads the playlists you chose, plus the iptv-org India index for logos and categories, and then the
streams you open. Whoever maintains those lists decides which channels appear. It asks only for the INTERNET
permission and has no analytics or ads. iptv-org and Free-TV both say they hold no video and only link to publicly
available streams; neither guarantees the rights to a stream, and iptv-org has a copyright-claim form for owners.

## Audio

The app plays through [fvp](https://pub.dev/packages/fvp) (libmdk, built on FFmpeg) instead of Android's own decoders,
because many TVs cannot decode MPEG layer 2 (MP2) audio, and some cannot decode 5.1 Dolby Digital (AC-3). With Android's
decoders a stream whose audio is MP2 only, or AC-3 plus MP2, plays video with no sound; about one Indian channel in
fourteen is like that. fvp decodes them in software, at a cost of about 10 MB of libraries and about 25 MB of memory while
playing. Remove the `fvp.registerWith()` line in `lib/main.dart` to go back to the TV's decoders.

While a channel plays, Left and Right switch between its audio tracks when it has more than one (several languages, or
5.1 plus stereo); the banner shows which one is on ("Audio 2 of 5 · stereo · eng"). The player logs the tracks it sees
to logcat as `AUDIO <channel>: tracks=[...]`.

Some streams carry a surround track and a stereo track that are not the same mix. On Movies Now HD, for example, the
AC-3 5.1 track has silent centre and surround channels and quiet front channels, while the MP2 stereo track is about
6 dB louder; with the 5.1 track playing, music was heard but speech was not. When a stream also carries a stereo track,
the player chooses it, and Left and Right switch back. To see what a stream carries:
`ffprobe -show_entries stream=codec_type,codec_name,channels <stream url>`.

## Memory

A Flutter app runs its own engine, so it uses memory while it is open and, like any app, stays cached until
the TV needs the space. On a small TV, close it after use (or force-stop `com.nk.live_tv`) rather than leaving
it in the background.

## Credits

`apps/live-tv` is a fork, reworked for TV remotes and low-memory TVs, of the `tv/master` branch of
[kananinirav/Indian-IPTV-App](https://github.com/kananinirav/Indian-IPTV-App) (MIT, see
`apps/live-tv/LICENSE`), taken from [kulwantnagi/android-tv-makeover](https://github.com/kulwantnagi/android-tv-makeover)
(MIT). `apps/make_live_tv_icons.py` draws its icon and TV banner.
