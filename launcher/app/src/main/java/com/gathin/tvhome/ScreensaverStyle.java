package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * This fork: how the clock screensaver (ClockScene) is laid out, as chosen under Settings > Clock > Screensaver.
 * Stored in Config.SETTINGS_NAME. The clock moves around the screen (as before) or stays on one of nine spots; the
 * date and the weather sit under the clock, on a spot of their own, or are left out; each has a size; the glow
 * behind changes colour (as before), keeps one chosen colour, or is off, from one to four points of light.
 */
final class ScreensaverStyle {

    static final String CLOCK_MODE = "ss_clock_mode", CLOCK_SPOT = "ss_clock_spot", CLOCK_SIZE = "ss_clock_size";
    static final String DATE_MODE = "ss_date_mode", DATE_SPOT = "ss_date_spot", DATE_SIZE = "ss_date_size";
    static final String WEATHER_MODE = "ss_weather_mode", WEATHER_SPOT = "ss_weather_spot", WEATHER_SIZE = "ss_weather_size";
    static final String GLOW = "ss_glow", GLOW_COLOR = "ss_glow_color", GLOW_POINTS = "ss_glow_points";

    /** The clock: moving around the screen, or fixed on its spot. */
    static final String MOVE = "move", FIXED = "fixed";
    /** The date or the weather: under the clock, on a spot of its own, or not shown. */
    static final String WITH_CLOCK = "with", OWN = "own", OFF = "off";
    /** The glow: the slow colour cycle, one colour, or plain black. */
    static final String GLOW_CYCLE = "cycle", GLOW_CUSTOM = "custom", GLOW_OFF = "off";

    /** S, M, L, XL: text size against the screensaver's own (M). */
    static final float[] SIZES = {0.7f, 1f, 1.3f, 1.6f};
    static final String[] SIZE_NAMES = {"S", "M", "L", "XL"};
    static final int MAX_POINTS = 4;

    String clockMode = MOVE, clockSpot = "cm";
    String dateMode = WITH_CLOCK, dateSpot = "lb";
    String weatherMode = WITH_CLOCK, weatherSpot = "rb";
    int clockSize = 1, dateSize = 1, weatherSize = 1;
    String glow = GLOW_CYCLE;
    int glowColor = 0xFF2B5CFF;
    int glowPoints = 1;

    static ScreensaverStyle load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        ScreensaverStyle s = new ScreensaverStyle();
        s.clockMode = oneOf(p.getString(CLOCK_MODE, MOVE), MOVE, MOVE, FIXED);
        s.clockSpot = spot(p.getString(CLOCK_SPOT, "cm"), "cm");
        s.clockSize = size(p.getInt(CLOCK_SIZE, 1));
        s.dateMode = oneOf(p.getString(DATE_MODE, WITH_CLOCK), WITH_CLOCK, WITH_CLOCK, OWN, OFF);
        s.dateSpot = spot(p.getString(DATE_SPOT, "lb"), "lb");
        s.dateSize = size(p.getInt(DATE_SIZE, 1));
        s.weatherMode = oneOf(p.getString(WEATHER_MODE, WITH_CLOCK), WITH_CLOCK, WITH_CLOCK, OWN, OFF);
        s.weatherSpot = spot(p.getString(WEATHER_SPOT, "rb"), "rb");
        s.weatherSize = size(p.getInt(WEATHER_SIZE, 1));
        s.glow = oneOf(p.getString(GLOW, GLOW_CYCLE), GLOW_CYCLE, GLOW_CYCLE, GLOW_CUSTOM, GLOW_OFF);
        s.glowColor = p.getInt(GLOW_COLOR, 0xFF2B5CFF) | 0xFF000000;
        s.glowPoints = Math.max(1, Math.min(MAX_POINTS, p.getInt(GLOW_POINTS, 1)));
        return s;
    }

    private static String oneOf(String value, String fallback, String... allowed) {
        for (String a : allowed) if (a.equals(value)) return value;
        return fallback;
    }

    private static int size(int i) {
        return Math.max(0, Math.min(SIZES.length - 1, i));
    }

    /** A spot is two letters: across (l, c, r) then up and down (t, m, b), e.g. "cm" for the centre. */
    static String spot(String value, String fallback) {
        return value != null && value.length() == 2 && "lcr".indexOf(value.charAt(0)) >= 0
                && "tmb".indexOf(value.charAt(1)) >= 0 ? value : fallback;
    }

    /** 0, 0.5 or 1 across the room left for the view. */
    static float across(String spot) {
        return "lcr".indexOf(spot.charAt(0)) / 2f;
    }

    /** 0, 0.5 or 1 down the room left for the view. */
    static float down(String spot) {
        return "tmb".indexOf(spot.charAt(1)) / 2f;
    }
}
