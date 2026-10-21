# Theme styles

The launcher has one look by default and one alternative, `abyss`. One setting picks between them, `theme_style`,
in `config.xml` or in a theme's `config.json`:

```json
{ "theme_style": "abyss" }
```

| Value | Meaning |
|---|---|
| `default` | Today's look: white text on black, dark tiles, frosted-white pills. What any other value means. |
| `abyss` | A minimal dark look with frosted surfaces, a near-white accent and rounder corners. |

Safe mode (three crashes in five minutes, [`WATCHDOG.md`](WATCHDOG.md)) forces `default`, like the other theme
settings. Removing the key, or resetting the theme, returns to the default.

## What abyss changes

- Focus is a solid near-white (`#F5F5F7`) with dark text: the focused pill, the focused choice on the settings
  screen, and the Search field. A focused list row stays glass, with a brighter near-white edge.
- Surfaces are translucent white fills (about 8 to 12 percent) with a faint light edge (about 14 percent) instead
  of opaque grey: pills, the dock band, the highlight on the focused tile. The tiles themselves are a very dark
  neutral (`#111113`), and the popup panels (Inputs, Module cards) are a nearly opaque dark neutral so the content
  behind them cannot cut into the text.
- Dividers and the selected settings choice use a dark grey (`#373737`).
- Corners are rounder: 24dp for pills, rows, choices, panels and tiles (the default uses 21, 13, 14, 20 and 16),
  and 32dp for the dock band (default 26). Pills are 42dp tall, so they simply become full capsules.
- The window stays black, the text colours keep the same white steps, and fonts are unchanged. The Sky backdrop
  (`sky_intensity`, [`sky.md`](sky.md)) is as it was. The Clock screensaver is not themed.

The TV's Android has no real backdrop blur, so "frosted" here means translucent fills and a thin light border
over whatever is behind, not a blurred copy of it.

## How it works

Colours and corner radii are theme attributes (`res/values/attrs.xml`). `Theme.TvHome` gives them their default
values; the `abyss` style is the overlay `ThemeOverlay.TvHome.Abyss` in `res/values/styles.xml`, which lists only
the values that differ. Each screen layers the overlay on its theme before inflating its layout, so Home, Search,
Shows, Live, Memory and the launcher settings all follow `theme_style` when they open. Changing the setting takes
effect the next time a screen opens (restart Home after pushing a theme).

To add another style, add an overlay with the values that differ and a name in `ThemeStyle` / `Appearance.apply`.

## Cost

No timers, threads, services, caches or bitmaps were added. The style is a theme overlay resolved when a screen
is created.

## Credit

The abyss look is inspired by the Abyss theme for Jellyfin by AumGupta (MIT licence,
https://github.com/AumGupta/abyss-jellyfin): design inspiration only, no code was copied.
