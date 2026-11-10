package com.gathin.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

/**
 * This fork: the Settings panel's Dim screen slider. Many TVs (Hisense songshan, Android 11) leave the backlight to the
 * vendor's picture settings and ignore Android's screen_brightness, so the screen is dimmed with a see-through black
 * layer over every app instead: an accessibility overlay of the remote-key helper (HomeKeyService, else
 * InputKeyService), which needs no other permission. The level is kept in Settings and put back whenever a helper
 * connects or Home comes back, so it survives app switches, standby and restarts. Without a running helper there is
 * nothing to draw it with, and the slider says so.
 */
final class ScreenDim {
    private static final String TAG = "ScreenDim";
    /** Percent of black over the screen, 0 (off) to MAX, in STEP steps. */
    static final String KEY = "screen_dim";
    static final int MAX = 80;
    static final int STEP = 1;   // every percent: a held Left/Right sweeps through them

    private static View overlay;
    private static AccessibilityService owner;

    private ScreenDim() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    static int level(Context ctx) {
        try {
            return clamp(prefs(ctx).getInt(KEY, 0));
        } catch (IllegalStateException lockedAtBoot) {
            return 0;   // credential storage before the first unlock; Home puts the level back once it starts
        }
    }

    static int clamp(int level) {
        int stepped = Math.round(level / (float) STEP) * STEP;
        return Math.max(0, Math.min(MAX, stepped));
    }

    /** The helper that draws the layer, or null when neither key helper runs. */
    static AccessibilityService host() {
        if (HomeKeyService.running != null) return HomeKeyService.running;
        return InputKeyService.running;
    }

    static boolean available() {
        return host() != null;
    }

    /** The level OK turns dimming back on to: the last one used, half to start with. */
    private static final String LAST_KEY = "screen_dim_last";

    /** Keeps the new level and shows it at once. */
    static void set(Context ctx, int level) {
        int kept = clamp(level);
        SharedPreferences.Editor edit = prefs(ctx).edit().putInt(KEY, kept);
        if (kept > 0) edit.putInt(LAST_KEY, kept);
        edit.apply();
        apply(ctx);
    }

    /** OK on the slider: off when on, else back to the last level. */
    static void toggle(Context ctx) {
        int level = level(ctx);
        set(ctx, level > 0 ? 0 : Math.max(STEP, clamp(prefs(ctx).getInt(LAST_KEY, 50))));
    }

    /** Puts the layer on, changes it or takes it off to match the kept level (main thread). */
    static void apply(Context ctx) {
        AccessibilityService service = host();
        int level = level(ctx);
        if (overlay != null && (service != owner || level == 0)) remove();
        if (service == null || level == 0) return;
        if (overlay == null) {
            View layer = new View(service);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("ScreenDim");
            try {
                ((WindowManager) service.getSystemService(Context.WINDOW_SERVICE)).addView(layer, lp);
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot add the dim layer", e);
                return;
            }
            overlay = layer;
            owner = service;
        }
        overlay.setBackgroundColor(Color.argb(Math.round(level * 255 / 100f), 0, 0, 0));
    }

    /** A helper going away takes its layer with it; the other helper, if it runs, takes over. */
    static void detach(AccessibilityService service, Context ctx) {
        if (owner != service) return;
        remove();
        apply(ctx);
    }

    private static void remove() {
        if (overlay == null || owner == null) return;
        try {
            ((WindowManager) owner.getSystemService(Context.WINDOW_SERVICE)).removeView(overlay);
        } catch (RuntimeException ignored) {
            // already gone with the service's window token
        }
        overlay = null;
        owner = null;
    }
}
