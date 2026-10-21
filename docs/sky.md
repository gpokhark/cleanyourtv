# Sky

An optional backdrop that follows the clock: night blues until dawn, a soft blue day, then a warm
horizon that peaks just before eight in the evening. It is off in the built-in configuration, so a
launcher built from this repository behaves exactly like before until a theme turns it on.

```json
{ "sky_intensity": 60 }
```

![The backdrop at dusk, a few minutes before eight](sky-sunset.jpg)

Push that as a theme's `config.json` and restart Home:

```bash
TV_HOST=192.168.1.50 tools/theme.sh themes/your-theme
```

## What it draws

A full-screen gradient in three bands — top, middle, bottom — with a soft warm glow near the horizon
whose horizontal position follows the sun: left at sunrise, right at sunset. `sky_intensity` is its
opacity, 0 to 100; the colours themselves are interpolated between a handful of anchors through the
day, which is what makes it drift instead of switching at fixed hours.

It sits behind everything, over the wallpaper when there is one, and the palette was chosen dark on
purpose: the white clock, the greeting, the hero name and the dock band all keep their contrast at
the default 60.

## Sunrise and sunset

By default the anchors are fixed: sunrise at 07:30 and sunset at 19:45. Many TVs keep location switched
off and hold no fix, so a theme can name the place instead (one decimal is plenty):

```json
{ "sky_intensity": 60, "sky_latitude": 51.5, "sky_longitude": -0.1 }
```

Both keys are needed; a value out of range is ignored. When they are set they are used first and the
TV's own location is never read. Otherwise, when Home has location
permission (the install script grants it) and the system already holds a last known fix, `SkyView`
reads it, computes that day's local sunrise and sunset with the NOAA solar formulae in `Sky.sunTimes`,
and stretches the day so the sunrise and sunset anchors land on them: the warm horizon follows the
real sun through the seasons. Nothing is requested from the location service; no listener, no
updates, just the last fix the system already has. With no permission, no fix, or a day with no
sunrise or sunset (polar regions), the fixed anchors apply exactly as before.

## Cost

`Sky` is pure maths — no Android types, no clock of its own; the caller passes the minutes since
midnight, which is why the palette is unit-tested on the JVM. `SkyView` renders one 480×270 bitmap
and redraws it once a minute, from the same minute tick that updates the clock. Nothing is animated
and no location updates are requested: the backdrop is the only change, and a TV with 1 GB of RAM has nothing to
keep up with.

Turning the key off restores the plain background (or the theme's `wallpaper`/`background_color`)
on the next Home start.
