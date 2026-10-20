# Memory: close apps that are using up the TV's RAM

A TV with 2 GB of RAM keeps every app you opened in memory, until it runs out, fills its swap and slows
everything down (keyboards and menus first). The **Memory** pill lists the apps you used recently and closes
the ones you pick, or all of them. It is off by default (`show_memory`).

- The header shows free RAM and how full the swap is.
- Press OK on an app to tick it, then **Close selected**; or press **Close all**, which closes every app you can
  open from the home screen, not only the ones listed. A toast says how much memory that freed, measured from the
  TV's free RAM before and after. Closed apps start fresh the next time you open them.
- Android does not tell an app which other apps are running, or how big they are. So the list is the apps used in
  the last day, which are the ones most likely still in memory, and it shows when each was last used. An app
  that was already closed frees nothing.
- Only apps with a launcher entry are listed, so system services are left alone. `memory_protect` (package names,
  in `config.xml` or a theme's `config.json`) hides more, for example your Live TV app.
- Closing uses `killBackgroundProcesses`, which stops an app's background processes as the system does under memory
  pressure. An app playing in the foreground is left running.

## Setup

Turn it on with `show_memory`, then grant the usage-statistics permission once over adb (an app cannot read it
any other way):

```
adb shell appops set com.gathin.tvhome GET_USAGE_STATS allow
```

(or `tools/tv.py "appops set com.gathin.tvhome GET_USAGE_STATS allow"`). Without it the screen says so.

## Close idle apps by itself (auto_trim)

`auto_trim` runs the same close as the pill's "close all" when you press Home, so you do not have to open the pill.
It is off by default. It needs the same usage-statistics permission as the pill.

```json
"auto_trim": true,
"auto_trim_below_mb": 500
```

- It runs only when free memory is below `auto_trim_below_mb` (default 500 MB), and at most once every 10 minutes.
- It keeps the app you used just before Home, anything with a playing media session, the apps in `memory_protect`, and
  the launcher. Seeing what is playing needs the Shows notification listener to be on (`docs/SHOWS.md`); without it,
  playing apps are protected only by Android itself, which does not stop an app that is playing in the foreground.
- It starts two seconds after Home so the screen is not held up. `adb logcat -s TvHome.Trim` shows each run.

## Recently used apps on Home (recent_apps)

`"recent_apps": 5` puts the five apps you used most recently, apart from the dock, right after the dock and the Shows
tile, newest first. It is a sort order in the existing grid, so it adds no screen and nothing runs in the background.
`0` (the default) turns it off. It uses the last seven days of usage statistics.
