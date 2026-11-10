package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;

/**
 * The launcher's Settings menu (this fork), opened by the top bar's Settings button: categories down the left,
 * the focused category's settings on the right, in Home's look (black, a soft glow, Outfit, frosted pills that turn
 * white with focus). Every change is saved in Config.SETTINGS_NAME at once and counted in
 * Config.SETTINGS_REVISION_KEY, so Home rebuilds with it when it comes back, as with Launcher settings.
 */
public final class SettingsActivity extends Activity {

    /** The category to open on (the language picker reopens the menu on System). */
    static final String EXTRA_CATEGORY = "category";
    static final String GRID_COLUMNS_KEY = "grid_columns";
    static final String CONTINUE_WATCHING_KEY = "continue_watching";
    static final String GREETING_MODE_KEY = "greeting_mode";
    static final String GREETING_TIME = "time", GREETING_CUSTOM = "custom", GREETING_OFF = "off";
    static final String GREETINGS_KEY = "greetings";
    static final String GREETING_MINUTES_KEY = "greeting_minutes";
    static final String GREETING_NAME_KEY = "greeting_name";

    private static final int[] DOCK_OPACITIES = {100, 70, 50, 30};
    /** 0: the next greeting each time Home comes back (MainActivity counts the visits in GREETING_TURN_KEY). */
    private static final int[] GREETING_MINUTES = {0, 5, 15, 30, 60};
    static final String GREETING_TURN_KEY = "greeting_turn";
    private static final int INK = 0xFFFFFFFF, INK_FOCUSED = 0xFF111111;

    /** The custom greetings, one per line in the preference. */
    static List<String> greetings(SharedPreferences prefs) {
        List<String> out = new ArrayList<>();
        for (String line : prefs.getString(GREETINGS_KEY, "").split("\n")) {
            if (!line.trim().isEmpty()) out.add(line.trim());
        }
        return out;
    }

