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
 * settings: their order, which are off, and the app tiles added there (each opens one app). Stored in
 * Config.SETTINGS_NAME.
 */
final class QuickTiles {

    static final String ORDER_KEY = "qs_order", OFF_KEY = "qs_off", APP_KEY = "qs_app_", LABEL_KEY = "qs_label_",
            APP_TILES_KEY = "qs_apps", DIM_WIDE_KEY = "qs_dim_wide";
    static final String DISPLAY = "display", NETWORK = "network", APPS = "apps", LAUNCHER = "launcher", DIM = "dim";
    /** The tiles every panel has; app tiles ("app1", "app2", ...) are added on top of them. DIM is Dim screen. */
    static final List<String> FIXED = Collections.unmodifiableList(Arrays.asList(DISPLAY, NETWORK, APPS, LAUNCHER, DIM));
    /** App tiles at most, so the panel still fits under the top of the screen. */
    static final int MAX_APP_TILES = 6;

    private QuickTiles() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * The app tiles added, in the order they were added. Before tiles could be added, the panel had two fixed app
     * buttons, app1 and app2: one that had an app keeps it as an app tile.
     */
    static List<String> appTiles(Context ctx) {
        SharedPreferences p = prefs(ctx);
        String saved = p.getString(APP_TILES_KEY, null);
        List<String> out = new ArrayList<>();
        if (saved == null) {
            for (String old : new String[]{"app1", "app2"}) if (!p.getString(APP_KEY + old, "").isEmpty()) out.add(old);
            return out;
        }
        for (String id : saved.split(",")) if (isApp(id) && !out.contains(id)) out.add(id);
        return out;
    }

    /** The fixed tiles, then the app tiles. */
    static List<String> all(Context ctx) {
        List<String> out = new ArrayList<>(FIXED);
        out.addAll(appTiles(ctx));
        return out;
    }

    /** A new app tile for pkg, on, after the last tile that is on; its id, or null with MAX_APP_TILES already. */
    static String addApp(Context ctx, String pkg) {
        List<String> apps = appTiles(ctx);
        if (apps.size() >= MAX_APP_TILES) return null;
        int n = 1;
        while (apps.contains("app" + n)) n++;
        String id = "app" + n;
        apps.add(id);
        prefs(ctx).edit().putString(APP_TILES_KEY, android.text.TextUtils.join(",", apps))
                .putString(APP_KEY + id, pkg).remove(LABEL_KEY + id).apply();
        setOn(ctx, id, true);
        return id;
    }

    /** Takes an app tile off the panel for good, with its app and name. */
    static void removeApp(Context ctx, String id) {
        List<String> order = order(ctx);
        order.remove(id);
        Set<String> off = off(ctx);
        off.remove(id);
        List<String> apps = appTiles(ctx);
        apps.remove(id);
        prefs(ctx).edit().putString(APP_TILES_KEY, android.text.TextUtils.join(",", apps))
                .putString(ORDER_KEY, android.text.TextUtils.join(",", order))
                .putString(OFF_KEY, android.text.TextUtils.join(",", off))
                .remove(APP_KEY + id).remove(LABEL_KEY + id).apply();
    }

    /**
     * Every tile, in the chosen order (a tile the saved order lacks goes at the end), with the tiles that are off
     * after the ones that are on.
     */
    static List<String> order(Context ctx) {
        List<String> all = all(ctx);
        List<String> saved = new ArrayList<>();
        for (String id : prefs(ctx).getString(ORDER_KEY, "").split(",")) {
            if (all.contains(id) && !saved.contains(id)) saved.add(id);
        }
        for (String id : all) if (!saved.contains(id)) saved.add(id);
        Set<String> off = off(ctx);
        List<String> out = new ArrayList<>();
        for (String id : saved) if (!off.contains(id)) out.add(id);
        for (String id : saved) if (off.contains(id)) out.add(id);
        return out;
    }

    static void saveOrder(Context ctx, List<String> order) {
        prefs(ctx).edit().putString(ORDER_KEY, android.text.TextUtils.join(",", order)).apply();
    }

