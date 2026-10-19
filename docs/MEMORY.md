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
