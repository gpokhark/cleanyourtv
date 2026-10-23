# Memory: close apps that are using up the TV's RAM

A TV with 2 GB of RAM keeps every app you opened in memory, until it runs out, fills its swap and slows
everything down (keyboards and menus first). The **Memory** pill lists the apps you used recently and closes
the ones you pick, or all of them. It is off by default (`show_memory`).

- The header has two lines: free RAM and free swap, each as MB free of the total and the percentage free.
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

## When "Close all" does nothing

The pill asks Android to close each app (`killBackgroundProcesses`). Some TV firmware ignores that request from an
ordinary app, even though the same close works from `adb shell am kill <package>`. The pill and auto-trim then change
nothing: the apps stay in memory and swap stays full.

- The pill measures how much memory came back. If it was 20 MB or less it says "Nothing was freed" and does not mark
  the apps as closed. Auto-trim logs it too: `adb logcat -s TvHome.Trim:I` prints "asked to close N apps ... free memory
  X -> Y MB". If X and Y are close, the TV is ignoring the request.
- To check by hand: note a background app's process id (`pidof <package>`), press Close all, and look again. A real close
  shows `ActivityManager: Killing ... kill background` in `adb logcat`.
- What works on such a TV is closing through adb: from a computer (`am kill-all`, `am force-stop <package>`), or from the
  launcher itself with `memory_adb`, below. Lowering Android's "Background process limit" in Developer options also
  helps if the TV offers it.

## Closing apps through adb (memory_adb)

`"memory_adb": true` in a theme's `config.json` makes **Close all**, **Close selected** and auto-trim stop apps by asking
the TV's own adb service to run `am force-stop <package>`, the same close that works from a computer. It is off by default.

Setup: USB debugging must be on (Settings, Developer options). The first time you press Close all, the TV shows
"Allow USB debugging?" for the launcher's key; choose **Always allow**. After that Close all is silent. If the TV forgets
the key, the pill says so and asks again.

What it can and cannot do:

- It can run one command, `am force-stop`, for package names it has checked (letters, digits, underscores and dots,
  nothing else). There is no way to hand it any other command.
- It refuses system packages (`android`, the system UI, Settings, Google Play services and framework providers), this
  launcher, and everything in `memory_protect`. At most 40 apps per run.
- It connects to the TV's own address (127.0.0.1) only, keeps no connection open between runs, does not do wireless
  debugging (TLS), and rejects oversized messages.
- Auto-trim uses it only after the TV has accepted the key, so a run nobody started never shows a prompt.
- It is off in safe mode (`docs/WATCHDOG.md`).

The key is made on first use and kept in the launcher's private folder, which is excluded from backups; it is never
logged. To take the permission back, use "Revoke USB debugging authorizations" in Developer options, or turn
`memory_adb` off.

If "Allow USB debugging?" never appears: the pill's own message ("Accept “Allow USB debugging?” on the TV…") shows, then
gives up after 30 seconds, and `adb logcat -d | grep adbd_auth` shows `sending prompt with id N` followed by
`prompt currently pending, skipping`. The TV's adb service is stuck on an earlier prompt that nobody answered (any device
on your network that once connected with an unknown key can leave one) and will not show another. Turning USB debugging
off and on does not help on some firmware: the service keeps running (check that its process id in the log is unchanged).
Restarting the TV clears it, and the dialog then appears on the next Close all. The same stuck state stops a computer
from pairing a new key, so it is not specific to the launcher.

The trade-off: this needs USB debugging to stay on, which is also what lets a computer on your network control the TV
(`docs/REMOTE.md`), so keep port 5555 off the internet. Anything that could run code inside the launcher could use the
key, which is why the commands are fixed and limited as above. `adb logcat -s TvHome.Adb` shows each run.
