package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;

/**
 * Glue between the theme_style setting (see {@link ThemeStyle}) and Android: puts the style's overlay on an
 * activity's theme, and reads the theme's colours and sizes for code that paints.
 * Holds no state, starts nothing and caches nothing.
 */
final class Appearance {

    private Appearance() {
    }

    /**
     * Layers the overlay for {@code cfg.themeStyle} on the activity's theme; the default style needs none. Call
     * before setContentView. The overlay is applied on top of the activity's own theme rather than replacing it,
     * so nothing the manifest set for the activity is lost.
     */
    static void apply(Activity activity, Config cfg) {
        if (ThemeStyle.ABYSS.equals(cfg.themeStyle)) {
            activity.getTheme().applyStyle(R.style.ThemeOverlay_TvHome_Abyss, true);
        }
    }

    /** The colour a theme attribute (R.attr.*) resolves to in {@code ctx}'s theme. */
    static int color(Context ctx, int attr) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    /** The size, in pixels, a dimension attribute (R.attr.radius_*) resolves to in {@code ctx}'s theme. */
    static float dimension(Context ctx, int attr) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, tv, true);
        return tv.getDimension(ctx.getResources().getDisplayMetrics());
    }
}