    private SharedPreferences prefs;
    /** Accent: the highlight colour this menu and Home use; 0 is white. */
    private int accent;
    /** The System > Updates page is on screen (a finished check redraws it). */
    private boolean updatesOpen;
    /** Highlight color Adaptive: Home follows each app's colour; this menu stays white. */
    private boolean adaptive;
    private Config config;
    private float density;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final List<TextView> categoryButtons = new ArrayList<>();
    private TextView paneTitle;
    private ScrollView scroll;
    private LinearLayout pane;
    private int category = -1;
    /** What Back does on a page inside a category (the city search, a text field): return to the category. */
    private Runnable back;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        accent = Accent.get(this);
        adaptive = Accent.adaptive(this);
        config = Config.load(this);
        density = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        // Home's ambient glow, a calm blue behind the menu.
        View glow = new View(this);
        GradientDrawable glowShape = new GradientDrawable();
        glowShape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowShape.setColors(new int[]{0x402B5CFF, 0x142B5CFF, 0x002B5CFF});
        glowShape.setGradientCenter(0.18f, 0.2f);
        glow.setBackground(glowShape);
        glow.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> glowShape.setGradientRadius((r - l) * 0.7f));
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        columns.setPadding(dp(56), dp(36), dp(56), 0);
        root.addView(columns, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        columns.addView(left, new LinearLayout.LayoutParams(dp(220), -1));
        // This fork: a smaller header (no "LAUNCHER" line), so the categories and rows get the room.
        TextView title = text(32, R.string.font_light, INK);
        title.setText(R.string.sm_title);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-2, -2);
        titleLp.bottomMargin = dp(20);
        left.addView(title, titleLp);
        int[] names = {R.string.sm_home, R.string.sm_themes, R.string.sm_weather, R.string.sm_clock, R.string.sm_storage,
                R.string.sm_system};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            TextView b = pill(getString(names[i]));
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(dp(22), 0, dp(22), 0);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            b.setOnFocusChangeListener((v, focused) -> {
                // keepPage: a page redrawing itself (Quick settings) let focus land here for a moment.
                if (focused && !keepPage) showCategory(index);
            });
            b.setOnClickListener(v -> focusPane());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(40));
            lp.bottomMargin = dp(8);
            left.addView(b, lp);
            categoryButtons.add(b);
            // The offline build has no weather; the button stays in the list, hidden, so the pages keep their numbers.
            if (BuildConfig.OFFLINE && names[i] == R.string.sm_weather) b.setVisibility(View.GONE);
        }

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, -1, 1f);
        rightLp.setMarginStart(dp(48));
        columns.addView(right, rightLp);
        paneTitle = text(30, R.string.font_light, INK);
        LinearLayout.LayoutParams paneTitleLp = new LinearLayout.LayoutParams(-2, -2);
        paneTitleLp.topMargin = dp(18);
        paneTitleLp.bottomMargin = dp(14);
        right.addView(paneTitle, paneTitleLp);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setClipChildren(false);
        // Room at the sides for a focused theme card's zoom and ring; the right column still clips the scroll area,
        // so content scrolled away stays off the title.
        scroll.setPadding(dp(16), dp(12), dp(16), dp(40));
        pane = new LinearLayout(this);
        pane.setOrientation(LinearLayout.VERTICAL);
        pane.setClipChildren(false);
        pane.setClipToPadding(false);
        scroll.addView(pane, new FrameLayout.LayoutParams(-1, -2));
        right.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        // This fork: the notes of the focused row, in one line under the page instead of paragraphs between rows.
        hint = text(14, R.string.font_regular, 0x99FFFFFF);
        hint.setLineSpacing(dp(2), 1f);
        hint.setMaxLines(3);
        hint.setEllipsize(TextUtils.TruncateAt.END);
        hint.setMinLines(2);
        hint.setPadding(dp(16), dp(8), dp(16), dp(18));
        hint.setVisibility(View.INVISIBLE);
        right.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        pane.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> showHint(newFocus));

        setContentView(root);
        // The Themes page checks GitHub only once focus is in it, not when the category is merely passed over.
        root.getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (category == 1 && back == null && now != null && isInside(now, pane)) onThemesEntered();
        });
        int start = Math.max(0, Math.min(categoryButtons.size() - 1, getIntent().getIntExtra(EXTRA_CATEGORY, 0)));
        categoryButtons.get(start).requestFocus();
    }

    @Override
    protected void onDestroy() {
        hideScreensaverPreview();
        super.onDestroy();
        background.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (back != null) {
            Runnable r = back;
            back = null;
            r.run();
            return;
        }
        super.onBackPressed();
    }

    /**
     * Left from the settings returns to the category they belong to, rather than whichever category the focus finder
     * reaches; Up and Down stop at the ends of the category list.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // A Quick settings tile being moved takes every arrow itself (showQuickSettings).
        if (qsMoving != null) return super.dispatchKeyEvent(event);
        View focus = getCurrentFocus();
        if (event.getAction() == KeyEvent.ACTION_DOWN && focus != null) {
            int key = event.getKeyCode();
            if (key == KeyEvent.KEYCODE_DPAD_LEFT && isInside(focus, pane) && !(focus instanceof EditText)
                    && !(focus instanceof ColorSlider)
                    && FocusFinder.getInstance().findNextFocus(pane, focus, View.FOCUS_LEFT) == null) {
                if (category >= 0) categoryButtons.get(category).requestFocus();
                return true;
            }
            // Down past a page's last row (Up past its first) stays on the page instead of landing on a category in
            // the list on the left, which would switch the page. Left goes to the list.
            if ((key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) && isInside(focus, pane)
                    && FocusFinder.getInstance().findNextFocus(pane, focus,
                    key == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : View.FOCUS_UP) == null) {
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) scroll.fullScroll(View.FOCUS_DOWN);
                else scroll.smoothScrollTo(0, 0);
                focus.requestFocus();
                return true;
            }
            if (categoryButtons.contains(focus)) {
                int i = categoryButtons.indexOf(focus);
                if (key == KeyEvent.KEYCODE_DPAD_UP && i == 0) return true;
                if (key == KeyEvent.KEYCODE_DPAD_DOWN && i == categoryButtons.size() - 1) return true;
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    focusPane();
                    return true;
                }
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isInside(View v, ViewGroup group) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) {
            if (p == group) return true;
        }
        return false;
    }

    private void focusPane() {
        View first = firstFocusable(pane);
        if (first != null) first.requestFocus();
    }

    private static View firstFocusable(View v) {
        if (v.getVisibility() != View.VISIBLE) return null;
        if (v.isFocusable()) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = firstFocusable(g.getChildAt(i));
                if (f != null) return f;
            }
        }
        return null;
    }

    // ---- categories ----------------------------------------------------------------------------------

    private void showCategory(int index) {
        showCategory(index, false);
    }

    private void showCategory(int index, boolean force) {
        if (index == category && back == null && !force) return;
        back = null;
        updatesOpen = false;
        category = index;
        for (int i = 0; i < categoryButtons.size(); i++) categoryButtons.get(i).setSelected(i == index);
        clearPane();
        scroll.scrollTo(0, 0);
        switch (index) {
            case 0: buildHome(); break;
            case 1: buildThemes(); break;
            case 2: buildWeather(); break;
            case 3: buildClock(); break;
            case 4: buildStorage(); break;
            default: buildSystem(); break;
        }
    }

    /** Builds the category again in place, at the same scroll position. */
    private void rebuild(int index) {
        int scrollY = scroll.getScrollY();
        showCategory(index, true);
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    /**
     * Empties the right side. A focused view removed with it would send focus to the first category (and so to the
     * Home page): park it on this category's button first, which shows nothing new while back is null.
     */
    private void clearPane() {
        if (pane.hasFocus() && category >= 0) categoryButtons.get(category).requestFocus();
        hideScreensaverPreview();
        pane.removeAllViews();
    }

    private void buildHome() {
        paneTitle.setText(R.string.sm_home);
        section(R.string.sm_layout);
        int dock = config.columns;
        pane.addView(choiceRow(getString(R.string.sm_dock_apps), numbers(1, 6), dock - 1, i -> {
            prefs.edit().putInt("columns", i + 1).apply();
            changed();
        }));
        int grid = prefs.getInt(GRID_COLUMNS_KEY, getResources().getInteger(R.integer.grid_columns));
        pane.addView(choiceRow(getString(R.string.sm_grid_apps), numbers(2, 6), grid - 2, i -> {
            prefs.edit().putInt(GRID_COLUMNS_KEY, i + 2).apply();
            changed();
        }));
        pane.addView(toggleRow(getString(R.string.sm_continue_watching), prefs.getBoolean(CONTINUE_WATCHING_KEY, true),
                on -> {
                    prefs.edit().putBoolean(CONTINUE_WATCHING_KEY, on).apply();
                    changed();
                }));
        // This fork: popular titles from Nuvio's catalog behind Home while the dock has focus (not in the offline build).
        if (!BuildConfig.OFFLINE) {
            pane.addView(toggleRow(getString(R.string.sm_nuvio_suggestions), NuvioSuggestions.enabled(this), on -> {
                prefs.edit().putBoolean(NuvioSuggestions.KEY, on).apply();
                changed();
            }));
            note(getString(R.string.sm_nuvio_suggestions_note));
        }
        section(R.string.sm_look);
        // This fork: a smaller clock, the date and the weather on one line, round icon pills.
        pane.addView(toggleRow(getString(R.string.sm_compact_top), config.compactTop, on -> {
            prefs.edit().putBoolean(Config.COMPACT_TOP_KEY, on).apply();
            changed();
        }));
        // This fork: the focused dock app's name above the dock (config.xml / theme dock_title).
        pane.addView(toggleRow(getString(R.string.sm_app_name), config.dockTitle, on -> {
            prefs.edit().putBoolean(Config.DOCK_TITLE_KEY, on).apply();
            changed();
        }));
        String[] opacities = new String[DOCK_OPACITIES.length];
        int current = 0;
        for (int i = 0; i < DOCK_OPACITIES.length; i++) {
            opacities[i] = DOCK_OPACITIES[i] + "%";
            if (DOCK_OPACITIES[i] == config.dockOpacity) current = i;
        }
        pane.addView(choiceRow(getString(R.string.sm_dock_band), opacities, current, i -> {
            prefs.edit().putInt(Config.DOCK_OPACITY_KEY, DOCK_OPACITIES[i]).apply();
            changed();
        }));
        pane.addView(toggleRow(getString(R.string.sm_animations), prefs.getBoolean("animations", config.animations), on -> {
            SharedPreferences.Editor e = prefs.edit().putBoolean("animations", on);
            if (!on) e.putBoolean("advanced_effects", false);
            e.apply();
            changed();
        }));
        section(R.string.sm_highlight);
        note(getString(R.string.sm_highlight_note));
        pane.addView(swatchRow());
        boolean preset = false;
        for (int c : Accent.PRESETS) preset |= c == accent;
        preset |= adaptive;
        pane.addView(actionRow(getString(R.string.sm_custom_color), preset ? "" : Accent.hex(accent), () ->
                showColorPicker(R.string.sm_custom_color, accent == 0 ? 0xFF0A84FF : accent, this::applyAccent, () -> {
                    back = null;
                    rebuild(0);
                    focusPane();
                })));
        section(R.string.sm_apps);
        pane.addView(actionRow(getString(R.string.sm_reorder), "", () -> {
            setResult(RESULT_OK, new Intent().putExtra(LauncherSettingsActivity.EXTRA_REORDER, true));
            finish();
        }));
        pane.addView(actionRow(getString(R.string.sm_more_options), "", () ->
                open(new Intent(this, LauncherSettingsActivity.class))));
    }

    /** Whether this visit to Settings has checked the theme list against GitHub yet (once, on entering the page). */
    private boolean themesChecked;
    private boolean themesChecking;
    private String themesStatus;
    private TextView themesStatusView;
    /** The cards on the Themes page and their image views, to load newer previews into after the check. */
    private final List<ThemeLibrary.Theme> cardThemes = new ArrayList<>();
    private final List<ImageView> cardImages = new ArrayList<>();
    private int cardWidth;
    /** Each card's focusable part and progress overlay by theme, and the No theme row, to come back to after applying. */
    private final java.util.Map<String, View> themeHolders = new java.util.HashMap<>();
    private final java.util.Map<String, TextView> themeProgress = new java.util.HashMap<>();
    private boolean applyingTheme;

    private void buildThemes() {
        if (BuildConfig.OFFLINE) {
            buildThemesOffline();
            return;
        }
        paneTitle.setText(R.string.sm_themes);
        // At the top: where the list stands with GitHub, in bold, and Refresh to check now.
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        themesStatusView = text(17, R.string.font_medium, INK);
        themesStatusView.setText(themesStatus != null ? themesStatus : getString(R.string.sm_themes_saved));
        statusRow.addView(themesStatusView, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView refresh = pill(getString(R.string.sm_refresh_themes));
        refresh.setOnClickListener(v -> checkThemes(true));
        refresh.setOnFocusChangeListener((v, focused) -> {
            if (focused) scrollIntoView(statusRow);
        });
        statusRow.addView(refresh, new LinearLayout.LayoutParams(-2, dp(44)));
        pane.addView(statusRow);
        note(getString(R.string.sm_themes_note));
        cardThemes.clear();
        cardImages.clear();
        themeHolders.clear();
        themeProgress.clear();
        // Only what is saved on the TV here: passing over the category in the list fetches nothing. The check with
        // GitHub starts when focus goes into the page (onThemesEntered).
        List<ThemeLibrary.Theme> shown = ThemeLibrary.list(this);
        String current = ThemeLibrary.current(this);
        // Downloaded: Default first (the launcher's own look, which the repo's "default" theme writes out), unless it
        // was deleted, then the themes applied before and kept on the TV: switching back to one needs no download.
        List<ThemeLibrary.Theme> kept = new ArrayList<>();
        if (!prefs.getBoolean(DEFAULT_HIDDEN_KEY, false)) kept.add(defaultTheme(shown));
        for (ThemeLibrary.Theme t : ThemeLibrary.downloaded(this)) if (!isDefault(t)) kept.add(t);
        if (!kept.isEmpty()) {
            section(R.string.sm_downloaded);
            note(getString(R.string.sm_downloaded_note));
            themeGrid(kept, true, current);
        }
        section(R.string.sm_all_themes);
        themeGrid(shown, false, current);
    }

    /** The offline build: no list from GitHub, only the themes on this TV (kept, or copied there by the owner). */
    private void buildThemesOffline() {
        paneTitle.setText(R.string.sm_themes);
        cardThemes.clear();
        cardImages.clear();
        themeHolders.clear();
        themeProgress.clear();
        String current = ThemeLibrary.current(this);
        List<ThemeLibrary.Theme> onTv = new ArrayList<>();
        if (!prefs.getBoolean(DEFAULT_HIDDEN_KEY, false)) onTv.add(defaultTheme(ThemeLibrary.list(this)));
        for (ThemeLibrary.Theme t : ThemeLibrary.downloaded(this)) if (!isDefault(t)) onTv.add(t);
        section(R.string.sm_themes_on_tv);
        note(getString(R.string.sm_themes_added_note));
        themeGrid(onTv, true, current);
    }

    /** Theme cards three to a row; downloaded: the cards of the Downloaded section. */
    private void themeGrid(List<ThemeLibrary.Theme> themes, boolean downloaded, String current) {
        int perRow = 3;
        int cardW = (getResources().getDisplayMetrics().widthPixels - dp(56 + 56 + 250 + 48 + 32) - dp(24) * (perRow - 1)) / perRow;
        int cardH = Math.round(cardW * 9f / 16f);
        LinearLayout row = null;
        for (int i = 0; i < themes.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);   // a focused card zooms and rings past its row
                row.setClipToPadding(false);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(18);
                pane.addView(row, lp);
            }
            ThemeLibrary.Theme theme = themes.get(i);
            View card = themeCard(theme, cardW, cardH, isCurrent(theme, current), downloaded);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, -2);
            if (i % perRow != 0) lp.setMarginStart(dp(24));
            row.addView(card, lp);
        }
    }

    /**
     * Focus went into the Themes page: check the list against GitHub, once per visit to Settings (ThemeLibrary keeps
     * it to a conditional call at most every few minutes). A changed list redraws the page; otherwise only the
     * previews GitHub has a newer version of are fetched and swapped in.
     */
    private void onThemesEntered() {
        if (BuildConfig.OFFLINE) return;   // nothing to check with GitHub
        if (!themesChecked) checkThemes(false);
    }

    /** force: the Refresh button, which checks now even if the list was checked in the last few minutes. */
    private void checkThemes(boolean force) {
        if (themesChecking) return;
        themesChecking = true;
        themesChecked = true;
        themesStatus = getString(R.string.sm_themes_checking);
        if (themesStatusView != null) themesStatusView.setText(themesStatus);
        String before = signature(new ArrayList<>(cardThemes));
        background.execute(() -> {
            List<ThemeLibrary.Theme> fresh = null;
            try {
                fresh = ThemeLibrary.refresh(this, force);
            } catch (java.io.IOException e) {
                android.util.Log.d(ThemeLibrary.TAG, "themes not refreshed: " + e);
            }
            List<ThemeLibrary.Theme> result = fresh;
            main.post(() -> {
                themesChecking = false;
                if (isFinishing()) return;
                themesStatus = getString(result != null ? R.string.sm_themes_current : R.string.sm_themes_offline);
                if (category != 1 || back != null) return;
                if (result != null && !signature(result).equals(before)) {
                    boolean inPane = pane.hasFocus();
                    rebuild(1);
                    if (inPane) focusPane();
                    return;
                }
                if (themesStatusView != null) themesStatusView.setText(themesStatus);
                if (result == null) return;
                for (int i = 0; i < cardThemes.size(); i++) {
                    ThemeLibrary.Theme theme = cardThemes.get(i);
                    ImageView image = cardImages.get(i);
                    if (!ThemeLibrary.previewStale(this, theme)) continue;
                    background.execute(() -> {
                        Bitmap b = ThemeLibrary.preview(this, theme, cardWidth, true);
                        if (b != null) main.post(() -> image.setImageBitmap(b));
                    });
                }
            });
        });
    }

    /** What the page shows of a list: the themes and the versions of their previews. */
    private static String signature(List<ThemeLibrary.Theme> themes) {
        StringBuilder sb = new StringBuilder();
        for (ThemeLibrary.Theme t : themes) sb.append(t.name).append(':').append(t.shotSha).append(';');
        return sb.toString();
    }

    /**
     * A theme's card: the preview, with a white ring around it while focused. The ring is a layer of its own around
     * the card rather than a stroke on it: a stroke on the preview sat half inside its rounded clip and was cut, and
     * the zoomed card was clipped by its row.
     */
    private View themeCard(ThemeLibrary.Theme theme, int w, int h, boolean current, boolean downloaded) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        card.setClipToPadding(false);
        FrameLayout holder = new FrameLayout(this);
        holder.setFocusable(true);
        holder.setClickable(true);
        holder.setClipChildren(false);
        holder.setClipToPadding(false);
        FrameLayout frame = new FrameLayout(this);
        float radius = dp(14);
        GradientDrawable placeholder = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF26262B, 0xFF16161A});
        placeholder.setCornerRadius(radius);
        frame.setBackground(placeholder);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        frame.setClipToOutline(true);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        if (current) {
            TextView badge = text(11, R.string.font_medium, INK_FOCUSED);
            badge.setText(getString(R.string.sm_theme_current).toUpperCase(Locale.getDefault()));
            badge.setLetterSpacing(0.1f);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF5FFFFFF);
            bg.setCornerRadius(dp(10));
            badge.setBackground(bg);
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
            lp.setMargins(0, dp(8), dp(8), 0);
            frame.addView(badge, lp);
        }
        // At rest, a faint edge (brighter on the current theme), drawn inside the preview.
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(radius);
        rest.setStroke(Math.max(1, dp(1)), current ? 0xB3FFFFFF : 0x1FFFFFFF);
        frame.setForeground(rest);
        // Shown on the card while its theme is applied: "Downloading wallpaper 3 of 12…".
        TextView progress = text(15, R.string.font_medium, INK);
        progress.setGravity(Gravity.CENTER);
        progress.setBackgroundColor(0xB3000000);
        progress.setVisibility(View.GONE);
        frame.addView(progress, new FrameLayout.LayoutParams(-1, -1));
        holder.addView(frame, new FrameLayout.LayoutParams(-1, -1));
        // The focus ring, a few dp outside the preview all round, with its corners following the preview's.
        int gap = dp(4);
        View ring = new View(this);
        GradientDrawable ringShape = new GradientDrawable();
        ringShape.setCornerRadius(radius + gap);
        ringShape.setStroke(dp(3), Accent.ring(accent));
        ring.setBackground(ringShape);
        ring.setAlpha(0f);
        FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(-1, -1);
        ringLp.setMargins(-gap, -gap, -gap, -gap);
        holder.addView(ring, ringLp);
        card.addView(holder, new LinearLayout.LayoutParams(w, h));
        TextView name = text(15, R.string.font_regular, 0xB3FFFFFF);
        name.setText(theme.title());
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-2, -2);
        nameLp.topMargin = dp(12);
        nameLp.setMarginStart(dp(4));
        card.addView(name, nameLp);
        holder.setOnFocusChangeListener((v, focused) -> {
            ring.animate().alpha(focused ? 1f : 0f).setDuration(150).start();
            v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(150).start();
            v.setTranslationZ(focused ? 1f : 0f);
            name.setTextColor(focused ? INK : 0xB3FFFFFF);
            if (focused) scrollIntoView(card);
        });
        String key = cardKey(theme.name, downloaded);
        holder.setOnClickListener(v -> applyTheme(theme, downloaded));
        if (downloaded) {
            // Delete the copy kept on the TV: the Delete pill under the card, or hold OK. The theme in use cannot be
            // deleted (its pill reads In use); Default is only taken out of this section, and comes back when it is
            // applied from All themes.
            Runnable delete = () -> {
                if (current) return;
                if (isDefault(theme)) prefs.edit().putBoolean(DEFAULT_HIDDEN_KEY, true).apply();
                else ThemeLibrary.removeDownloaded(this, theme.name);
                Toast.makeText(this, getString(R.string.sm_downloaded_removed, theme.title()), Toast.LENGTH_SHORT).show();
                rebuild(1);
                focusPane();
            };
            holder.setOnLongClickListener(v -> {
                delete.run();
                return true;
            });
            if (!current) {   // the theme in use gets no Delete: its Current badge says why
                TextView deletePill = pill(getString(R.string.sm_delete));
                deletePill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                deletePill.setOnClickListener(v -> delete.run());
                deletePill.setOnFocusChangeListener((v, focused) -> {
                    if (focused) scrollIntoView(card);
                });
                LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(-2, dp(34));
                deleteLp.topMargin = dp(8);
                card.addView(deletePill, deleteLp);
            }
        } else {
            cardThemes.add(theme);
            cardImages.add(image);
        }
        themeHolders.put(key, holder);
        themeProgress.put(key, progress);
        cardWidth = w;
        // After this visit's check the newest previews are fetched as the page is drawn; before it, the saved ones.
        boolean fetch = !downloaded && themesChecked && getString(R.string.sm_themes_current).equals(themesStatus);
        background.execute(() -> {
            Bitmap b = ThemeLibrary.preview(this, theme, w, fetch);
            if (b != null) main.post(() -> image.setImageBitmap(b));
        });
        return card;
    }

    /** Default: the launcher's own look; the repo's "default" theme is that look written out. */
    private static final String DEFAULT_NAME = "default";
    /** Default was deleted from the Downloaded section (it comes back when applied from All themes). */
    private static final String DEFAULT_HIDDEN_KEY = "theme_default_hidden";

    private static boolean isDefault(ThemeLibrary.Theme t) {
        return DEFAULT_NAME.equals(t.name);
    }

    /** No theme pushed counts as Default. */
    private static boolean isCurrent(ThemeLibrary.Theme t, String current) {
        return t.name.equals(current) || isDefault(t) && current.isEmpty();
    }

    /** Default's card: the repo's entry when the list has it (for its preview), else one by name alone. */
    private static ThemeLibrary.Theme defaultTheme(List<ThemeLibrary.Theme> all) {
        for (ThemeLibrary.Theme t : all) if (isDefault(t)) return t;
        return new ThemeLibrary.Theme(DEFAULT_NAME, new ArrayList<>(), null, null);
    }

    /**
     * Applied in place: the card shows the download's progress, and the page stays where it is, with focus back on
     * the same card and its Current badge, once the theme is in.
     */
    private static String cardKey(String name, boolean downloaded) {
        return (downloaded ? "kept:" : "all:") + name;
    }

    /** downloaded: a theme kept on the TV, applied from that copy with no download. */
    private void applyTheme(ThemeLibrary.Theme theme, boolean downloaded) {
        if (applyingTheme) return;
        applyingTheme = true;
        String title = theme.title();
        // Default is the launcher's own look: nothing to download, and it goes back into Downloaded.
        boolean toDefault = isDefault(theme);
        if (toDefault) prefs.edit().remove(DEFAULT_HIDDEN_KEY).apply();
        // After a download the card to come back to is the theme's new card in Downloaded.
        String key = cardKey(theme.name, true);
        TextView progress = themeProgress.get(cardKey(theme.name, downloaded));
        if (progress != null) {
            progress.setText(getString(R.string.sm_applying, title));
            progress.setVisibility(View.VISIBLE);
        }
        background.execute(() -> {
            String error = null;
            try {
                if (toDefault) ThemeLibrary.reset(this);
                else if (downloaded) ThemeLibrary.applyDownloaded(this, theme);
                else ThemeLibrary.apply(this, theme, (done, total) -> main.post(() -> {
                    if (progress != null && total > 0) {
                        progress.setText(getString(R.string.sm_downloading, Math.min(done + 1, total), total));
                    }
                }));
            } catch (java.io.IOException e) {
                android.util.Log.w(ThemeLibrary.TAG, "theme not applied: " + e);
                error = getString(R.string.sm_theme_failed);
            }
            String failure = error;
            main.post(() -> {
                applyingTheme = false;
                if (isFinishing()) return;
                setResult(RESULT_OK);
                config = Config.load(this);
                if (failure == null) Toast.makeText(this, getString(R.string.sm_theme_applied, title), Toast.LENGTH_SHORT).show();
                else Toast.makeText(this, failure, Toast.LENGTH_LONG).show();
                // Left the page meanwhile: the new Current badge shows the next time it is opened.
                if (category != 1 || back != null) return;
                boolean inPane = pane.hasFocus();
                rebuild(1);
                if (!inPane) return;
                View again = themeHolders.get(key);
                if (again == null) again = themeHolders.get(cardKey(theme.name, false));
                if (again != null) again.requestFocus();
                else focusPane();
            });
        });
    }

    private void buildWeather() {
        paneTitle.setText(R.string.sm_weather);
        pane.addView(toggleRow(getString(R.string.sm_show_weather), Weather.enabled(this), on -> {
            prefs.edit().putBoolean(Weather.SHOW_KEY, on).apply();
            changed();
        }));
        boolean celsius = "c".equals(prefs.getString(Weather.UNITS_KEY, "f"));
        pane.addView(choiceRow(getString(R.string.sm_units),
                new String[]{getString(R.string.sm_fahrenheit), getString(R.string.sm_celsius)}, celsius ? 1 : 0, i -> {
                    prefs.edit().putString(Weather.UNITS_KEY, i == 1 ? "c" : "f").apply();
                    changed();
                }));
        pane.addView(toggleRow(getString(R.string.sm_conditions), prefs.getBoolean(Weather.CONDITIONS_KEY, true), on -> {
            prefs.edit().putBoolean(Weather.CONDITIONS_KEY, on).apply();
            changed();
        }));
        pane.addView(toggleRow(getString(R.string.sm_hilo), prefs.getBoolean(Weather.HILO_KEY, true), on -> {
            prefs.edit().putBoolean(Weather.HILO_KEY, on).apply();
            changed();
        }));
        String place = prefs.getString(Weather.PLACE_KEY, "");
        pane.addView(actionRow(getString(R.string.sm_location),
                place.isEmpty() ? getString(R.string.sm_location_auto) : place, this::showCitySearch));
        pane.addView(actionRow(getString(R.string.sm_refresh), "", () -> {
            Weather.invalidate(this);
            changed();
            Toast.makeText(this, R.string.sm_refreshing, Toast.LENGTH_SHORT).show();
        }));
    }

    private void showCitySearch() {
        clearPane();
        paneTitle.setText(R.string.sm_find_city);
        back = () -> rebuild(2);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        EditText field = field(getString(R.string.sm_city_hint), "");
        bar.addView(field, new LinearLayout.LayoutParams(0, dp(52), 1f));
        TextView search = pill(getString(R.string.sm_search));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(-2, dp(48));
        searchLp.setMarginStart(dp(12));
        bar.addView(search, searchLp);
        pane.addView(bar);
        TextView status = text(16, R.string.font_regular, 0x99FFFFFF);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(14);
        pane.addView(status, statusLp);
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        pane.addView(results);
        pane.addView(actionRow(getString(R.string.sm_use_auto), getString(R.string.sm_location_auto_detail), () -> {
            prefs.edit().remove(Weather.PLACE_KEY).remove(Weather.LAT_KEY).remove(Weather.LON_KEY).apply();
            Weather.invalidate(this);
            changed();
            back = null;
            rebuild(2);
            focusPane();
        }));
        Runnable run = () -> {
            String query = field.getText().toString().trim();
            if (query.isEmpty()) return;
            hideKeyboard(field);
            status.setText(R.string.sm_searching);
            results.removeAllViews();
            background.execute(() -> {
                List<Weather.Place> found = null;
                try {
                    found = Weather.search(query);
                } catch (java.io.IOException | org.json.JSONException e) {
                    android.util.Log.d("TvHomeWeather", "lookup failed: " + e);
                }
                List<Weather.Place> places = found;
                main.post(() -> {
                    if (places == null) {
                        status.setText(R.string.sm_lookup_failed);
                        return;
                    }
                    status.setText(places.isEmpty() ? getString(R.string.sm_no_places) : "");
                    for (Weather.Place p : places) {
                        results.addView(actionRow(p.name, p.detail(), () -> {
                            prefs.edit().putString(Weather.PLACE_KEY, p.label())
                                    .putString(Weather.LAT_KEY, Double.toString(p.lat))
                                    .putString(Weather.LON_KEY, Double.toString(p.lon)).apply();
                            Weather.invalidate(this);
                            changed();
                            back = null;
                            rebuild(2);
                            focusPane();
                        }));
                    }
                    View first = firstFocusable(results);
                    if (first != null) first.requestFocus();
                });
            });
        };
        search.setOnClickListener(v -> run.run());
        field.setOnEditorActionListener((v, action, event) -> {
            run.run();
            return true;
        });
        field.requestFocus();
    }

    private void buildClock() {
        paneTitle.setText(R.string.sm_clock);
        String[] formats = {"system", "12", "24"};
        String format = prefs.getString(Config.CLOCK_FORMAT_KEY, config.clockFormat);
        int formatIndex = Math.max(0, java.util.Arrays.asList(formats).indexOf(format));
        pane.addView(choiceRow(getString(R.string.sm_time_format), new String[]{getString(R.string.sm_system_default),
                getString(R.string.sm_12h), getString(R.string.sm_24h)}, formatIndex, i -> {
            prefs.edit().putString(Config.CLOCK_FORMAT_KEY, formats[i]).apply();
            changed();
        }));
        // This fork: where the screensaver puts the clock, the date and the weather, their sizes, the glow, when it starts.
        pane.addView(actionRow(getString(R.string.sm_screensaver), "", this::showScreensaver));
        String[] modes = {GREETING_TIME, GREETING_CUSTOM, GREETING_OFF};
        int modeIndex = Math.max(0, java.util.Arrays.asList(modes).indexOf(prefs.getString(GREETING_MODE_KEY, GREETING_TIME)));
        pane.addView(choiceRow(getString(R.string.sm_greeting), new String[]{getString(R.string.sm_greeting_time),
                getString(R.string.sm_greeting_custom), getString(R.string.sm_off)}, modeIndex, i -> {
            prefs.edit().putString(GREETING_MODE_KEY, modes[i]).apply();
            changed();
        }));
        String name = prefs.getString(GREETING_NAME_KEY, config.greetingName);
        pane.addView(actionRow(getString(R.string.sm_name), name.isEmpty() ? getString(R.string.sm_none) : name, () ->
                editText(getString(R.string.sm_name), getString(R.string.sm_name_hint), name, true, value -> {
                    prefs.edit().putString(GREETING_NAME_KEY, value).apply();
                    changed();
                })));
        section(R.string.sm_custom_greetings);
        note(getString(R.string.sm_custom_note));
        List<String> lines = greetings(prefs);
        for (int i = 0; i < lines.size(); i++) {
            final int index = i;
            pane.addView(choiceRow("“" + lines.get(i) + "”", new String[]{getString(R.string.sm_remove)}, -1, x -> {
                List<String> now = greetings(prefs);
                if (index < now.size()) now.remove(index);
                prefs.edit().putString(GREETINGS_KEY, TextUtils.join("\n", now)).apply();
                changed();
                rebuild(3);
                focusPane();
            }));
        }
        pane.addView(actionRow("+  " + getString(R.string.sm_add_greeting), "", () ->
                editText(getString(R.string.sm_add_greeting), getString(R.string.sm_greeting_hint), "", false, value -> {
                    if (value.isEmpty()) return;
                    List<String> now = greetings(prefs);
                    now.add(value.replace('\n', ' '));
                    SharedPreferences.Editor e = prefs.edit().putString(GREETINGS_KEY, TextUtils.join("\n", now));
                    // A first custom greeting is meant to be seen: switch the greeting to Custom with it.
                    if (now.size() == 1) e.putString(GREETING_MODE_KEY, GREETING_CUSTOM);
                    e.apply();
                    changed();
                })));
        String[] every = new String[GREETING_MINUTES.length];
        int minutes = prefs.getInt(GREETING_MINUTES_KEY, 5), everyIndex = 1;
        for (int i = 0; i < GREETING_MINUTES.length; i++) {
            every[i] = GREETING_MINUTES[i] == 0 ? getString(R.string.sm_each_visit)
                    : getString(R.string.sm_minutes, GREETING_MINUTES[i]);
            if (GREETING_MINUTES[i] == minutes) everyIndex = i;
        }
        pane.addView(choiceRow(getString(R.string.sm_change_every), every, everyIndex, i -> {
            prefs.edit().putInt(GREETING_MINUTES_KEY, GREETING_MINUTES[i]).apply();
            changed();
        }));
    }

    // ---- storage -------------------------------------------------------------------------------------------

    /** Bytes in a file or folder tree. */
    private static long size(java.io.File f) {
        if (f == null || !f.exists()) return 0;
        if (f.isFile()) return f.length();
        long total = 0;
        java.io.File[] children = f.listFiles();
        if (children != null) for (java.io.File c : children) total += size(c);
        return total;
    }

    private static void delete(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] children = f.listFiles();
        if (children != null) for (java.io.File c : children) delete(c);
        f.delete();
    }

    private String bytes(long n) {
        return android.text.format.Formatter.formatShortFileSize(this, n);
    }

    /** The bar colours, after iOS's storage page: one per part, in the order the bar draws them. */
    private static final int[] STORAGE_COLORS = {0xFFFF453A, 0xFFFF9F0A, 0xFFFFD60A, 0xFF30D158, 0xFF64D2FF,
            0xFF0A84FF, 0xFFBF5AF2, 0xFFFF375F, 0xFF5E5CE6, 0xFF8E8E93};
    /** The legend's names, one per part, in STORAGE_COLORS' order. */
    private static final int[] STORAGE_LABELS = {R.string.sm_st_app, R.string.sm_seg_wallpapers, R.string.sm_seg_themes,
            R.string.sm_seg_previews, R.string.sm_seg_thumbnails, R.string.sm_st_languages, R.string.sm_seg_settings,
            R.string.sm_seg_update, R.string.sm_seg_temp, R.string.sm_seg_runtime};

    /**
     * Storage, laid out like iOS's: a card for the launcher with one bar split into its parts and a legend under it,
     * then the Languages row (its list removes them one by one), the TV's own storage settings, and clean-up. Every part the launcher keeps has its own name in the legend,
     * so nothing is lumped together; empty parts stay hidden until they hold something. The sizes are read off the main thread; the card fills in
     * when they are.
     */
    private void buildStorage() {
        paneTitle.setText(R.string.sm_storage);
        java.io.File thumbnails = new java.io.File(getCacheDir(), "continue");
        java.io.File themeStore = new java.io.File(getFilesDir(), "themes");

        TextView[] launcherTotal = {null};
        SegmentBar launcherBar = new SegmentBar(this);
        LinearLayout launcherLegend = new LinearLayout(this);
        pane.addView(storageCard(getString(R.string.sm_seg_launcher), launcherTotal, launcherBar, launcherLegend));

        // The downloaded languages (LanguagePacks), as a row that opens their list; they are on the bar as well.
        View languages = actionRow(getString(R.string.sm_st_languages),
                getString(R.string.sm_st_languages_value, 1 + installedLanguages().size()), this::showInstalledLanguages);
        // The row keeps its own height (actionRow's layout params); only the gap above the cards changes.
        ((LinearLayout.LayoutParams) languages.getLayoutParams()).topMargin = dp(18);
        // The offline build has every language built in: nothing downloaded to list or delete.
        if (!BuildConfig.OFFLINE) pane.addView(languages);
        // The TV's own storage page, for everything that is not the launcher.
        pane.addView(actionRow(getString(R.string.sm_android_storage), "", () ->
                open(SystemSettings.intent(this, Settings.ACTION_INTERNAL_STORAGE_SETTINGS))));

        section(R.string.sm_clean_up);
        pane.addView(actionRow(getString(R.string.sm_clear_thumbnails), "", () -> {
            delete(thumbnails);
            Toast.makeText(this, R.string.sm_cleared, Toast.LENGTH_SHORT).show();
            rebuild(4);
            focusPane();
        }));
        pane.addView(actionRow(getString(R.string.sm_delete_downloaded), "", () -> {
            for (ThemeLibrary.Theme t : ThemeLibrary.downloaded(this)) ThemeLibrary.removeDownloaded(this, t.name);
            Toast.makeText(this, R.string.sm_cleared, Toast.LENGTH_SHORT).show();
            rebuild(4);
            focusPane();
        }));
        if (!BuildConfig.OFFLINE) pane.addView(actionRow(getString(R.string.sm_delete_other_languages), "", () -> {
            String inUse = languageInUse();
            for (String tag : installedLanguages()) if (!tag.equals(inUse)) LanguagePacks.delete(this, tag);
            Toast.makeText(this, R.string.sm_cleared, Toast.LENGTH_SHORT).show();
            rebuild(4);
            focusPane();
        }));
        pane.addView(actionRow(getString(R.string.sm_clear_previews), "", () -> {
            // Previews and the saved theme list; the downloaded copies stay. Both come back from GitHub when needed.
            java.io.File[] dirs = themeStore.listFiles();
            if (dirs != null) for (java.io.File d : dirs) {
                if (d.isDirectory()) {
                    java.io.File[] in = d.listFiles();
                    if (in != null) for (java.io.File f : in) if (!f.getName().equals("saved")) delete(f);
                } else {
                    delete(d);
                }
            }
            Toast.makeText(this, R.string.sm_cleared, Toast.LENGTH_SHORT).show();
            rebuild(4);
            focusPane();
        }));

        List<String> packs = installedLanguages();
        background.execute(() -> {
            long app = new java.io.File(getPackageCodePath()).length();
            java.io.File external = Config.dir(this);
            long wallpapers = 0;
            if (external != null) {
                wallpapers = size(new java.io.File(external, Config.FILE_NAME)) + size(new java.io.File(external, Config.WALLPAPER_NAME))
                        + size(new java.io.File(external, "wallpapers"));
            }
            long downloaded = 0, previews = 0;
            java.io.File[] dirs = themeStore.listFiles();
            if (dirs != null) for (java.io.File d : dirs) {
                long all = size(d), kept = size(new java.io.File(d, "saved"));
                downloaded += kept;
                previews += all - kept;
            }
            long thumbs = size(thumbnails);
            long langs = 0;
            for (String tag : packs) langs += LanguagePacks.size(this, tag);
            // Settings and weather: the preferences (every setting, the weather and update state) and the small files
            // Home keeps (app order, library, cards), that is everything in files/ but themes and languages.
            long settings = size(new java.io.File(getDataDir(), "shared_prefs"))
                    + Math.max(0, size(getFilesDir()) - size(themeStore) - size(new java.io.File(getFilesDir(), "languages")));
            // An update's APK stays in the cache after it is installed, until Android or Clean up clears it.
            long update = size(new java.io.File(getCacheDir(), "update.apk"));
            long temp = Math.max(0, size(getCacheDir()) - thumbs - update);
            long data = size(getDataDir()) + size(external);
            // What is left is Android's own for this app: compiled code (code_cache) and its runtime files.
            long runtime = Math.max(0, data - wallpapers - downloaded - previews - thumbs - langs - settings - update - temp);
            long launcher = app + data;
            long[] parts = {app, wallpapers, downloaded, previews, thumbs, langs, settings, update, temp, runtime};
            main.post(() -> {
                if (isFinishing() || category != 4) return;
                launcherTotal[0].setText(bytes(launcher));
                launcherBar.set(launcher, parts, STORAGE_COLORS);
                legend(launcherLegend, STORAGE_LABELS, parts, STORAGE_COLORS);
            });
        });
    }

    /** A rounded card: title on the left, a figure on the right (filled in later), the bar, then its legend. */
    private View storageCard(String title, TextView[] figure, SegmentBar bar, LinearLayout legend) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(26), dp(22), dp(26), dp(20));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0x1AFFFFFF);
        bg.setStroke(Math.max(1, dp(1)), 0x1FFFFFFF);
        bg.setCornerRadius(dp(22));
        card.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(22, R.string.font_regular, INK);
        name.setText(title);
        head.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView value = text(17, R.string.font_regular, 0x8CFFFFFF);
        value.setText(R.string.sm_measuring);
        head.addView(value);
        figure[0] = value;
        card.addView(head);

        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(-1, dp(22));
        barLp.topMargin = dp(16);
        card.addView(bar, barLp);

        legend.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams legendLp = new LinearLayout.LayoutParams(-1, -2);
        legendLp.topMargin = dp(12);
        card.addView(legend, legendLp);
        return card;
    }

    /**
     * The legend under a bar: a dot, the part's name and its size, as many to a line as fit (measured), the rest on
     * the next. A part of 0 bytes is left out until it holds something.
     */
    private void legend(LinearLayout legend, int[] labels, long[] sizes, int[] colors) {
        legend.removeAllViews();
        int room = legend.getWidth() > 0 ? legend.getWidth() : dp(900);
        LinearLayout line = null;
        int inLine = 0, used = 0;
        for (int i = 0; i < labels.length; i++) {
            if (sizes[i] <= 0) continue;   // a part with nothing in it appears once it holds something
            TextView name = text(15, R.string.font_regular, 0xE6FFFFFF);
            name.setText(getString(labels[i]));
            name.setSingleLine(true);
            TextView size = text(13, R.string.font_regular, 0x80FFFFFF);
            size.setText(bytes(sizes[i]));
            size.setSingleLine(true);
            name.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            size.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            int width = dp(11 + 8 + 6) + name.getMeasuredWidth() + size.getMeasuredWidth();
            if (line == null || used + dp(22) + width > room) {
                line = new LinearLayout(this);
                line.setOrientation(LinearLayout.HORIZONTAL);
                line.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                if (legend.getChildCount() > 0) lp.topMargin = dp(8);
                legend.addView(line, lp);
                inLine = 0;
                used = 0;
            }
            used += width + (inLine > 0 ? dp(22) : 0);
            View dot = new View(this);
            android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
            d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            d.setColor(colors[i]);
            dot.setBackground(d);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(11), dp(11));
            if (inLine > 0) dotLp.setMarginStart(dp(22));
            line.addView(dot, dotLp);
            LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(-2, -2);
            nameLp.setMarginStart(dp(8));
            line.addView(name, nameLp);
            LinearLayout.LayoutParams sizeLp = new LinearLayout.LayoutParams(-2, -2);
            sizeLp.setMarginStart(dp(6));
            line.addView(size, sizeLp);
            inLine++;
        }
    }

    /**
     * One bar split into parts, as iOS draws storage: each part in its colour, in order from the left, a thin gap
     * between them, the rest of the whole as an empty track. A part too small to see still gets a sliver.
     */
    private static final class SegmentBar extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        private final android.graphics.Path clip = new android.graphics.Path();
        private long whole;
        private long[] parts = new long[0];
        private int[] colors = new int[0];

        SegmentBar(Context ctx) {
            super(ctx);
        }

        void set(long whole, long[] parts, int[] colors) {
            this.whole = whole;
            this.parts = parts;
            this.colors = colors;
            invalidate();
        }

        @Override protected void onDraw(android.graphics.Canvas c) {
            float w = getWidth(), h = getHeight(), rad = h / 4f;
            float density = getResources().getDisplayMetrics().density;
            r.set(0, 0, w, h);
            paint.setColor(0x26FFFFFF);
            c.drawRoundRect(r, rad, rad, paint);
            if (whole <= 0) return;
            clip.reset();
            clip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW);
            c.save();
            c.clipPath(clip);
            float x = 0, gap = 2 * density, sliver = 3 * density;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i] <= 0) continue;
                float width = Math.max(sliver, w * parts[i] / (float) whole);
                if (x >= w) break;
                paint.setColor(colors[i]);
                c.drawRect(x, 0, Math.min(w, x + width), h, paint);
                x += width + gap;
            }
            c.restore();
        }
    }

    private void buildSystem() {
        paneTitle.setText(R.string.sm_system);
        pane.addView(actionRow(getString(R.string.sm_atv_settings), "", this::showAtvSettings));
        pane.addView(actionRow(getString(R.string.sm_language), languageName(prefs.getString(Config.LOCALE_KEY, config.locale)),
                this::showLanguages));
        Updates.Offer offer = Updates.offer(this);
        // This fork: the panel the remote's Settings button opens, arranged with its own preview.
        pane.addView(actionRow(getString(R.string.sm_qs_title), "", () -> showQuickSettings(null)));
        pane.addView(actionRow(getString(R.string.sm_updates),
                offer != null ? updateStatus(offer) : Updates.installedName(this), this::showUpdates));
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        // Upstream's launcher version, then this fork's own: "Launcher 3.4 · Liquify's fork 0.5.8".
        // The offline build says so: "Launcher 3.4 · Liquify's fork 0.5.9 · Offline".
        if (BuildConfig.OFFLINE) version += " · " + getString(R.string.sm_channel_offline);
        note(getString(R.string.sm_about, getString(R.string.upstream_version), version));
    }

    /**
     * The languages the launcher is translated into, each in its own name: upstream's translations (res/values-*)
     * plus this fork's Japanese. "" follows the TV. The tags resolve to those folders (id → values-in, he → values-iw).
     */
    static final String[][] LANGUAGES = {
            {"en", "English"}, {"es", "Español"}, {"ja", "日本語"},
            {"ar", "العربية"}, {"ast", "Asturianu"}, {"id", "Bahasa Indonesia"}, {"bn", "বাংলা"}, {"ca", "Català"},
            {"cs", "Čeština"}, {"da", "Dansk"}, {"de", "Deutsch"}, {"es-419", "Español (Latinoamérica)"},
            {"fr", "Français"}, {"it", "Italiano"}, {"ko", "한국어"}, {"nl", "Nederlands"}, {"nb", "Norsk bokmål"},
            {"uz", "Oʻzbekcha"}, {"pl", "Polski"}, {"pt-BR", "Português (Brasil)"}, {"pt-PT", "Português (Portugal)"},
            {"ru", "Русский"}, {"sr-Latn", "Srpski (latinica)"}, {"fi", "Suomi"}, {"sv", "Svenska"},
            {"vi", "Tiếng Việt"}, {"tr", "Türkçe"}, {"uk", "Українська"}, {"he", "עברית"}, {"th", "ไทย"},
            {"zh-CN", "简体中文"}, {"zh-TW", "繁體中文"},
    };

    private String languageName(String tag) {
        if (tag == null || tag.trim().isEmpty()) return getString(R.string.sm_language_system);
        for (String[] l : LANGUAGES) if (l[0].equalsIgnoreCase(tag.trim())) return l[1];
        return tag;
    }

    /** The downloaded languages, in the list's order. */
    private List<String> installedLanguages() {
        java.util.Set<String> have = LanguagePacks.installedTags(this);
        List<String> out = new ArrayList<>();
        for (String[] l : LANGUAGES) if (have.contains(l[0])) out.add(l[0]);
        return out;
    }

    /** The downloaded language the launcher shows now, or null for English. */
    private String languageInUse() {
        return LanguagePacks.installedTagFor(this, LauncherLocale.effective(this));
    }

    /**
     * Storage > Languages: English (built in), then each downloaded language with its size; choosing one removes it.
     * The one in use stays, and System > Language downloads any of them again.
     */
    private void showInstalledLanguages() {
        clearPane();
        paneTitle.setText(R.string.sm_st_languages);
        back = () -> rebuild(4);
        note(getString(R.string.sm_st_languages_note));
        String inUse = languageInUse();
        pane.addView(actionRow("English", getString(inUse == null ? R.string.sm_in_use : R.string.sm_built_in), null));
        for (String tag : installedLanguages()) {
            String name = languageName(tag);
            boolean used = tag.equals(inUse);
            pane.addView(actionRow(name, used ? getString(R.string.sm_in_use) : bytes(LanguagePacks.size(this, tag)),
                    used ? null : () -> {
                        LanguagePacks.delete(this, tag);
                        Toast.makeText(this, getString(R.string.sm_lang_removed, name), Toast.LENGTH_SHORT).show();
                        showInstalledLanguages();
                    }));
        }
        focusPane();
    }

    /** A language download in progress (one at a time), so a second press waits. */
    private boolean downloading;

    /**
     * Settings > System > Language: the TV's language first, then Installed and Not installed. Picking an installed
     * one applies it at once; picking one that is not installed downloads it from GitHub and moves it to Installed,
     * where picking it again switches to it.
     */
    private void showLanguages() {
        showLanguages(null);
    }

    /** The list, with focus on focusTag (a language just downloaded), else on the language in use. */
    private void showLanguages(String focusTag) {
        clearPane();
        paneTitle.setText(R.string.sm_language);
        back = () -> rebuild(5);
        if (!BuildConfig.OFFLINE) note(getString(R.string.sm_language_note));   // offline: nothing downloads
        String current = prefs.getString(Config.LOCALE_KEY, config.locale).trim();
        java.util.Set<String> have = LanguagePacks.installedTags(this);
        View focus = null;
        View tv = languageRow("", getString(R.string.sm_language_system), current.isEmpty(), false);
        if (current.isEmpty()) focus = tv;
        pane.addView(tv);
        for (int pass = 0; pass < 2; pass++) {
            boolean installedPass = pass == 0;
            if (BuildConfig.OFFLINE && !installedPass) continue;   // all built in
            section(installedPass ? R.string.sm_lang_installed : R.string.sm_lang_not_installed);
            for (String[] l : LANGUAGES) {
                String tag = l[0];
                boolean installed = LanguagePacks.builtIn(tag) || have.contains(tag);
                if (installed != installedPass) continue;
                boolean chosen = tag.equalsIgnoreCase(current);
                View row = languageRow(tag, l[1], chosen, !installed);
                if (focusTag != null ? tag.equals(focusTag) : chosen) focus = row;
                pane.addView(row);
            }
        }
        View first = focus;
        if (first != null) pane.post(first::requestFocus);
        else focusPane();
    }

    /** One language: picking it applies tag, or, when download, only downloads it (picked again, it applies). */
    private View languageRow(String tag, String name, boolean chosen, boolean download) {
        TextView[] value = {null};
        View row = actionRow(name, chosen ? "✓" : " ", () -> {
            if (downloading) return;
            if (!download) {
                applyLanguage(tag);
                return;
            }
            downloading = true;
            value[0].setText(R.string.sm_lang_downloading);
            LanguagePacks.install(this, tag, ok -> {
                downloading = false;
                if (isFinishing() || isDestroyed()) return;
                if (ok) {
                    Toast.makeText(this, getString(R.string.sm_lang_ready, name), Toast.LENGTH_LONG).show();
                    // Still on this list (not another page): show the language under Installed, focused.
                    if (category == 5 && getString(R.string.sm_language).contentEquals(paneTitle.getText())) showLanguages(tag);
                } else {
                    value[0].setText(R.string.sm_lang_failed_short);
                    Toast.makeText(this, R.string.sm_lang_failed, Toast.LENGTH_LONG).show();
                }
            });
        });
        value[0] = (TextView) ((ViewGroup) row).getChildAt(1);
        return row;
    }

    private void applyLanguage(String tag) {
        prefs.edit().putString(Config.LOCALE_KEY, tag).apply();
        changed();
        // The menu itself takes the language when it is built again (attachBaseContext); it opens on System.
        getIntent().putExtra(EXTRA_CATEGORY, 5);
        recreate();
    }

    /** System > ATV settings: the TV's own settings pages, one row each. */
    private void showAtvSettings() {
        clearPane();
        paneTitle.setText(R.string.sm_atv_settings);
        back = () -> rebuild(5);
        pane.addView(actionRow(getString(R.string.sm_tv_settings), "", () ->
                open(SystemSettings.intent(this, Settings.ACTION_SETTINGS))));
        pane.addView(actionRow(getString(R.string.sm_network), "", () ->
                open(SystemSettings.intent(this, Settings.ACTION_WIFI_SETTINGS))));
        pane.addView(actionRow(getString(R.string.sm_apps_settings), "", () ->
                open(SystemSettings.intent(this, Settings.ACTION_APPLICATION_SETTINGS))));
        pane.addView(actionRow(getString(R.string.sm_screensaver), "", () ->
                open(SystemSettings.intent(this, Settings.ACTION_DREAM_SETTINGS))));
        pane.addView(actionRow(getString(R.string.sm_default_home), "", () ->
                open(new Intent(this, HomeSettingsActivity.class))));
        focusPane();
    }

    /**
     * System > Updates (Updates): Check for updates at the top, with what it found, Install update when there is one
     * and the installed version; how often the launcher checks by itself at the bottom.
     */
    private void showUpdates() {
        int scrollY = back != null && updatesOpen ? scroll.getScrollY() : 0;
        clearPane();
        paneTitle.setText(R.string.sm_updates);
        updatesOpen = true;
        back = () -> {
            updatesOpen = false;
            rebuild(5);
        };
        note(getString(R.string.sm_update_note));
        Updates.Offer offer = Updates.offer(this);
        TextView status = new TextView(this);
        String now = updateStatus(offer);
        View check = actionRow(getString(R.string.sm_update_check), now.isEmpty() ? " " : now, () -> {
            setRowValue(status, getString(R.string.sm_checking));
            Updates.check(this, found -> {
                if (category == 5 && updatesOpen) showUpdates();
            });
        });
        // actionRow's value view is the second child; keep it to show "Checking…".
        if (check instanceof LinearLayout && ((LinearLayout) check).getChildCount() > 2) {
            View v = ((LinearLayout) check).getChildAt(1);
            if (v instanceof TextView) status.setTag(v);
        }
        pane.addView(check);
        if (offer != null) {
            // Install first shows what's new and asks again (UpdateConfirm), then downloads.
            // The row shows the download's progress, then that Android's installer is opening.
            TextView[] progressView = {null};
            View download = actionRow(getString(R.string.update_download), offer.name, () ->
                    UpdateConfirm.show(this, offer, accent, () -> {
                        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show();
                        Updates.install(this, offer, (percent, failure) -> {
                            if (failure != null) {
                                if (progressView[0] != null) progressView[0].setText(offer.name);
                                Toast.makeText(this, getString(R.string.update_failed, failure), Toast.LENGTH_LONG).show();
                            } else if (progressView[0] != null) {
                                progressView[0].setText(percent < 0 ? getString(R.string.update_opening)
                                        : getString(R.string.update_progress, percent));
                            }
                        });
                    }));
            View value = ((ViewGroup) download).getChildAt(1);
            if (value instanceof TextView) progressView[0] = (TextView) value;
            pane.addView(download);
        }
        pane.addView(actionRow(getString(R.string.sm_version), Updates.installedName(this) + " · "
                + getString(Updates.OFFLINE.equals(Updates.build()) ? R.string.sm_channel_offline : R.string.sm_channel_online), null));

        // This fork: which build updates install. Picking the other one offers this version of it at once.
        section(R.string.sm_update_channel);
        String[] channels = {getString(R.string.sm_channel_online), getString(R.string.sm_channel_offline)};
        pane.addView(choiceRow(getString(R.string.sm_update_channel), channels,
                Updates.OFFLINE.equals(Updates.channel(this)) ? 1 : 0, i -> {
                    String picked = i == 1 ? Updates.OFFLINE : Updates.ONLINE;
                    if (picked.equals(Updates.channel(this))) return;
                    Updates.setChannel(this, picked);
                    Updates.check(this, found -> {
                        if (category == 5 && updatesOpen) showUpdates();
                    });
                }));
        note(getString(R.string.sm_update_channel_note));

        section(R.string.sm_update_auto);
        String[] labels = new String[Updates.INTERVALS.length];
        int current = 0, hours = Updates.intervalHours(this);
        for (int i = 0; i < labels.length; i++) {
            labels[i] = Updates.INTERVALS[i] == 0 ? getString(R.string.sm_off) : getString(R.string.sm_hours, Updates.INTERVALS[i]);
            if (Updates.INTERVALS[i] == hours) current = i;
        }
        pane.addView(choiceRow(getString(R.string.sm_update_every), labels, current,
                i -> Updates.setIntervalHours(this, Updates.INTERVALS[i])));
        note(getString(R.string.sm_update_auto_note));
        if (scrollY > 0) scroll.post(() -> scroll.scrollTo(0, scrollY));
        else focusPane();
    }

    private String updateStatus(Updates.Offer offer) {
        if (offer != null) return getString(R.string.sm_update_ready, offer.name.isEmpty() ? String.valueOf(offer.code) : offer.name);
        if (Updates.lastCheck(this) == 0) return "";
        return Updates.lastError(this) != null ? getString(R.string.sm_update_error) : getString(R.string.sm_up_to_date);
    }

    /** Shows text in an actionRow's value view, remembered in holder's tag. */
    private static void setRowValue(TextView holder, String text) {
        if (holder.getTag() instanceof TextView) ((TextView) holder.getTag()).setText(text);
    }

    /** A page with one text field: Save keeps what was typed (empty clears, where clearing is allowed). */
    private void editText(String title, String hint, String value, boolean clearable, java.util.function.Consumer<String> save) {
        int returnTo = category;
        editText(title, hint, value, clearable, save, () -> {
            rebuild(returnTo);
            focusPane();
        });
    }

    /** The same, coming back with after (a sub-page such as Quick settings) instead of the category's page. */
    private void editText(String title, String hint, String value, boolean clearable, java.util.function.Consumer<String> save,
                          Runnable after) {
        back = null;
        clearPane();
        paneTitle.setText(title);
        back = () -> {
            back = null;
            after.run();
        };
        EditText field = field(hint, value);
        pane.addView(field, new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(-2, -2);
        buttonsLp.topMargin = dp(16);
        pane.addView(buttons, buttonsLp);
        Runnable done = () -> {
            hideKeyboard(field);
            save.accept(field.getText().toString().trim());
            back = null;
            after.run();
        };
        TextView saveButton = pill(getString(R.string.sm_save));
        saveButton.setOnClickListener(v -> done.run());
        buttons.addView(saveButton, new LinearLayout.LayoutParams(-2, dp(46)));
        if (clearable) {
            TextView clear = pill(getString(R.string.sm_clear));
            clear.setOnClickListener(v -> {
                field.setText("");
                done.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(46));
            lp.setMarginStart(dp(12));
            buttons.addView(clear, lp);
        }
        field.setOnEditorActionListener((v, action, event) -> {
            done.run();
            return true;
        });
        field.requestFocus();
    }

    // ---- highlight colour ---------------------------------------------------------------------------------

    /** The preset colours as round swatches, white first; the chosen one ringed, the focused one zoomed. */
    private View swatchRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        int size = dp(42);   // ten swatches fit the pane with a focused one's zoom
        row.addView(swatch(getString(R.string.sm_adaptive), wheel(), adaptive, v -> {
            adaptive = true;
            Accent.setAdaptive(this);
            restyle(0);
            rebuild(0);
            focusSwatch(getString(R.string.sm_adaptive));
        }), swatchLp(size));
        for (int c : Accent.PRESETS) {
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(c == 0 ? 0xFFF2F2F2 : c);
            String name = c == 0 ? getString(R.string.sm_white_default) : Accent.hex(c);
            row.addView(swatch(name, fill, !adaptive && c == accent, v -> {
                applyAccent(c);
                rebuild(0);
                focusSwatch(name);
            }), swatchLp(size));
        }
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(8);
        rowLp.bottomMargin = dp(4);
        row.setLayoutParams(rowLp);
        return row;
    }

    private LinearLayout.LayoutParams swatchLp(int size) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(dp(10));
        return lp;
    }

    /** A round swatch: the colour inset in a ring that shows when chosen, and brightens and zooms with focus. */
    private View swatch(String name, Drawable colour, boolean chosen, View.OnClickListener pick) {
        FrameLayout swatch = new FrameLayout(this);
        swatch.setFocusable(true);
        swatch.setClickable(true);
        swatch.setContentDescription(name);
        StateListDrawable frame = new StateListDrawable();
        GradientDrawable focusedRing = new GradientDrawable();
        focusedRing.setShape(GradientDrawable.OVAL);
        focusedRing.setStroke(dp(3), 0xFFFFFFFF);
        GradientDrawable chosenRing = new GradientDrawable();
        chosenRing.setShape(GradientDrawable.OVAL);
        chosenRing.setStroke(dp(2), 0x99FFFFFF);
        frame.addState(new int[]{android.R.attr.state_focused}, focusedRing);
        frame.addState(new int[]{android.R.attr.state_selected}, chosenRing);
        frame.addState(new int[]{}, new GradientDrawable());
        swatch.setBackground(frame);
        View dot = new View(this);
        dot.setBackground(colour);
        FrameLayout.LayoutParams dotLp = new FrameLayout.LayoutParams(-1, -1);
        dotLp.setMargins(dp(6), dp(6), dp(6), dp(6));
        swatch.addView(dot, dotLp);
        swatch.setSelected(chosen);
        swatch.setOnFocusChangeListener((v, focused) -> {
            v.animate().scaleX(focused ? 1.15f : 1f).scaleY(focused ? 1.15f : 1f).setDuration(120).start();
            if (focused) scrollIntoView((View) v.getParent());
        });
        swatch.setOnClickListener(pick);
        return swatch;
    }

    /** Adaptive's swatch: a colour wheel. */
    private static Drawable wheel() {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setGradientType(GradientDrawable.SWEEP_GRADIENT);
        g.setColors(new int[]{0xFFFF453A, 0xFFFFD60A, 0xFF30D158, 0xFF64D2FF, 0xFF0A84FF, 0xFFBF5AF2, 0xFFFF375F, 0xFFFF453A});
        return g;
    }

    /** After the page is rebuilt in the new colour, focus goes back to the swatch that was picked. */
    private void focusSwatch(String name) {
        pane.post(() -> {
            View found = findSwatch(pane, name);
            if (found != null) found.requestFocus();
        });
    }

    private static View findSwatch(View v, String description) {
        if (description.contentEquals(v.getContentDescription() == null ? "" : v.getContentDescription())) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View f = findSwatch(g.getChildAt(i), description);
                if (f != null) return f;
            }
        }
        return null;
    }

    /** Saves the colour, and repaints this menu in it: the category pills now, the page when it is rebuilt. */
    private void applyAccent(int c) {
        adaptive = false;
        Accent.set(this, c);
        restyle(c == 0 ? 0 : 0xFF000000 | c);
    }

    private void restyle(int c) {
        accent = c;
        changed();
        for (TextView b : categoryButtons) {
            b.setBackground(glass(dp(22)));
            b.setTextColor(ink(INK));
        }
    }

    /**
     * The custom colour, for the remote: hue, saturation and brightness sliders (Left and Right; hold to go faster),
     * a preview of a focused pill in the colour, Use this color, and a hex code for an exact one.
     */
    /** Hue, saturation and brightness sliders and a hex code; chosen gets the colour, then leave runs (also on Cancel). */
    private void showColorPicker(int title, int start, IntConsumer chosen, Runnable leave) {
        clearPane();
        paneTitle.setText(title);
        back = () -> {
            back = null;
            leave.run();
        };
        float[] hsv = new float[3];
        android.graphics.Color.colorToHSV(start, hsv);

        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sample = text(17, R.string.font_medium, INK);
        sample.setText(R.string.sm_preview);
        sample.setGravity(Gravity.CENTER);
        sample.setPadding(dp(26), 0, dp(26), 0);
        GradientDrawable sampleBg = new GradientDrawable();
        sampleBg.setCornerRadius(dp(23));
        sample.setBackground(sampleBg);
        previewRow.addView(sample, new LinearLayout.LayoutParams(-2, dp(46)));
        TextView outline = text(17, R.string.font_medium, INK);
        outline.setText(R.string.sm_preview);
        outline.setGravity(Gravity.CENTER);
        outline.setPadding(dp(26), 0, dp(26), 0);
        GradientDrawable outlineBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x2EFFFFFF, 0x14FFFFFF});
        outlineBg.setCornerRadius(dp(23));
        outline.setBackground(outlineBg);
        LinearLayout.LayoutParams outlineLp = new LinearLayout.LayoutParams(-2, dp(46));
        outlineLp.setMarginStart(dp(14));
        previewRow.addView(outline, outlineLp);
        TextView hexLabel = text(18, R.string.font_regular, 0xB3FFFFFF);
        hexLabel.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(-2, -2);
        hexLp.setMarginStart(dp(22));
        previewRow.addView(hexLabel, hexLp);
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, -2);
        previewLp.bottomMargin = dp(10);
        pane.addView(previewRow, previewLp);

        ColorSlider hue = new ColorSlider(this, ColorSlider.HUE);
        ColorSlider sat = new ColorSlider(this, ColorSlider.SATURATION);
        ColorSlider val = new ColorSlider(this, ColorSlider.BRIGHTNESS);
        Runnable update = () -> {
            int c = android.graphics.Color.HSVToColor(hsv);
            sampleBg.setColor(Accent.fill(c));
            sample.setTextColor(Accent.onFill(c));
            outlineBg.setStroke(Math.max(1, dp(1.5f)), Accent.edge(c, 0x38FFFFFF));
            hexLabel.setText(Accent.hex(c));
            hue.set(hsv[0], hsv[0], hsv[1], hsv[2]);
            sat.set(hsv[1] * 100f, hsv[0], hsv[1], hsv[2]);
            val.set(hsv[2] * 100f, hsv[0], hsv[1], hsv[2]);
        };
        ColorSlider.Listener listener = (slider, value) -> {
            if (slider == hue) hsv[0] = Math.min(value, 359.9f);
            else if (slider == sat) hsv[1] = value / 100f;
            else hsv[2] = value / 100f;
            update.run();
        };
        int[] labels = {R.string.sm_hue, R.string.sm_saturation, R.string.sm_brightness};
        ColorSlider[] sliders = {hue, sat, val};
        for (int i = 0; i < sliders.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = text(18, R.string.font_regular, INK);
            name.setText(labels[i]);
            row.addView(name, new LinearLayout.LayoutParams(dp(140), -2));
            sliders[i].setListener(listener);
            ColorSlider slider = sliders[i];
            slider.setOnFocusChangeListener((v, focused) -> {
                name.setTextColor(focused ? INK : 0xB3FFFFFF);
                if (focused) scrollIntoView(row);
            });
            name.setTextColor(0xB3FFFFFF);
            row.addView(slider, new LinearLayout.LayoutParams(0, dp(48), 1f));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
            rowLp.topMargin = dp(10);
            pane.addView(row, rowLp);
        }
        update.run();

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(-2, -2);
        buttonsLp.topMargin = dp(24);
        pane.addView(buttons, buttonsLp);
        TextView use = pill(getString(R.string.sm_use_color));
        use.setOnClickListener(v -> {
            chosen.accept(android.graphics.Color.HSVToColor(hsv));
            back = null;
            leave.run();
        });
        buttons.addView(use, new LinearLayout.LayoutParams(-2, dp(46)));
        TextView hex = pill(getString(R.string.sm_hex));
        hex.setOnClickListener(v -> editText(getString(R.string.sm_hex), getString(R.string.sm_hex_hint),
                Accent.hex(android.graphics.Color.HSVToColor(hsv)), false, typed -> {
                    int c = Accent.parse(typed);
                    if (c == -1) {
                        Toast.makeText(this, R.string.sm_hex_bad, Toast.LENGTH_LONG).show();
                    } else {
                        chosen.accept(c);
                        back = null;
                        leave.run();
                    }
                }));
        LinearLayout.LayoutParams hexButtonLp = new LinearLayout.LayoutParams(-2, dp(46));
        hexButtonLp.setMarginStart(dp(12));
        buttons.addView(hex, hexButtonLp);
        TextView cancel = pill(getString(R.string.sm_cancel));
        cancel.setOnClickListener(v -> {
            back = null;
            leave.run();
        });
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(-2, dp(46));
        cancelLp.setMarginStart(dp(12));
        buttons.addView(cancel, cancelLp);
        hue.requestFocus();
    }

    // ---- the screensaver ---------------------------------------------------------------------------------

    /** The screensaver drawn small above the page's rows (it stays put while they scroll), and its scene. */
    private FrameLayout screensaverPreview;
    private ClockScene previewScene;
    private static final int[] SCREENSAVER_MINUTES = {5, 10, 15, 30, 60, 120};

    /**
     * Settings > Clock > Screensaver: when it starts, where the clock, the date and the weather go and how big, and
     * the glow behind them; the preview above redraws with each change (ScreensaverStyle, ClockScene).
     */
    private void showScreensaver() {
        showScreensaver(null);
    }

    /** focusLabel: the row to come back to (after the glow colour), by its label; null for the first. */
    private void showScreensaver(String focusLabel) {
        clearPane();
        paneTitle.setText(R.string.sm_screensaver);
        back = () -> rebuild(3);
        showScreensaverPreview();
        ScreensaverStyle st = ScreensaverStyle.load(this);

        // When it starts: Android's own timer (screen_off_timeout), which needs "modify system settings".
        int now = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 10 * 60_000) / 60_000;
        String[] after = new String[SCREENSAVER_MINUTES.length];
        int afterIndex = 0;
        for (int i = 0; i < after.length; i++) {
            int m = SCREENSAVER_MINUTES[i];
            after[i] = m < 60 ? getString(R.string.sm_minutes, m) : getString(R.string.sm_hours, m / 60);
            if (Math.abs(m - now) < Math.abs(SCREENSAVER_MINUTES[afterIndex] - now)) afterIndex = i;
        }
        pane.addView(choiceRow(getString(R.string.sm_ss_start), after, afterIndex, i -> {
            if (Settings.System.canWrite(this)) {
                Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, SCREENSAVER_MINUTES[i] * 60_000);
                return;
            }
            Toast.makeText(this, R.string.sm_ss_start_denied, Toast.LENGTH_LONG).show();
            Intent allow = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, android.net.Uri.parse("package:" + getPackageName()));
            open(allow.resolveActivity(getPackageManager()) != null ? allow : new Intent(Settings.ACTION_DREAM_SETTINGS));
        }));
        if (!Settings.System.canWrite(this)) note(getString(R.string.sm_ss_start_note));

        // Each part in two rows: where it goes (a few choices and a 3x3 grid of spots) and how big.
        section(R.string.sm_clock);
        placeRow(new String[]{getString(R.string.sm_ss_moves)},
                ScreensaverStyle.FIXED.equals(st.clockMode) ? -1 : 0, st.clockSpot,
                i -> saveScreensaver(ScreensaverStyle.CLOCK_MODE, ScreensaverStyle.MOVE),
                spot -> {
                    prefs.edit().putString(ScreensaverStyle.CLOCK_SPOT, spot).apply();
                    saveScreensaver(ScreensaverStyle.CLOCK_MODE, ScreensaverStyle.FIXED);
                });
        sizeRow(ScreensaverStyle.CLOCK_SIZE, st.clockSize);
        partRows(R.string.sm_ss_date, ScreensaverStyle.DATE_MODE, st.dateMode, ScreensaverStyle.DATE_SPOT, st.dateSpot,
                ScreensaverStyle.DATE_SIZE, st.dateSize);
        // The offline build has no weather to place.
        if (!BuildConfig.OFFLINE) partRows(R.string.sm_weather, ScreensaverStyle.WEATHER_MODE, st.weatherMode, ScreensaverStyle.WEATHER_SPOT,
                st.weatherSpot, ScreensaverStyle.WEATHER_SIZE, st.weatherSize);

        section(R.string.sm_ss_glow);
        String[] glowModes = {ScreensaverStyle.GLOW_CYCLE, ScreensaverStyle.GLOW_CUSTOM, ScreensaverStyle.GLOW_OFF};
        String colorLabel = getString(R.string.sm_ss_glow_color);
        View glowRow = choiceRow(colorLabel,
                new String[]{getString(R.string.sm_ss_glow_cycle), getString(R.string.sm_custom_color), getString(R.string.sm_off)},
                java.util.Arrays.asList(glowModes).indexOf(st.glow), i -> {
                    // Custom color opens the picker; the glow turns custom once a colour is chosen there.
                    if (i == 1) {
                        showColorPicker(R.string.sm_ss_glow_color, st.glowColor, c -> prefs.edit()
                                .putInt(ScreensaverStyle.GLOW_COLOR, c).putString(ScreensaverStyle.GLOW, ScreensaverStyle.GLOW_CUSTOM)
                                .apply(), () -> showScreensaver(colorLabel));
                    } else {
                        saveScreensaver(ScreensaverStyle.GLOW, glowModes[i]);
                    }
                });
        // The custom colour as a dot on its pill.
        TextView customPill = (TextView) ((ViewGroup) glowRow).getChildAt(2);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(st.glowColor);
        dot.setSize(dp(12), dp(12));
        customPill.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
        customPill.setCompoundDrawablePadding(dp(8));
        pane.addView(glowRow);
        pane.addView(choiceRow(getString(R.string.sm_ss_glow_points), numbers(1, ScreensaverStyle.MAX_POINTS),
                st.glowPoints - 1, i -> saveScreensaver(ScreensaverStyle.GLOW_POINTS, i + 1)));

        View full = actionRow(getString(R.string.sm_ss_full), "", () -> open(new Intent(this, ClockActivity.class)));
        ((LinearLayout.LayoutParams) full.getLayoutParams()).topMargin = dp(18);
        pane.addView(full);

        View focus = null;
        if (focusLabel != null) {
            for (int i = 0; i < pane.getChildCount() && focus == null; i++) {
                View row = pane.getChildAt(i);
                if (row instanceof LinearLayout && ((LinearLayout) row).getChildCount() > 0
                        && ((LinearLayout) row).getChildAt(0) instanceof TextView
                        && focusLabel.contentEquals(((TextView) ((LinearLayout) row).getChildAt(0)).getText())) focus = row;
            }
        }
        View target = focus;
        if (target != null) pane.post(target::requestFocus);
        else focusPane();
    }

    /** The date or the weather: with the clock, off, or on a spot of its own (the grid); then its size. */
    private void partRows(int title, String modeKey, String mode, String spotKey, String spot, String sizeKey, int size) {
        section(title);
        String[] modes = {ScreensaverStyle.WITH_CLOCK, ScreensaverStyle.OFF};
        placeRow(new String[]{getString(R.string.sm_ss_with_clock), getString(R.string.sm_off)},
                ScreensaverStyle.OWN.equals(mode) ? -1 : java.util.Arrays.asList(modes).indexOf(mode), spot,
                i -> saveScreensaver(modeKey, modes[i]),
                picked -> {
                    prefs.edit().putString(spotKey, picked).apply();
                    saveScreensaver(modeKey, ScreensaverStyle.OWN);
                });
        sizeRow(sizeKey, size);
    }

    /**
     * Position: a few pills, then a 3x3 grid of spots (the screen in small); one of them is chosen. selected is the
     * chosen pill, or -1 for the grid's spot.
     */
    private void placeRow(String[] options, int selected, String spot, IntConsumer pickOption,
                          java.util.function.Consumer<String> pickSpot) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        TextView name = text(16, R.string.font_regular, INK);
        name.setText(R.string.sm_ss_position);
        name.setSingleLine(true);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        List<View> choices = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView pill = pill(options[i]);
            pill.setMinWidth(dp(58));
            pill.setSelected(i == selected);
            pill.setOnClickListener(v -> {
                for (View other : choices) other.setSelected(other == v);
                pickOption.accept(index);
            });
            pill.setOnFocusChangeListener((v, focused) -> {
                if (focused) scrollIntoView(row);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMarginStart(dp(8));
            row.addView(pill, lp);
            choices.add(pill);
        }
        // The grid: three rows of three small screens, the chosen spot outlined, the focused one white.
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        for (int r = 0; r < 3; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < 3; c++) {
                String here = String.valueOf("lcr".charAt(c)) + "tmb".charAt(r);
                View cell = new View(this);
                cell.setFocusable(true);
                cell.setClickable(true);
                cell.setBackground(spotCell());
                cell.setSelected(selected < 0 && here.equals(spot));
                cell.setContentDescription(here);
                cell.setOnClickListener(v -> {
                    for (View other : choices) other.setSelected(other == v);
                    pickSpot.accept(here);
                });
                cell.setOnFocusChangeListener((v, focused) -> {
                    if (focused) scrollIntoView(row);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(17));
                if (c > 0) lp.setMarginStart(dp(4));
                if (r > 0) lp.topMargin = dp(4);
                line.addView(cell, lp);
                choices.add(cell);
            }
            grid.addView(line);
        }
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(-2, -2);
        gridLp.setMarginStart(dp(18));
        row.addView(grid, gridLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(6);
        pane.addView(row, rowLp);
    }

    /** A spot in the grid: glass, outlined when chosen, white when focused (like the pills). */
    private android.graphics.drawable.Drawable spotCell() {
        float radius = dp(4);
        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x2EFFFFFF, 0x14FFFFFF});
        rest.setCornerRadius(radius);
        rest.setStroke(Math.max(1, dp(1)), 0x38FFFFFF);
        GradientDrawable chosen = new GradientDrawable();
        chosen.setColor(accent == 0 ? 0x99FFFFFF : (Accent.ring(accent) & 0x00FFFFFF) | 0xB3000000);
        chosen.setCornerRadius(radius);
        chosen.setStroke(Math.max(1, Math.round(1.5f * getResources().getDisplayMetrics().density)),
                accent == 0 ? 0xFFFFFFFF : Accent.ring(accent));
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0xFFFFFFFF);
        focused.setCornerRadius(radius);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{android.R.attr.state_selected}, chosen);
        states.addState(new int[]{}, rest);
        return states;
    }

    private void sizeRow(String key, int size) {
        pane.addView(choiceRow(getString(R.string.sm_ss_size), ScreensaverStyle.SIZE_NAMES, size,
                i -> saveScreensaver(key, i)));
    }

    private void saveScreensaver(String key, String value) {
        prefs.edit().putString(key, value).apply();
        refreshScreensaverPreview();
    }

    private void saveScreensaver(String key, int value) {
        prefs.edit().putInt(key, value).apply();
        refreshScreensaverPreview();
    }

    /** Above the rows, under the page title: the screensaver at 16:9, drawn at screen size and scaled down. */
    private void showScreensaverPreview() {
        hideScreensaverPreview();
        ViewGroup column = (ViewGroup) scroll.getParent();
        FrameLayout frame = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF000000);
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.max(1, dp(1)), 0x38FFFFFF);
        frame.setBackground(bg);
        frame.setClipToOutline(true);
        int h = dp(200);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(h * 16 / 9, h);
        lp.setMarginStart(dp(16));
        lp.bottomMargin = dp(6);
        column.addView(frame, column.indexOfChild(scroll), lp);
        screensaverPreview = frame;
        refreshScreensaverPreview();
    }

    private void refreshScreensaverPreview() {
        FrameLayout frame = screensaverPreview;
        if (frame == null) return;
        if (previewScene != null) previewScene.stop();
        frame.removeAllViews();
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        View screen = getLayoutInflater().inflate(R.layout.dream_clock, frame, false);
        frame.addView(screen, new FrameLayout.LayoutParams(dm.widthPixels, dm.heightPixels));
        float scale = (float) frame.getLayoutParams().width / dm.widthPixels;
        screen.setPivotX(0);
        screen.setPivotY(0);
        screen.setScaleX(scale);
        screen.setScaleY(scale);
        ClockFormat.apply(screen, prefs.getString(Config.CLOCK_FORMAT_KEY, config.clockFormat));
        previewScene = new ClockScene(screen);
        previewScene.useHomeWallpaper(this);
        previewScene.start();
    }

    private void hideScreensaverPreview() {
        if (previewScene != null) previewScene.stop();
        previewScene = null;
        if (screensaverPreview != null && screensaverPreview.getParent() != null) {
            ((ViewGroup) screensaverPreview.getParent()).removeView(screensaverPreview);
        }
        screensaverPreview = null;
    }

    // ---- quick settings ----------------------------------------------------------------------------------

    /** The tile being moved on the Quick settings page, or null; its order while it moves. */
    private String qsMoving;
    /** True while a page swaps the view that has focus: Android hands focus to the category list meanwhile. */
    private boolean keepPage;
    /** The Quick settings page's own Back (to System), restored after the tile actions. */
    private Runnable qsPageBack;
    private List<String> qsOrder;
    private LinearLayout qsTiles, qsActions;

    /**
     * Settings > System > Quick settings: the remote's Settings panel itself, as the preview. Selecting a tile offers
     * Move (then the arrows carry it and OK puts it down), Turn off or on, and for the two app buttons, Choose app.
     * focusId: the tile to come back to.
     */
    private void showQuickSettings(String focusId) {
        back = null;
        qsMoving = null;
        stayOnPage(this::clearPane);
        paneTitle.setText(R.string.sm_qs_title);
        back = () -> rebuild(5);
        qsPageBack = back;
        note(getString(R.string.sm_qs_note));

        View card = getLayoutInflater().inflate(R.layout.quick_panel, pane, false);
        TextClock date = card.findViewById(R.id.quickDate);
        String datePattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd");
        date.setFormat12Hour(datePattern);
        date.setFormat24Hour(datePattern);
        ClockFormat.apply(card.findViewById(R.id.quickTime), prefs.getString(Config.CLOCK_FORMAT_KEY, config.clockFormat));
        card.findViewById(R.id.quickAll).setFocusable(false);   // the preview's own buttons are the tiles
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-2, -2);
        cardLp.topMargin = dp(8);
        pane.addView(card, cardLp);
        qsTiles = card.findViewById(R.id.tiles);

        // What can be done with the selected tile, under the preview.
        qsActions = new LinearLayout(this);
        qsActions.setOrientation(LinearLayout.HORIZONTAL);
        qsActions.setMinimumHeight(dp(58));
        qsActions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.topMargin = dp(10);
        pane.addView(qsActions, actionsLp);

        View reset = actionRow(getString(R.string.sm_qs_reset), "", () -> {
            QuickTiles.reset(this);
            showQuickSettings(null);
        });
        pane.addView(reset);

        qsOrder = QuickTiles.order(this);
        View focus = drawQuickTiles(focusId != null ? focusId : qsOrder.get(0));
        if (focus != null) pane.post(focus::requestFocus);
    }

    /** Runs r, which replaces the focused view, without the category list switching the page meanwhile. */
    private void stayOnPage(Runnable r) {
        keepPage = true;
        try {
            r.run();
        } finally {
            keepPage = false;
        }
    }

    /** The preview's tiles, all of them (off ones dimmed); returns the view of focusId. */
    private View drawQuickTiles(String focusId) {
        java.util.Set<String> off = QuickTiles.off(this);
        View[] focus = {null};
        QuickTiles.fill(this, qsTiles, qsOrder, (id, tile) -> {
            boolean on = !off.contains(id) && !(QuickTiles.isApp(id) && QuickTiles.intent(this, id) == null);
            tile.setAlpha(on || id.equals(qsMoving) ? 1f : 0.38f);
            tile.setActivated(id.equals(qsMoving));
            tile.setOnClickListener(v -> {
                if (qsMoving == null) showQuickActions(id, tile);
            });
            tile.setOnFocusChangeListener((v, focused) -> {
                if (focused) scrollIntoView(tile);
            });
            tile.setOnKeyListener((v, keyCode, event) -> moveQuickTile(id, keyCode, event));
            if (id.equals(focusId)) focus[0] = tile;
        });
        return focus[0];
    }

    /** Move, Turn off or on, Choose app: pills under the preview for the selected tile. */
    private void showQuickActions(String id, View tile) {
        qsActions.removeAllViews();
        boolean on = !QuickTiles.off(this).contains(id);
        List<TextView> pills = new ArrayList<>();
        pills.add(pill(getString(R.string.sm_qs_move)));
        pills.get(0).setOnClickListener(v -> {
            back = qsPageBack;
            stayOnPage(() -> {
                qsActions.removeAllViews();
                qsMoving = id;
                View moving = drawQuickTiles(id);
                if (moving != null) moving.requestFocus();
            });
            Toast.makeText(this, R.string.sm_qs_moving, Toast.LENGTH_SHORT).show();
        });
        TextView toggle = pill(getString(on ? R.string.sm_qs_turn_off : R.string.sm_qs_turn_on));
        toggle.setOnClickListener(v -> {
            if (!on && QuickTiles.isApp(id) && QuickTiles.app(this, id).isEmpty()) {
                showAppChooser(id);   // an app button needs its app first
                return;
            }
            QuickTiles.setOn(this, id, !on);
            showQuickSettings(id);
        });
        pills.add(toggle);
        if (QuickTiles.isApp(id)) {
            TextView choose = pill(getString(R.string.sm_qs_choose_app));
            choose.setOnClickListener(v -> showAppChooser(id));
            pills.add(choose);
        }
        // Its own text; empty brings back the tile's own name.
        TextView rename = pill(getString(R.string.sm_qs_rename));
        rename.setOnClickListener(v -> {
            String own = QuickTiles.label(this, id);
            String shown = own.isEmpty() ? QuickTiles.defaultLabel(this, id).toString() : own;
            editText(getString(R.string.sm_qs_rename), getString(R.string.sm_qs_rename_hint), shown, true, value -> {
                boolean original = value.isEmpty() || value.contentEquals(QuickTiles.defaultLabel(this, id));
                QuickTiles.setLabel(this, id, original ? "" : value);
            }, () -> showQuickSettings(id));
        });
        pills.add(rename);
        for (int i = 0; i < pills.size(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            if (i > 0) lp.setMarginStart(dp(8));
            qsActions.addView(pills.get(i), lp);
        }
        pills.get(0).requestFocus();
        back = () -> {
            back = qsPageBack;
            stayOnPage(() -> {
                qsActions.removeAllViews();
                tile.requestFocus();
            });
        };
    }

    /** While a tile moves: the arrows swap it with its neighbour in the grid, OK or Back puts it down. */
    private boolean moveQuickTile(String id, int keyCode, KeyEvent event) {
        if (!id.equals(qsMoving)) return false;
        boolean drop = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BACK;
        if (event.getAction() != KeyEvent.ACTION_DOWN) return drop || isArrow(keyCode);
        int at = qsOrder.indexOf(id), to = -1;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (at % 2 == 1) to = at - 1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (at % 2 == 0 && at + 1 < qsOrder.size()) to = at + 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: if (at >= 2) to = at - 2; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (at + 2 < qsOrder.size()) to = at + 2; break;
            default:
                if (!drop) return false;
                qsMoving = null;
                QuickTiles.saveOrder(this, qsOrder);
                qsOrder = QuickTiles.order(this);   // a tile that is on stays above the ones that are off
                stayOnPage(() -> {
                    View placed = drawQuickTiles(id);
                    if (placed != null) placed.requestFocus();
                });
                return true;
        }
        if (to >= 0) {
            Collections.swap(qsOrder, at, to);
            stayOnPage(() -> {
                View moved = drawQuickTiles(id);
                if (moved != null) moved.requestFocus();
            });
        }
        return true;
    }

    private static boolean isArrow(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
    }

    /** The apps that can be opened from the TV, by name; picking one puts it on the app button and turns it on. */
    private void showAppChooser(String id) {
        back = null;
        qsMoving = null;
        stayOnPage(this::clearPane);
        paneTitle.setText(R.string.sm_qs_choose_app);
        back = () -> showQuickSettings(id);
        android.content.pm.PackageManager pm = getPackageManager();
        java.util.Map<String, CharSequence> apps = new java.util.TreeMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.equals(getPackageName())) apps.put(pkg, ri.loadLabel(pm));
            }
        }
        List<java.util.Map.Entry<String, CharSequence>> sorted = new ArrayList<>(apps.entrySet());
        sorted.sort((a, b) -> a.getValue().toString().compareToIgnoreCase(b.getValue().toString()));
        String current = QuickTiles.app(this, id);
        View focus = null;
        for (java.util.Map.Entry<String, CharSequence> app : sorted) {
            View row = actionRow(app.getValue().toString(), app.getKey().equals(current) ? "✓" : "", () -> {
                QuickTiles.setApp(this, id, app.getKey());
                QuickTiles.setOn(this, id, true);
                showQuickSettings(id);
            });
            if (app.getKey().equals(current)) focus = row;
            pane.addView(row);
        }
        View target = focus;
        if (target != null) pane.post(target::requestFocus);
        else focusPane();
    }

    // ---- rows ------------------------------------------------------------------------------------------

    /** A label and a set of pills, the chosen one outlined; OK on a pill picks it. selected -1: none marked. */
    private View choiceRow(String label, String[] options, int selected, IntConsumer pick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        TextView name = text(16, R.string.font_regular, INK);
        name.setText(label);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        List<TextView> pills = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            TextView p = pill(options[i]);
            // Six or more choices (Dock apps 1-6, Check every Off-24 h) leave the label more room with slimmer pills.
            p.setMinWidth(dp(options.length >= 6 ? 50 : 58));
            // Five or more word choices (Rotate: Each visit … 60 min) would squeeze the row's label to "Ro…".
            if (options.length >= 5) p.setPadding(dp(14), 0, dp(14), 0);
            p.setSelected(i == selected);
            p.setOnClickListener(v -> {
                for (TextView other : pills) other.setSelected(other == v);
                pick.accept(index);
            });
            p.setOnFocusChangeListener((v, focused) -> {
                if (focused) scrollIntoView(row);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(36));
            lp.setMarginStart(dp(options.length >= 6 ? 6 : 8));
            row.addView(p, lp);
            pills.add(p);
        }
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(4);
        row.setLayoutParams(rowLp);
        return row;
    }

    private View toggleRow(String label, boolean on, java.util.function.Consumer<Boolean> set) {
        return choiceRow(label, new String[]{getString(R.string.sm_on), getString(R.string.sm_off)}, on ? 0 : 1,
                i -> set.accept(i == 0));
    }

    /** A full-width glass row: label, value and a chevron; it turns white with focus, like the pills. */
    private View actionRow(String label, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setBackground(glass(dp(14)));
        row.setPadding(dp(20), 0, dp(18), 0);
        TextView name = text(16, R.string.font_regular, INK);
        name.setText(label);
        name.setDuplicateParentStateEnabled(true);
        // A label written right to left (العربية, עברית in the language list) still starts at the row's left edge.
        // Absolute LEFT: the launcher does not declare RTL support, so Android ignores a text direction set here and
        // START would follow the label's own (right-to-left) direction.
        name.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        name.setTextColor(ink(INK));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        if (!value.isEmpty()) {
            TextView v = text(14, R.string.font_regular, 0x99FFFFFF);
            v.setText(value);
            v.setDuplicateParentStateEnabled(true);
            v.setTextColor(ink(0x99FFFFFF));
            v.setSingleLine(true);
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setMaxWidth(dp(360));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.setMarginStart(dp(16));
            row.addView(v, lp);
        }
        // No action: an information row (Storage). It still takes focus, so the remote can scroll to it.
        if (action != null) {
            TextView chevron = text(20, R.string.font_light, 0x99FFFFFF);
            chevron.setText("›");
            chevron.setDuplicateParentStateEnabled(true);
            chevron.setTextColor(ink(0x99FFFFFF));
            LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(-2, -2);
            chevronLp.setMarginStart(dp(14));
            row.addView(chevron, chevronLp);
            row.setOnClickListener(v -> action.run());
        }
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused) scrollIntoView(v);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        return row;
    }

    /** Tags of the pane's section titles and notes (showHint tells them from the rows). */
    private static final Object SECTION_TAG = new Object(), NOTE_TAG = new Object();
    /** The focused row's notes, under the page (showHint). */
    private TextView hint;

    private void section(int title) {
        TextView t = text(12, R.string.font_medium, 0x80FFFFFF);
        t.setText(getString(title).toUpperCase(Locale.getDefault()));
        t.setLetterSpacing(0.16f);
        t.setTag(SECTION_TAG);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = pane.getChildCount() == 0 ? dp(2) : dp(18);
        lp.bottomMargin = dp(2);
        pane.addView(t, lp);
    }

    /**
     * This fork: a note is no longer a paragraph between the rows. It belongs to the row above it, or, right under a
     * section title (or at the top of a page), to every row of that section, and shows in the hint line under the
     * page while one of them has focus (showHint).
     */
    private void note(String s) {
        TextView t = text(15, R.string.font_regular, 0x8CFFFFFF);
        t.setText(s);
        t.setTag(NOTE_TAG);
        t.setVisibility(View.GONE);
        pane.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    /** The hint line: the notes of the row that holds focus, empty (but keeping its room) for a row without any. */
    private void showHint(View focus) {
        if (hint == null) return;
        View row = focus;
        while (row != null && row.getParent() != pane) {
            row = row.getParent() instanceof View ? (View) row.getParent() : null;
        }
        StringBuilder text = new StringBuilder();
        if (row != null) {
            List<CharSequence> sectionNotes = new ArrayList<>();
            List<CharSequence> rowNotes = new ArrayList<>();
            View lastRow = null;
            boolean seen = false;
            for (int i = 0; i < pane.getChildCount(); i++) {
                View c = pane.getChildAt(i);
                if (c.getTag() == SECTION_TAG) {
                    if (seen) break;   // the focused row's section has ended
                    sectionNotes = new ArrayList<>();
                    lastRow = null;
                } else if (c.getTag() == NOTE_TAG) {
                    CharSequence n = ((TextView) c).getText();
                    if (lastRow == null) sectionNotes.add(n);
                    else if (lastRow == row) rowNotes.add(n);
                } else {
                    if (seen) break;   // past the focused row and its own notes
                    lastRow = c;
                    seen = c == row;
                }
            }
            for (CharSequence n : rowNotes.isEmpty() ? sectionNotes : rowNotes) {
                if (text.length() > 0) text.append("  ");
                text.append(n);
            }
        }
        hint.setText(text);
        hint.setVisibility(text.length() > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    // ---- views -------------------------------------------------------------------------------------------

    /** Home's frosted pill (pill_bg): glass at rest, white with dark text with focus; outlined when chosen. */
    private TextView pill(String label) {
        TextView p = text(14, R.string.font_medium, INK);
        p.setText(label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(16), 0, dp(16), 0);
        p.setFocusable(true);
        p.setClickable(true);
        p.setBackground(glass(dp(18)));
        p.setTextColor(ink(INK));
        return p;
    }

    private Drawable glass(int radius) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Accent.fill(accent));
        focused.setCornerRadius(radius);
        GradientDrawable chosen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x47FFFFFF, 0x29FFFFFF});
        chosen.setCornerRadius(radius);
        chosen.setStroke(Math.max(1, Math.round(1.5f * density)), accent == 0 ? 0xD9FFFFFF : Accent.ring(accent));
        GradientDrawable rest = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x2EFFFFFF, 0x14FFFFFF});
        rest.setCornerRadius(radius);
        rest.setStroke(Math.max(1, dp(1)), Accent.edge(accent, 0x38FFFFFF));
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_selected}, chosen);
        s.addState(new int[]{}, rest);
        return s;
    }

    private ColorStateList ink(int rest) {
        if (accent != 0) return Accent.ink(accent, rest);
        return new ColorStateList(new int[][]{{android.R.attr.state_focused}, {}}, new int[]{INK_FOCUSED, rest});
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        e.setImeOptions(EditorInfo.IME_ACTION_DONE);
        e.setHint(hint);
        e.setText(value);
        e.setSelection(value.length());
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        e.setTypeface(UiFont.get(this, R.string.font_regular));
        e.setTextColor(INK);
        e.setHintTextColor(0x66FFFFFF);
        e.setPadding(dp(20), 0, dp(20), 0);
        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(0x33FFFFFF);
        focused.setCornerRadius(dp(16));
        focused.setStroke(dp(2), Accent.ring(accent));
        GradientDrawable rest = new GradientDrawable();
        rest.setColor(0x1FFFFFFF);
        rest.setCornerRadius(dp(16));
        rest.setStroke(Math.max(1, dp(1)), 0x38FFFFFF);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, rest);
        e.setBackground(bg);
        return e;
    }

    private TextView text(float sp, int font, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(this, font));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Whether v is, or holds, the first focusable view of the page. */
    private boolean isFirstFocusable(View v) {
        View first = firstFocusable(pane);
        return first != null && (first == v || isInside(first, v instanceof ViewGroup ? (ViewGroup) v : null));
    }

    private void scrollIntoView(View v) {
        scroll.post(() -> {
            // Gone from the page by now (a Quick settings tile redrawn as it moves, a page left): nothing to show.
            if (!v.isAttachedToWindow() || !isInside(v, pane)) return;
            // The page's first row: show the page from its top, so a note above that row is not cut in half.
            if (isFirstFocusable(v)) {
                scroll.smoothScrollTo(0, 0);
                return;
            }
            android.graphics.Rect r = new android.graphics.Rect();
            v.getDrawingRect(r);
            pane.offsetDescendantRectToMyCoords(v, r);
            int top = r.top - dp(24), bottom = r.bottom + dp(24);
            int y = scroll.getScrollY(), h = scroll.getHeight() - scroll.getPaddingBottom();
            if (top < y) scroll.smoothScrollTo(0, Math.max(0, top));
            else if (bottom > y + h) scroll.smoothScrollTo(0, bottom - h);
        });
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private static String[] numbers(int from, int to) {
        String[] out = new String[to - from + 1];
        for (int i = from; i <= to; i++) out[i - from] = Integer.toString(i);
        return out;
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** A choice was saved: Home rebuilds with it when it comes back (Config.SETTINGS_REVISION_KEY). */
    private void changed() {
        setResult(RESULT_OK);
        prefs.edit().putLong(Config.SETTINGS_REVISION_KEY, prefs.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}
