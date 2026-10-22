# Live: favourite free live-TV channels

The **Live** pill opens a short list of your favourite free live channels. OK on one opens it. It is off by
default (`show_live`).

Free live TV (Pluto TV, Tubi, Plex, Xumo Play, The Roku Channel, TCL TV+...) lives inside separate apps, and none of
them lets another app browse its channels. So the list is yours: each favourite is a shortcut in `live_channels`,
in `config.xml` or a theme's `config.json`.

```json
"show_live": true,
"live_channels": [
  "NBC News NOW|tv.pluto.android|https://pluto.tv/us/live-tv/nbc-news-now",
  "Plex Live TV|com.plexapp.android|",
  "The Roku Channel|com.roku.web.trc|"
]
```

Each entry is `Name|android.package|link`:

- **With a link**, the app is asked to open that channel. Of the free-TV apps tried (Pluto TV, Tubi, Plex, Xumo
  Play, The Roku Channel, TCL TV+), only Pluto TV took a link that opens a channel: `https://pluto.tv/us/live-tv/<channel>`.
  Tubi ignored `tubitv://live` (it opened its home, or a random title); the others publish no usable channel link.
  Links are the app's own (unofficial) and can stop working after an update.
- **Without a link**, or if the app does not understand it, the app just opens and you pick the channel there.
- An app that is not installed opens its Play Store page.

Channel names for Pluto are in its public list; find the link for a channel by name with a small script of your
own, or copy `https://pluto.tv/us/live-tv/<name>` from pluto.tv in a browser. Edit the list, push it with
`tools/theme.sh <theme folder>`, and it shows up right away; no rebuild is needed.
