package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Plain JVM tests for the sunset palette: anchors, interpolation and clamping. */
public class SkyTest {

    @Test
    public void nightLooksTheSameAtBothEndsOfTheDay() {
        Sky.Palette first = Sky.at(0);
        Sky.Palette last = Sky.at(1439);
        assertEquals(first.top, last.top);
        assertEquals(first.mid, last.mid);
        assertEquals(first.bottom, last.bottom);
        assertEquals(0, last.sun);
    }

    @Test
    public void glowPeaksAtSunriseAndSunset() {
        assertTrue(Sky.at(450).sun > Sky.at(780).sun);       // 07:30 sunrise vs 13:00 day
        assertTrue(Sky.at(1140).sun > Sky.at(1260).sun);     // 19:00 sunset vs 21:00 dusk
        assertTrue(Sky.at(1140).sun > Sky.at(450).sun);      // the evening is the warmest moment
        assertEquals(0, Sky.at(60).sun);
        assertEquals(0, Sky.at(1400).sun);
    }

    @Test
    public void theGlowTracksTheSunFromLeftToRight() {
        assertTrue(Sky.at(390).sunX < Sky.at(780).sunX);
        assertTrue(Sky.at(780).sunX < Sky.at(1140).sunX);
        assertTrue(Sky.at(780).sunX > 0.4f && Sky.at(780).sunX < 0.6f);   // midday is near the centre
    }

    @Test
    public void coloursInterpolateBetweenAnchors() {
        Sky.Palette dawn = Sky.at(390);
        Sky.Palette sunrise = Sky.at(450);
        Sky.Palette half = Sky.at(420);
        assertEquals(Sky.lerpColor(dawn.top, sunrise.top, 0.5f), half.top);
        assertNotEquals(dawn.top, half.top);
        assertNotEquals(sunrise.top, half.top);
    }

    @Test
    public void timeIsClampedAndAnsweredEveryMinuteOfTheDay() {
        assertEquals(Sky.at(0).top, Sky.at(-30).top);
        assertEquals(Sky.at(1440).top, Sky.at(4000).top);
        for (int minute = 0; minute < 1440; minute += 5) {
            Sky.Palette p = Sky.at(minute);
            double top = luma(p.top);
            double mid = luma(p.mid);
            // The header and the clock sit on the top two thirds; they stay on dark ground all day.
            assertTrue("top too bright at " + minute, top < 0.4);
            assertTrue("mid too bright at " + minute, mid < 0.4);
        }
        assertEquals(1439, Sky.minuteOfDay(23, 59));
        assertEquals(0, Sky.minuteOfDay(0, 0));
    }

    /** Rough relative brightness of an opaque ARGB colour, 0 to 1. */
    private static double luma(int color) {
        return (0.2126 * ((color >> 16) & 0xFF) + 0.7152 * ((color >> 8) & 0xFF) + 0.0722 * (color & 0xFF)) / 255.0;
    }

    @Test
    public void sunTimesMatchKnownDays() {
        // London, 2024-06-21 (day 173), UTC+1: sunrise about 04:43, sunset about 21:21.
        int[] london = Sky.sunTimes(51.5, -0.12, 173, 60);
        assertEquals(4 * 60 + 43, london[0], 4);
        assertEquals(21 * 60 + 21, london[1], 4);
        // Equator at the March equinox (day 80), UTC: close to 06:00 and 18:00.
        int[] eq = Sky.sunTimes(0, 0, 80, 0);
        assertEquals(6 * 60 + 7, eq[0], 8);
        assertEquals(18 * 60 + 8, eq[1], 8);
        // Southern winter is shorter than southern summer.
        int[] winter = Sky.sunTimes(-33.9, 151.2, 172, 600);
        int[] summer = Sky.sunTimes(-33.9, 151.2, 355, 660);
        assertTrue(winter[1] - winter[0] < summer[1] - summer[0]);
    }

    @Test
    public void sunTimesRejectPolarAndBadInput() {
        assertEquals(null, Sky.sunTimes(80, 0, 172, 0));    // midnight sun
        assertEquals(null, Sky.sunTimes(80, 0, 355, 0));    // polar night
        assertEquals(null, Sky.sunTimes(95, 0, 100, 0));
        assertEquals(null, Sky.sunTimes(0, 0, 0, 0));
    }

    @Test
    public void anchorsFollowTheSunAndFallBackWithout() {
        // The reference day is a no-op.
        assertEquals(Sky.at(1100).top, Sky.at(1100, Sky.REF_SUNRISE, Sky.REF_SUNSET).top);
        assertEquals(Sky.at(1100).sun, Sky.at(1100, Sky.REF_SUNRISE, Sky.REF_SUNSET).sun);
        // Sunset at 17:00: the glow that peaks just before the reference sunset now peaks earlier.
        int sunset = 1020;
        Sky.Palette atSunset = Sky.at(sunset, 400, sunset);
        assertEquals(Sky.at(Sky.REF_SUNSET).sun, atSunset.sun);
        assertEquals(Sky.at(Sky.REF_SUNRISE).sun, Sky.at(400, 400, sunset).sun);
        assertTrue(Sky.at(sunset - 20, 400, sunset).sun > Sky.at(780, 400, sunset).sun);
        // Implausible times behave exactly as the fixed palette.
        assertEquals(Sky.at(900).mid, Sky.at(900, -1, -1).mid);
        assertEquals(Sky.at(900).mid, Sky.at(900, 800, 700).mid);
        // Midnight and end of day stay night.
        assertEquals(Sky.at(0).top, Sky.at(0, 400, sunset).top);
        assertEquals(Sky.at(1440).top, Sky.at(1440, 400, sunset).top);
    }
}
