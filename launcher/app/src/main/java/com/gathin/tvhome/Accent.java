package com.gathin.tvhome;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The highlight colour (this fork, Settings > Home > Highlight colour): what a focused pill, menu row, Continue
 * watching card and the tiles' glass turn, and the outline of the pills at rest. Kept in Config.SETTINGS_NAME as
 * 0xAARRGGBB; 0 is the launcher's own white, and then nothing here changes a view.
 */
final class Accent {

    static final String KEY = "accent_color";
    /** Adaptive: the colour follows the focused app, sampled from its banner like Home's glow (KEY is then 0). */
    static final String ADAPTIVE_KEY = "accent_adaptive";

    /** White (the default) first, then the presets the Settings menu offers as swatches. */
    static final int[] PRESETS = {
            0,
            0xFF0A84FF,   // blue
            0xFF64D2FF,   // sky
            0xFF30D158,   // green
            0xFFFFD60A,   // yellow
            0xFFFF9F0A,   // orange
            0xFFFF453A,   // red
            0xFFFF375F,   // pink
            0xFFBF5AF2,   // purple
    };

    private static final int DARK_INK = 0xFF111111;

    private Accent() {
    }

    static int get(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getInt(KEY, 0);
    }

    static void set(Context context, int argb) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY, argb == 0 ? 0 : 0xFF000000 | argb).putBoolean(ADAPTIVE_KEY, false).apply();
    }

    static boolean adaptive(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getBoolean(ADAPTIVE_KEY, false);
    }

    static void setAdaptive(Context context) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(KEY, 0).putBoolean(ADAPTIVE_KEY, true).apply();
    }

    /** What a focused pill or row is filled with. */
    static int fill(int accent) {
        return accent == 0 ? 0xF5FFFFFF : 0xF5000000 | (accent & 0xFFFFFF);
    }

    /** Text and icons on that fill: dark on a light colour, white on a dark one. */
    static int onFill(int accent) {
        return accent == 0 || luminance(accent) > 0.3f ? DARK_INK : 0xFFFFFFFF;
    }

    /** A ring or outline drawn round a focused card. */
    static int ring(int accent) {
        return accent == 0 ? 0xF2FFFFFF : 0xFF000000 | (accent & 0xFFFFFF);
    }

    /** A pill's outline at rest: the faint white edge, or the colour at partial strength. */
    static int edge(int accent, int plain) {
        return accent == 0 ? plain : 0x73000000 | (accent & 0xFFFFFF);
    }

    /** Relative luminance, 0 (black) to 1 (white), with sRGB gamma. */
    static float luminance(int argb) {
        return (float) (0.2126 * lin(Color.red(argb)) + 0.7152 * lin(Color.green(argb)) + 0.0722 * lin(Color.blue(argb)));
    }

    private static double lin(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static String hex(int argb) {
        return String.format("#%06X", argb & 0xFFFFFF);
    }

    /** "#3A7BFF", "3a7bff" or "#3AF" to 0xFF3A7BFF; -1 when it is not a colour. */
    static int parse(String s) {
        if (s == null) return -1;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() == 3) t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
        if (t.length() != 6) return -1;
        try {
            return 0xFF000000 | Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Home's frosted pill (pill_bg) in the colour: filled with focus, outlined at rest, the reorder state kept. */
    static Drawable pill(float radius, float density, int accent) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(fill(accent));
        focused.setCornerRadius(radius);
        GradientDrawable activated = new GradientDrawable();
        activated.setColor(0x596EC1FF);
        activated.setStroke(Math.round(1.5f * density), 0xFF6EC1FF);
        activated.setCornerRadius(radius);
        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x2EFFFFFF, 0x14FFFFFF});
        rest.setStroke(Math.max(1, Math.round(density)), edge(accent, 0x38FFFFFF));
        rest.setCornerRadius(radius);
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_activated}, activated);
        s.addState(new int[]{}, rest);
        return s;
    }

    /** A menu row (input_row_bg): clear at rest, filled with focus. */
    static Drawable row(float radius, int accent) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(fill(accent));
        focused.setCornerRadius(radius);
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(0);
        rest.setCornerRadius(radius);
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{}, rest);
        return s;
    }

    /** rest at rest, the colour's ink with focus; a dimmed rest colour keeps its dimming on the fill too. */
    static ColorStateList ink(int accent, int rest) {
        int focused = onFill(accent);
        int alpha = Color.alpha(rest) < 0xC0 ? Math.max(0x99, Color.alpha(rest) + 0x40) : 0xFF;
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{(alpha << 24) | (focused & 0xFFFFFF), rest});
    }

    /** Recolours a top-bar pill: its background, its text and its icon tint (left alone when the icon is a logo). */
    static void stylePill(TextView v, float density, int accent) {
        if (accent == 0) return;
        v.setBackground(pill(21 * density, density, accent));
        v.setTextColor(ink(accent, 0xFFFFFFFF));
        if (v.getCompoundDrawableTintList() != null) v.setCompoundDrawableTintList(ink(accent, 0xFFFFFFFF));
    }

    /**
     * Recolours a popup's rows (item_input, item_card_row): every focusable view with a state background gets the
     * coloured row, and the text and icons that follow their row's focus get the matching ink.
     */
    static void restyle(View v, float density, int accent) {
        if (accent == 0 || v == null) return;
        if (v.isFocusable() && v.getBackground() instanceof StateListDrawable && !(v instanceof TextView && isPill(v))) {
            v.setBackground(row(13 * density, accent));
        }
        if (v instanceof TextView) {
            ColorStateList c = ((TextView) v).getTextColors();
            if (c.isStateful()) ((TextView) v).setTextColor(ink(accent, c.getDefaultColor()));
        } else if (v instanceof ImageView) {
            ColorStateList c = ((ImageView) v).getImageTintList();
            if (c != null && c.isStateful()) ((ImageView) v).setImageTintList(ink(accent, c.getDefaultColor()));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) restyle(g.getChildAt(i), density, accent);
        }
    }

    /** A pill inside a popup (a prompt's buttons) keeps its pill shape: only rows become rows. */
    private static boolean isPill(View v) {
        return v.getLayoutParams() != null && v.getLayoutParams().width == ViewGroup.LayoutParams.WRAP_CONTENT;
    }
}
