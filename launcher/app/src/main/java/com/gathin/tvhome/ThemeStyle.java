package com.gathin.tvhome;

import java.util.Locale;

/** The names theme_style accepts. Pure, so the parsing can be tested on the JVM. */
final class ThemeStyle {

    static final String DEFAULT = "default";
    static final String ABYSS = "abyss";

    private ThemeStyle() {
    }

    /** "abyss" (any case, surrounding space ignored) stays; anything else, including null, is the default. */
    static String normalize(String raw) {
        if (raw == null) return DEFAULT;
        return ABYSS.equals(raw.trim().toLowerCase(Locale.ROOT)) ? ABYSS : DEFAULT;
    }
}
