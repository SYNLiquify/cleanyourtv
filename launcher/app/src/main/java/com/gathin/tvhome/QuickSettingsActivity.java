package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService), laid out like
 * Google TV's: the date and time with a round button for the TV's settings, then two columns of glass tiles, top
 * right over Home or whatever app is playing. Translucent and in a
 * task of its own, so the app behind keeps running and Back returns to it. Pressing Settings again
 * closes the panel.
 */
public final class QuickSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }


    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        if (getResources().getBoolean(R.bool.rtl_menu_bar)) {
            View panel = findViewById(R.id.panel);
            android.widget.FrameLayout.LayoutParams position =
                    (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
            position.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
            int inset = position.getMarginEnd();
            position.setMarginEnd(0);
            position.setMargins(inset, position.topMargin, 0, position.bottomMargin);
            panel.setLayoutParams(position);
        }
        findViewById(R.id.scrim).setOnClickListener(v -> finish());

        // The date ("Fri, Nov 6", in the launcher's language) and the time, 12- or 24-hour as Home's clock.
        TextClock date = findViewById(R.id.quickDate);
        String datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd");
        date.setFormat12Hour(datePattern);
        date.setFormat24Hour(datePattern);
        ClockFormat.apply(findViewById(R.id.quickTime), Config.load(this).clockFormat);

        findViewById(R.id.quickAll).setOnClickListener(v -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        LinearLayout tiles = findViewById(R.id.tiles);
        View first = addTile(tiles, R.drawable.ic_picture, R.string.quick_picture, SystemSettings.displayIntent(this));
        addTile(tiles, R.drawable.ic_wifi, R.string.quick_network, SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS));
        addTile(tiles, R.drawable.ic_apps, R.string.quick_apps,
                SystemSettings.intent(this, Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        // Through Home, so it rebuilds with the new choices when Launcher settings closes.
        addTile(tiles, R.drawable.ic_home_settings, R.string.launcher_settings,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        first.requestFocus();
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    @SuppressWarnings("deprecation")
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    /** A tile in the grid, two to a row; returns it. */
    private View addTile(LinearLayout tiles, int icon, int title, Intent target) {
        int gap = Math.round(8 * getResources().getDisplayMetrics().density);
        LinearLayout row = tiles.getChildCount() == 0 ? null : (LinearLayout) tiles.getChildAt(tiles.getChildCount() - 1);
        if (row == null || row.getChildCount() == 2) {
            row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            if (tiles.getChildCount() > 0) lp.topMargin = gap;
            tiles.addView(row, lp);
        }
        View tile = getLayoutInflater().inflate(R.layout.item_quick_tile, row, false);
        ((ImageView) tile.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.title)).setText(title);
        if (row.getChildCount() == 1) ((LinearLayout.LayoutParams) tile.getLayoutParams()).setMarginStart(gap);
        tile.setOnClickListener(v -> open(target));
        row.addView(tile);
        return tile;
    }

    private void open(Intent target) {
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
