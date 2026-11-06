package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This fork: the tiles of the remote's Settings panel (QuickSettingsActivity), as arranged on Settings > System > Quick
 * settings: their order, which are off, and the apps behind the two app buttons. Stored in Config.SETTINGS_NAME.
 */
final class QuickTiles {

    static final String ORDER_KEY = "qs_order", OFF_KEY = "qs_off", APP_KEY = "qs_app_", LABEL_KEY = "qs_label_";
    static final String DISPLAY = "display", NETWORK = "network", APPS = "apps", LAUNCHER = "launcher",
            APP1 = "app1", APP2 = "app2";
    static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(DISPLAY, NETWORK, APPS, LAUNCHER, APP1, APP2));

    private QuickTiles() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Every tile, in the chosen order (a tile the saved order lacks goes at the end), with the tiles that are off
     * after the ones that are on.
     */
    static List<String> order(Context ctx) {
        List<String> saved = new ArrayList<>();
        for (String id : prefs(ctx).getString(ORDER_KEY, "").split(",")) {
            if (ALL.contains(id) && !saved.contains(id)) saved.add(id);
        }
        for (String id : ALL) if (!saved.contains(id)) saved.add(id);
        Set<String> off = off(ctx);
        List<String> out = new ArrayList<>();
        for (String id : saved) if (!off.contains(id)) out.add(id);
        for (String id : saved) if (off.contains(id)) out.add(id);
        return out;
    }

    static void saveOrder(Context ctx, List<String> order) {
        prefs(ctx).edit().putString(ORDER_KEY, android.text.TextUtils.join(",", order)).apply();
    }

    /** Tiles turned off; the two app buttons start off. */
    static Set<String> off(Context ctx) {
        String saved = prefs(ctx).getString(OFF_KEY, null);
        Set<String> out = new HashSet<>();
        if (saved == null) {
            out.add(APP1);
            out.add(APP2);
        } else {
            for (String id : saved.split(",")) if (ALL.contains(id)) out.add(id);
        }
        return out;
    }

    /** Turns a tile on or off; off goes to the bottom of the grid, on comes back after the last tile that is on. */
    static void setOn(Context ctx, String id, boolean on) {
        Set<String> off = off(ctx);
        if (on) off.remove(id);
        else off.add(id);
        List<String> order = order(ctx);
        order.remove(id);
        if (on) {
            int at = 0;
            for (int i = 0; i < order.size(); i++) if (!off.contains(order.get(i))) at = i + 1;
            order.add(at, id);
        } else {
            order.add(id);
        }
        prefs(ctx).edit().putString(OFF_KEY, android.text.TextUtils.join(",", off))
                .putString(ORDER_KEY, android.text.TextUtils.join(",", order)).apply();
    }

    /** The name the owner gave a tile, or "" for its own. */
    static String label(Context ctx, String id) {
        return prefs(ctx).getString(LABEL_KEY + id, "");
    }

    static void setLabel(Context ctx, String id, String label) {
        prefs(ctx).edit().putString(LABEL_KEY + id, label == null ? "" : label.trim()).apply();
    }

    /** Back to the first layout: order, on and off, apps and names. */
    static void reset(Context ctx) {
        SharedPreferences.Editor e = prefs(ctx).edit().remove(ORDER_KEY).remove(OFF_KEY);
        for (String id : ALL) e.remove(APP_KEY + id).remove(LABEL_KEY + id);
        e.apply();
    }

    /** The tile's own name: the setting's, or for an app button its app's (or "App button 1"). */
    static CharSequence defaultLabel(Context ctx, String id) {
        switch (id) {
            case DISPLAY: return ctx.getString(R.string.quick_picture);
            case NETWORK: return ctx.getString(R.string.quick_network);
            case APPS: return ctx.getString(R.string.quick_apps);
            case LAUNCHER: return ctx.getString(R.string.launcher_settings);
            default:
                try {
                    PackageManager pm = ctx.getPackageManager();
                    return pm.getApplicationLabel(pm.getApplicationInfo(app(ctx, id), 0));
                } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
                    return ctx.getString(R.string.sm_qs_app_button, APP1.equals(id) ? 1 : 2);
                }
        }
    }

    static boolean isApp(String id) {
        return APP1.equals(id) || APP2.equals(id);
    }

    /** The package behind an app button, or "". */
    static String app(Context ctx, String id) {
        return prefs(ctx).getString(APP_KEY + id, "");
    }

    static void setApp(Context ctx, String id, String pkg) {
        prefs(ctx).edit().putString(APP_KEY + id, pkg).apply();
    }

    /** What the tile opens; null for an app button without an app (or one no longer installed). */
    static Intent intent(Context ctx, String id) {
        switch (id) {
            case DISPLAY: return SystemSettings.displayIntent(ctx);
            case NETWORK: return SystemSettings.intent(ctx, Settings.ACTION_WIFI_SETTINGS);
            case APPS: return SystemSettings.intent(ctx, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
            // Through Home, so it rebuilds with the new choices when Launcher settings closes.
            case LAUNCHER: return new Intent(ctx, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS);
            default:
                String pkg = app(ctx, id);
                if (pkg.isEmpty()) return null;
                PackageManager pm = ctx.getPackageManager();
                Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                return launch != null ? launch : pm.getLaunchIntentForPackage(pkg);
        }
    }

    /** The tiles the panel shows: on, and for an app button, with an app that can be opened. */
    static List<String> shown(Context ctx) {
        Set<String> off = off(ctx);
        List<String> out = new ArrayList<>();
        for (String id : order(ctx)) {
            if (off.contains(id)) continue;
            if (isApp(id) && intent(ctx, id) == null) continue;
            out.add(id);
        }
        return out;
    }

    /** The tile's view (item_quick_tile): icon and name; an app button shows its app's own icon and name. */
    static View tile(Context ctx, LayoutInflater inflater, LinearLayout row, String id) {
        View tile = inflater.inflate(R.layout.item_quick_tile, row, false);
        ImageView icon = tile.findViewById(R.id.icon);
        TextView title = tile.findViewById(R.id.title);
        switch (id) {
            case DISPLAY: icon.setImageResource(R.drawable.ic_picture); title.setText(R.string.quick_picture); break;
            case NETWORK: icon.setImageResource(R.drawable.ic_wifi); title.setText(R.string.quick_network); break;
            case APPS: icon.setImageResource(R.drawable.ic_apps); title.setText(R.string.quick_apps); break;
            case LAUNCHER: icon.setImageResource(R.drawable.ic_home_settings); title.setText(R.string.launcher_settings); break;
            default:
                String pkg = app(ctx, id);
                Drawable appIcon = null;
                CharSequence name = null;
                try {
                    PackageManager pm = ctx.getPackageManager();
                    appIcon = pm.getApplicationIcon(pkg);
                    name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                } catch (PackageManager.NameNotFoundException | IllegalArgumentException ignored) {
                }
                if (appIcon != null) {
                    icon.setImageTintList(null);   // the app's own colours
                    icon.setImageDrawable(appIcon);
                    title.setText(name);
                } else {
                    icon.setImageResource(R.drawable.ic_apps);
                    title.setText(ctx.getString(R.string.sm_qs_app_button, APP1.equals(id) ? 1 : 2));
                }
        }
        String own = label(ctx, id);
        if (!own.isEmpty()) title.setText(own);   // renamed on Settings > System > Quick settings
        return tile;
    }

    /** Lays the tiles out two to a row in tiles; each view is passed to bind (id, view) before it is added. */
    static List<View> fill(Context ctx, LinearLayout tiles, List<String> ids, java.util.function.BiConsumer<String, View> bind) {
        tiles.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        int gap = Math.round(8 * ctx.getResources().getDisplayMetrics().density);
        List<View> views = new ArrayList<>();
        LinearLayout row = null;
        for (String id : ids) {
            if (row == null || row.getChildCount() == 2) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (tiles.getChildCount() > 0) lp.topMargin = gap;
                tiles.addView(row, lp);
            }
            View tile = tile(ctx, inflater, row, id);
            if (row.getChildCount() == 1) ((LinearLayout.LayoutParams) tile.getLayoutParams()).setMarginStart(gap);
            bind.accept(id, tile);
            row.addView(tile);
            views.add(tile);
        }
        // An odd last tile keeps its half width, like the others.
        if (row != null && row.getChildCount() == 1) {
            View spacer = new View(ctx);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.setMarginStart(gap);
            row.addView(spacer, lp);
        }
        return views;
    }
}