    /** Tiles turned off. */
    static Set<String> off(Context ctx) {
        List<String> all = all(ctx);
        Set<String> out = new HashSet<>();
        for (String id : prefs(ctx).getString(OFF_KEY, "").split(",")) if (all.contains(id)) out.add(id);
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

    /** Back to the first layout: the settings tiles in order and on, no app tiles, their own names, Dim full width. */
    static void reset(Context ctx) {
        SharedPreferences.Editor e = prefs(ctx).edit().remove(ORDER_KEY).remove(OFF_KEY).putString(APP_TILES_KEY, "")
                .remove(DIM_WIDE_KEY);
        for (String id : all(ctx)) e.remove(APP_KEY + id).remove(LABEL_KEY + id);
        e.apply();
    }

    /** The tile's own name: the setting's, or for an app button its app's (or "App button 1"). */
    static CharSequence defaultLabel(Context ctx, String id) {
        switch (id) {
            case DISPLAY: return ctx.getString(R.string.quick_picture);
            case NETWORK: return ctx.getString(R.string.quick_network);
            case APPS: return ctx.getString(R.string.quick_apps);
            case LAUNCHER: return ctx.getString(R.string.launcher_settings);
            case DIM: return ctx.getString(R.string.sm_qs_dim);
            default:
                try {
                    PackageManager pm = ctx.getPackageManager();
                    return pm.getApplicationLabel(pm.getApplicationInfo(app(ctx, id), 0));
                } catch (PackageManager.NameNotFoundException | IllegalArgumentException e) {
                    return ctx.getString(R.string.sm_qs_app_button, number(id));
                }
        }
    }

    /** Dim screen as a full-width slider (true, the first layout) or a tile half wide like the others. */
    static boolean dimWide(Context ctx) {
        return prefs(ctx).getBoolean(DIM_WIDE_KEY, true);
    }

    static void setDimWide(Context ctx, boolean wide) {
        prefs(ctx).edit().putBoolean(DIM_WIDE_KEY, wide).apply();
    }

    /** A tile that takes a row of its own. */
    static boolean wide(Context ctx, String id) {
        return DIM.equals(id) && dimWide(ctx);
    }

    /** The tiles as the panel lays them out: two a row, a wide tile on a row of its own. */
    static List<List<String>> rows(Context ctx, List<String> ids) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        for (String id : ids) {
            if (wide(ctx, id)) {
                if (!row.isEmpty()) rows.add(row);
                rows.add(Collections.singletonList(id));
                row = new ArrayList<>();
                continue;
            }
            row.add(id);
            if (row.size() == 2) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) rows.add(row);
        return rows;
    }

    /**
     * Shows the Dim screen level on its tile (value and bar, out of ScreenDim.MAX), now and whenever its bar is laid
     * out again; returns the Runnable that shows it, for after a change.
     */
    static Runnable showDim(View tile) {
        Context ctx = tile.getContext();
        TextView value = tile.findViewById(R.id.dimValue);
        View track = tile.findViewById(R.id.dimTrack);
        View fill = tile.findViewById(R.id.dimFill);
        Runnable show = () -> {
            int level = ScreenDim.level(ctx);
            if (!ScreenDim.available()) value.setText(R.string.sm_qs_dim_unavailable);
            else if (level == 0) value.setText(R.string.sm_off);
            else value.setText(java.text.NumberFormat.getPercentInstance().format(level / 100.0));
            int width = Math.round(track.getWidth() * level / (float) ScreenDim.MAX);
            if (fill.getLayoutParams().width != width) {
                fill.getLayoutParams().width = width;
                fill.requestLayout();
            }
        };
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) v.post(show);
        });
        show.run();
        return show;
    }

    /** An app tile's id: "app" and a number ("apps" is the Apps settings tile). */
    static boolean isApp(String id) {
        return id != null && id.matches("app[0-9]+");
    }

    private static int number(String id) {
        try {
            return Integer.parseInt(id.substring(3));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** The package behind an app button, or "". */
    static String app(Context ctx, String id) {
        return prefs(ctx).getString(APP_KEY + id, "");
    }

    /** A new app for the tile; a name given for the old app goes, so the tile shows the new app's own name. */
    static void setApp(Context ctx, String id, String pkg) {
        SharedPreferences.Editor e = prefs(ctx).edit().putString(APP_KEY + id, pkg);
        if (!pkg.equals(app(ctx, id))) e.remove(LABEL_KEY + id);
        e.apply();
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
        if (DIM.equals(id)) {
            View dim = inflater.inflate(dimWide(ctx) ? R.layout.item_quick_dim : R.layout.item_quick_dim_small, row, false);
            String own = label(ctx, id);
            ((TextView) dim.findViewById(R.id.title)).setText(own.isEmpty() ? ctx.getString(R.string.sm_qs_dim) : own);
            showDim(dim);
            return dim;
        }
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
                    title.setText(ctx.getString(R.string.sm_qs_app_button, number(id)));
                }
        }
        String own = label(ctx, id);
        if (!own.isEmpty()) title.setText(own);   // renamed on Settings > System > Quick settings
        return tile;
    }

    /**
     * Lays the tiles out as rows() does (two a row, a wide tile alone); each view is passed to bind (id, view) before
     * it is added.
     */
    static List<View> fill(Context ctx, LinearLayout tiles, List<String> ids, java.util.function.BiConsumer<String, View> bind) {
        tiles.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        int gap = Math.round(8 * ctx.getResources().getDisplayMetrics().density);
        List<View> views = new ArrayList<>();
        for (List<String> ids2 : rows(ctx, ids)) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            if (tiles.getChildCount() > 0) rowLp.topMargin = gap;
            tiles.addView(row, rowLp);
            for (String id : ids2) {
                View tile = tile(ctx, inflater, row, id);
                if (row.getChildCount() == 1) ((LinearLayout.LayoutParams) tile.getLayoutParams()).setMarginStart(gap);
                bind.accept(id, tile);
                row.addView(tile);
                views.add(tile);
            }
            // A lone half-width tile keeps its half width, like the others.
            if (ids2.size() == 1 && !wide(ctx, ids2.get(0))) {
                View spacer = new View(ctx);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
                lp.setMarginStart(gap);
                row.addView(spacer, lp);
            }
        }
        return views;
    }
}
