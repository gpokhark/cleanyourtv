package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ThemeStyleTest {

    @Test
    public void abyssIsRecognisedWhateverTheCase() {
        assertEquals(ThemeStyle.ABYSS, ThemeStyle.normalize("abyss"));
        assertEquals(ThemeStyle.ABYSS, ThemeStyle.normalize(" Abyss "));
    }

    @Test
    public void anythingElseIsTheDefault() {
        assertEquals(ThemeStyle.DEFAULT, ThemeStyle.normalize(null));
        assertEquals(ThemeStyle.DEFAULT, ThemeStyle.normalize(""));
        assertEquals(ThemeStyle.DEFAULT, ThemeStyle.normalize("default"));
        assertEquals(ThemeStyle.DEFAULT, ThemeStyle.normalize("light"));
        assertEquals(ThemeStyle.DEFAULT, ThemeStyle.normalize("abyss2"));
    }
}
