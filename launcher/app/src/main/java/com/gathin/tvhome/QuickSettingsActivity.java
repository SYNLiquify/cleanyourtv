package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.Toast;

import java.util.List;
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
        float density = getResources().getDisplayMetrics().density;
        // A soft blurred shadow under the card (the frame's padding is room for the blur to fade out).
        SoftShadow.apply(findViewById(R.id.panel), new SoftShadow(Math.round(36 * density), Math.round(24 * density),
                Math.round(36 * density), Math.round(52 * density), 20 * density, 14 * density, 8 * density, 0xB0));

        // The date ("Fri, Nov 6", in the launcher's language) and the time, 12- or 24-hour as Home's clock.
        TextClock date = findViewById(R.id.quickDate);
        String datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd");
        date.setFormat12Hour(datePattern);
        date.setFormat24Hour(datePattern);
        ClockFormat.apply(findViewById(R.id.quickTime), Config.load(this).clockFormat);

        findViewById(R.id.quickAll).setOnClickListener(v -> open(SystemSettings.intent(this, Settings.ACTION_SETTINGS)));
        // The tiles as arranged on Settings > System > Quick settings (QuickTiles): order, off, app buttons.
        LinearLayout tiles = findViewById(R.id.tiles);
        List<View> shown = QuickTiles.fill(this, tiles, QuickTiles.shown(this), (id, tile) -> {
            if (QuickTiles.DIM.equals(id)) {
                bindDim(tile, QuickTiles.dimWide(this));
                return;
            }
            Intent target = QuickTiles.intent(this, id);
            tile.setOnClickListener(v -> open(target));
        });
        tiles.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        (shown.isEmpty() ? findViewById(R.id.quickAll) : shown.get(0)).requestFocus();
    }

    /**
     * This fork: the Dim screen tile (ScreenDim). Left/Right move it 1% (held, faster and faster; Right dims more,
     * mirrored in a right-to-left language); the bar shows the level out of ScreenDim.MAX. Full width, Left/Right
     * change it straight away and OK turns it off or back on. Compact, Left/Right still reach the tile beside it:
     * OK starts adjusting (the blue ring), and OK or Back ends it; Up or Down end it and move on.
     */
    private void bindDim(View dim, boolean wide) {
        Runnable show = QuickTiles.showDim(dim);
        dim.setOnFocusChangeListener((v, focused) -> {
            if (!focused) v.setActivated(false);
        });
        dim.setOnKeyListener((v, code, event) -> {
            boolean adjusting = wide || v.isActivated();
            if (!wide && v.isActivated() && (code == android.view.KeyEvent.KEYCODE_BACK
                    || code == android.view.KeyEvent.KEYCODE_DPAD_UP || code == android.view.KeyEvent.KEYCODE_DPAD_DOWN)) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) v.setActivated(false);
                return code == android.view.KeyEvent.KEYCODE_BACK;   // Up and Down go on to the next tile
            }
            if (!adjusting) return false;
            if (code != android.view.KeyEvent.KEYCODE_DPAD_LEFT && code != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                return false;
            }
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && ScreenDim.available()) {
                int direction = code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) direction = -direction;
                // Ramps while held: 1% a press, then 2% and 4% a step the longer the key repeats.
                int repeats = event.getRepeatCount();
                int step = ScreenDim.STEP * (repeats < 6 ? 1 : repeats < 16 ? 2 : 4);
                ScreenDim.set(this, ScreenDim.level(this) + direction * step);
                show.run();
            }
            return true;
        });
        dim.setOnClickListener(v -> {
            if (!ScreenDim.available()) return;
            if (!wide) {
                v.setActivated(!v.isActivated());
                return;
            }
            ScreenDim.toggle(this);
            show.run();
        });
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

    private void open(Intent target) {
        if (target == null) return;
        try {
            startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
