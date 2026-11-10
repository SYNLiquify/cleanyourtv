package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This fork: Nuvio suggestions behind Home while the dock has focus. The titles are Nuvio's default catalog, Cinemeta's
 * popular movies and series (the same lists Nuvio shows before any add-on is installed): a backdrop, logo, rating,
 * year, genres and a short description each. One title at a time, fading to the next every few seconds; leaving the
 * dock fades them away. The lists are fetched at most every few hours and the pictures cached on the TV, so moving
 * along the dock costs nothing; only two backdrops are held in memory, at screen size in RGB_565.
 * Written for this launcher from the idea in glassio (GPL-3.0); none of its code is used.
 */
final class NuvioSuggestions {

    private static final String TAG = "TvHomeSuggest";
    /** Settings > Home > Nuvio suggestions, in Config.SETTINGS_NAME; on by default where a Nuvio app is installed. */
    static final String KEY = "nuvio_suggestions";
    static final String[] NUVIO_APPS = {"com.nuvio.tv", "space.nuvio.nativelegacy", "com.nuvio.tv.test", "com.nuvioreshaped.tv"};
    private static final String CATALOG = "https://v3-cinemeta.strem.io/catalog/%s/top.json";
    private static final long LIST_MAX_AGE_MS = 6 * 60 * 60 * 1000L;
    private static final long ROTATE_MS = 9_000L;
    private static final long FADE_MS = 900L;
    /** The day's selection (SuggestionPicker): its day, its titles, yesterday's, and every title shown so far. */
    private static final String STATE = "nuvio_suggestions_state";
    private static final int TIMEOUT_MS = 10_000;

    static final class Item {
        String name, type, id, rating, year, meta, background, logo;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final View layer;
    private final ImageView backA, backB, logo;
    private final TextView name, meta;
    private final android.widget.LinearLayout chips;
    private final View info;
    /** Open in Nuvio: Up from the dock reaches it; while it has focus the title on show stays. */
    private final TextView open;
    private Item current;
    private Runnable backToDock = () -> { };
    private final File dir;
    private List<Item> items = Collections.emptyList();
    /** The day (epoch day) the titles on hand were picked for; a new day picks new ones. */
    private long selectionDay = -1;
    /** Keys (IMDb ids, lower-case names) of the titles in Continue watching: those are not suggested. */
    private java.util.function.Supplier<java.util.Set<String>> watching = Collections::emptySet;
    private int next;
    private boolean wanted, shown, loading;
    /** The backdrop on show: A or B; the other takes the next one and fades in over it. */
    private boolean onA = true;
    private Bitmap bitmapA, bitmapB;
    /** One number per step: a picture decoded for an older step (focus left meanwhile) is dropped. */
    private int generation;

    /** Home paused but still on screen (the translucent quick panel over it): hold the picture, don't rotate. */
    private boolean paused;

    /** Told true once the suggestion covers the screen (what is behind it need not draw), false as it goes. */
    private java.util.function.Consumer<Boolean> covering = c -> { };
    private boolean covers;

    void setCovering(java.util.function.Consumer<Boolean> covering) {
        this.covering = covering;
    }

    private void cover(boolean on) {
        if (covers == on) return;
        covers = on;
        covering.accept(on);
    }

    private final Runnable rotate = () -> {
        // Not while Open in Nuvio has focus: the owner is reading this title, or stepping through them with Left/Right.
        if (!paused && !openFocused()) step();
    };

    private boolean openFocused() {
        return open.hasFocus();
    }

    NuvioSuggestions(Context context, View layer) {
        ctx = context.getApplicationContext();
        this.layer = layer;
        backA = layer.findViewById(R.id.suggestionBackA);
        backB = layer.findViewById(R.id.suggestionBackB);
        logo = layer.findViewById(R.id.suggestionLogo);
        name = layer.findViewById(R.id.suggestionName);
        meta = layer.findViewById(R.id.suggestionMeta);
        chips = layer.findViewById(R.id.suggestionChips);
        info = layer.findViewById(R.id.suggestionInfo);
        open = layer.findViewById(R.id.suggestionOpen);
        open.setOnClickListener(v -> openCurrent());
        open.setOnFocusChangeListener((v, focused) -> {
            main.removeCallbacks(rotate);
            if (!focused && wanted) main.postDelayed(rotate, ROTATE_MS);   // back to the dock: rotate again
        });
        open.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) backToDock.run();
                return true;
            }
            // Left and Right step through the suggestions by hand: the previous title, or the next (mirrored in a
            // right-to-left language). One title a press; a held key does not race through them.
            boolean left = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT;
            if (!left && keyCode != android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && items.size() > 1) {
                boolean back = left != (v.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
                // next is the title after the one on show; the one before it is two back.
                if (back) next = ((next - 2) % items.size() + items.size()) % items.size();
                step();
            }
            return true;
        });
        dir = new File(ctx.getCacheDir(), "suggestions");
        // The scrim's three gradients, drawn once into 192x108 and stretched (filtered, so it stays smooth): a frame
        // then fills the screen once for it instead of three times while Home animates over the suggestion.
        Bitmap scrim = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888);
        android.graphics.drawable.Drawable gradients = layer.getContext().getDrawable(R.drawable.suggestion_scrim);
        if (gradients != null) {
            gradients.setBounds(0, 0, 192, 108);
            gradients.draw(new android.graphics.Canvas(scrim));
        }
        android.graphics.drawable.BitmapDrawable baked =
                new android.graphics.drawable.BitmapDrawable(layer.getResources(), scrim);
        baked.setFilterBitmap(true);
        layer.findViewById(R.id.suggestionScrim).setBackground(baked);
    }

    /** Whether a Nuvio app is installed, worked out once; packagesChanged() asks again. Main thread only. */
    private static Boolean installed;

    static boolean nuvioInstalled(Context ctx) {
        if (installed != null) return installed;
        PackageManager pm = ctx.getPackageManager();
        boolean found = false;
        for (String pkg : NUVIO_APPS) {
            try {
                pm.getPackageInfo(pkg, 0);
                found = true;
                break;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        installed = found;
        return found;
    }

    /** An app was installed or removed (Home's package receiver): look for Nuvio again next time. */
    static void packagesChanged() {
        installed = null;
    }

    /** The offline build has none: it goes online only for updates. */
    static boolean enabled(Context ctx) {
        if (BuildConfig.OFFLINE) return false;
        SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        // The saved choice when there is one; the Nuvio check (a PackageManager call per Nuvio app) only otherwise.
        return p.contains(KEY) ? p.getBoolean(KEY, false) : nuvioInstalled(ctx);
    }

    void setWatching(java.util.function.Supplier<java.util.Set<String>> watching) {
        this.watching = watching;
    }

    private static long today() {
        return java.time.LocalDate.now().toEpochDay();
    }

    /** How Down from Open in Nuvio gets back to the dock (Home's last focused tile). */
    void setBackToDock(Runnable back) {
        backToDock = back;
    }

    /** Up from the dock: focus Open in Nuvio when a title is on show; false leaves Up to Home. */
    boolean focusOpen() {
        if (!shown || current == null || layer.getVisibility() != View.VISIBLE) return false;
        return open.requestFocus();
    }

    /** Whether focus on v keeps the suggestions (the button is part of them). */
    boolean owns(View v) {
        return v == open;
    }

    /** Opens the title on show in the installed Nuvio app (its own link for a movie or series). */
    private void openCurrent() {
        Item item = current;
        if (item == null || TextUtils.isEmpty(item.id)) return;
        String type = "series".equals(item.type) ? "series" : "movie";
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : NUVIO_APPS) {
            try {
                pm.getPackageInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException notInstalled) {
                continue;
            }
            android.net.Uri uri = "space.nuvio.nativelegacy".equals(pkg)
                    ? android.net.Uri.parse("nuvio-legacy://meta/" + type + "/" + android.net.Uri.encode(item.id))
                    : android.net.Uri.parse("nuvio://meta?type=" + type + "&id=" + android.net.Uri.encode(item.id));
            android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW, uri).setPackage(pkg)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                layer.getContext().startActivity(view);
                return;
            } catch (android.content.ActivityNotFoundException | SecurityException e) {
                Log.d(TAG, pkg + " cannot open " + uri + ": " + e);
            }
        }
        android.widget.Toast.makeText(layer.getContext(), R.string.not_available, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** The dock has focus: fade a suggestion in (fetching the lists first, if they are due). */
    void show() {
        if (!enabled(ctx)) {
            hide();
            return;
        }
        wanted = true;
        if (shown || loading) return;
        // A new day (the dock is back after midnight) brings a new selection, as a stale list does.
        if (items.isEmpty() || listStale() || selectionDay != today()) {
            loading = true;
            worker.execute(() -> {
                long day = today();
                List<Item> found = loadItems(day);
                main.post(() -> {
                    loading = false;
                    if (!found.isEmpty()) {
                        items = found;
                        selectionDay = day;
                    }
                    if (wanted && !items.isEmpty() && !shown) step();
                });
            });
            return;
        }
        step();
    }

    /** Focus left the dock (or Home went away): fade out and stop rotating. */
    void hide() {
        if (open.hasFocus()) return;   // focus went to Open in Nuvio, which is part of them
        wanted = false;
        main.removeCallbacks(rotate);
        generation++;
        if (!shown) return;
        shown = false;
        current = null;
        cover(false);
        layer.animate().cancel();
        // withLayer: the fade draws the suggestion into a texture once, not into an offscreen buffer every frame.
        layer.animate().alpha(0f).setDuration(400).withLayer().withEndAction(() -> {
            if (!shown) {
                layer.setVisibility(View.GONE);
                release();
            }
        }).start();
    }

    /** Home paused under a window that leaves it visible (the quick panel): keep the picture, stop rotating. */
    void pause() {
        paused = true;
        main.removeCallbacks(rotate);
    }

    /** Home in front again: rotate on from the picture on show. */
    void resume() {
        if (!paused) return;
        paused = false;
        if (shown && wanted) main.postDelayed(rotate, ROTATE_MS);
    }

    /** Home is gone (onDestroy): the worker thread ends, rather than one per recreated Home. */
    void close() {
        stop();
        worker.shutdownNow();
    }

    /** Home stopped: let go of the pictures at once. */
    void stop() {
        paused = false;
        wanted = false;
        hide();
        cover(false);
        layer.animate().cancel();
        layer.setAlpha(0f);
        layer.setVisibility(View.GONE);
        release();
    }

    private void release() {
        backA.setImageDrawable(null);
        backB.setImageDrawable(null);
        logo.setImageDrawable(null);
        if (bitmapA != null) bitmapA.recycle();
        if (bitmapB != null) bitmapB.recycle();
        bitmapA = bitmapB = null;
    }

    /** The next title: decoded off the main thread, then faded in over the one on show. */
    private void step() {
        if (!wanted || items.isEmpty()) return;
        // Not a title already in Continue watching (unless every one of today's is).
        java.util.Set<String> skip = watching.get();
        Item candidate = items.get(next % items.size());
        for (int tries = 0; tries < items.size() && watched(candidate, skip); tries++) {
            next = (next + 1) % items.size();
            candidate = items.get(next);
        }
        Item item = candidate;
        next = (next + 1) % items.size();
        int gen = ++generation;
        int w = Math.max(1, ctx.getResources().getDisplayMetrics().widthPixels);
        worker.execute(() -> {
            Bitmap back = picture(item.background, w, Bitmap.Config.RGB_565);
            Bitmap mark = TextUtils.isEmpty(item.logo) ? null : picture(item.logo, Math.min(w / 2, 900), Bitmap.Config.ARGB_8888);
            main.post(() -> {
                if (gen != generation || !wanted || back == null) {
                    if (back != null) back.recycle();
                    if (mark != null) mark.recycle();
                    if (wanted && gen == generation) main.postDelayed(rotate, 2_000L);   // a picture that failed: try the next
                    return;
                }
                showItem(item, back, mark);
                main.postDelayed(rotate, ROTATE_MS);
            });
        });
    }

    private void showItem(Item item, Bitmap back, Bitmap mark) {
        current = item;
        ImageView in = onA ? backB : backA, out = onA ? backA : backB;
        Bitmap old = onA ? bitmapB : bitmapA;
        in.setImageBitmap(back);
        if (onA) bitmapB = back;
        else bitmapA = back;
        if (old != null && old != back) old.recycle();
        onA = !onA;
        logo.setImageBitmap(mark);
        logo.setVisibility(mark != null ? View.VISIBLE : View.GONE);
        name.setText(item.name);
        name.setVisibility(mark != null ? View.GONE : View.VISIBLE);
        meta.setText(item.meta);
        chips.removeAllViews();
        if (!item.rating.isEmpty()) chips.addView(chip("\u2605 " + item.rating));
        if (!item.year.isEmpty()) chips.addView(chip(item.year));
        chips.setVisibility(chips.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        if (!shown) {
            shown = true;
            in.setAlpha(1f);
            out.setAlpha(0f);
            // A under B: with B on show, A is hidden and not drawn under it.
            backA.setVisibility(in == backB ? View.INVISIBLE : View.VISIBLE);
            layer.setVisibility(View.VISIBLE);
            layer.animate().cancel();
            layer.animate().alpha(1f).setDuration(FADE_MS).withLayer().withEndAction(() -> {
                if (shown) cover(true);
            }).start();
            return;
        }
        // B lies over A, both under the scrim and the text: B fades in over A, or out to show the new A under it.
        backB.animate().cancel();
        backA.setVisibility(View.VISIBLE);
        if (in == backB) {
            backB.setAlpha(0f);
            backB.animate().alpha(1f).setDuration(FADE_MS).withLayer().withEndAction(() -> {
                if (backB.getAlpha() >= 1f) backA.setVisibility(View.INVISIBLE);   // covered: not drawn
            }).start();
        } else {
            backA.setAlpha(1f);
            backB.animate().alpha(0f).setDuration(FADE_MS).withLayer().start();
        }
        info.setAlpha(0f);
        info.animate().alpha(1f).setDuration(FADE_MS).start();
    }

    /** An outlined chip: the rating or the year. */
    private TextView chip(String label) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView t = new TextView(layer.getContext());
        t.setText(label);
        t.setTextColor(0xF2FFFFFF);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        t.setTypeface(UiFont.get(layer.getContext(), R.string.font_medium));
        t.setPadding(Math.round(9 * d), Math.round(2 * d), Math.round(9 * d), Math.round(3 * d));
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setCornerRadius(6 * d);
        box.setStroke(Math.max(1, Math.round(1.5f * d)), 0xB3FFFFFF);
        t.setBackground(box);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(Math.round(10 * d));
        t.setLayoutParams(lp);
        return t;
    }

    // ---- the lists --------------------------------------------------------------------------------------

    private File listFile() {
        return new File(ctx.getFilesDir(), "nuvio_suggestions.json");
    }

    private boolean listStale() {
        return System.currentTimeMillis() - listFile().lastModified() > LIST_MAX_AGE_MS;
    }

    private static boolean watched(Item item, java.util.Set<String> keys) {
        if (keys.isEmpty()) return false;
        return (item.id != null && keys.contains(item.id.toLowerCase(Locale.ROOT)))
                || keys.contains(item.name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * The day's titles (SuggestionPicker), movies and series taking turns. The catalogs are fetched again when the
     * saved copy is older than LIST_MAX_AGE_MS; the selection stays all day, and a new day picks a new one and deletes
     * the previous selection's pictures.
     */
    private List<Item> loadItems(long day) {
        File saved = listFile();
        if (listStale()) {
            try {
                JSONObject both = new JSONObject();
                both.put("movie", new JSONObject(new String(get(String.format(Locale.US, CATALOG, "movie"), 4 << 20), StandardCharsets.UTF_8)));
                both.put("series", new JSONObject(new String(get(String.format(Locale.US, CATALOG, "series"), 4 << 20), StandardCharsets.UTF_8)));
                try (OutputStream out = new FileOutputStream(saved)) {
                    out.write(both.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                Log.d(TAG, "suggestions not refreshed: " + e);
            }
        }
        try (InputStream in = new FileInputStream(saved)) {
            JSONObject both = new JSONObject(new String(read(in, 8 << 20), StandardCharsets.UTF_8));
            List<Item> movies = parse(both.optJSONObject("movie")), series = parse(both.optJSONObject("series"));
            List<Item> out = select(movies, series, day);
            next = out.isEmpty() ? 0 : new java.util.Random().nextInt(out.size());
            keepPicturesOf(out);
            return out;
        } catch (Exception e) {
            Log.d(TAG, "no suggestions: " + e);
            return Collections.emptyList();
        }
    }

    private static List<Item> parse(JSONObject catalog) {
        List<Item> out = new ArrayList<>();
        JSONArray metas = catalog == null ? null : catalog.optJSONArray("metas");
        for (int i = 0; metas != null && i < metas.length(); i++) {
            JSONObject m = metas.optJSONObject(i);
            if (m == null) continue;
            Item it = new Item();
            it.name = m.optString("name", "").trim();
            it.type = m.optString("type", "movie");
            it.id = m.optString("imdb_id", m.optString("id", ""));
            it.background = https(m.optString("background", ""));
            it.logo = https(m.optString("logo", ""));
            if (it.name.isEmpty() || it.background.isEmpty()) continue;
            it.rating = m.optString("imdbRating", "").trim();
            // The first year only: "2008-2015" shows as 2008.
            java.util.regex.Matcher y = java.util.regex.Pattern.compile("\\d{4}")
                    .matcher(m.optString("releaseInfo", m.optString("year", "")));
            it.year = y.find() ? y.group() : "";
            // "Series, Comedy, Animation" with dots between: the kind, then up to two genres.
            List<String> parts = new ArrayList<>();
            parts.add("series".equals(it.type) ? "Series" : "Movie");
            JSONArray genres = m.optJSONArray("genres");
            for (int j = 0; genres != null && j < genres.length() && parts.size() < 3; j++) parts.add(genres.optString(j));
            it.meta = TextUtils.join("  \u00b7  ", parts);
            out.add(it);
        }
        return out;
    }

    /** Only https pictures, as for Continue watching. */
    private static String https(String url) {
        return url != null && url.startsWith("https://") ? url : "";
    }

    // ---- pictures ---------------------------------------------------------------------------------------

    /** A picture from the cache or the network, sampled down to about maxWidth. */
    private Bitmap picture(String url, int maxWidth, Bitmap.Config config) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File file = cached(url);
            if (!file.isFile() || file.length() == 0) {
                byte[] bytes = get(url, 6 << 20);
                File tmp = new File(dir, file.getName() + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(bytes);
                }
                if (!tmp.renameTo(file)) tmp.delete();
            } else {
                file.setLastModified(System.currentTimeMillis());   // recently shown: last to be trimmed
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            if (o.outWidth <= 0) {
                file.delete();
                return null;
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = config;
            return BitmapFactory.decodeFile(file.getPath(), o);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.d(TAG, "picture not loaded: " + e);
            return null;
        }
    }

    /** Today's selection: the one saved for today, or a new pick (saved, with yesterday's and the titles seen). */
    private List<Item> select(List<Item> movies, List<Item> series, long day) {
        SharedPreferences state = ctx.getSharedPreferences(STATE, Context.MODE_PRIVATE);
        if (state.getLong("day", -1) == day) {
            java.util.Map<String, Item> byKey = new java.util.HashMap<>();
            for (Item it : movies) byKey.put(SuggestionPicker.key(it), it);
            for (Item it : series) byKey.put(SuggestionPicker.key(it), it);
            List<Item> kept = new ArrayList<>();
            for (String key : state.getString("today", "").split("\n")) {
                Item it = byKey.get(key);
                if (it != null) kept.add(it);
            }
            if (!kept.isEmpty()) return kept;
        }
        java.util.Set<String> yesterday = lines(state.getString("today", ""));
        java.util.Set<String> seen = lines(state.getString("seen", ""));
        SuggestionPicker.Pick pick = SuggestionPicker.pick(movies, series, seen, yesterday, day);
        java.util.Set<String> picked = new java.util.LinkedHashSet<>();
        for (Item it : pick.items) picked.add(SuggestionPicker.key(it));
        if (pick.restarted) seen = new java.util.HashSet<>(yesterday);   // every title had its turn: over again
        seen.addAll(picked);
        state.edit().putLong("day", day).putString("today", TextUtils.join("\n", picked))
                .putString("seen", TextUtils.join("\n", seen)).apply();
        Log.i(TAG, "day " + day + ": " + pick.items.size() + " titles" + (pick.restarted ? ", all seen: starting over" : ""));
        return pick.items;
    }

    private static java.util.Set<String> lines(String text) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String s : text.split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** The picture cache's file for a url. */
    private File cached(String url) {
        return new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length());
    }

    /** Deletes every cached picture that is not one of these titles' (the previous selection's, half-written ones). */
    private void keepPicturesOf(List<Item> titles) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Item it : titles) {
            if (!TextUtils.isEmpty(it.background)) keep.add(cached(it.background).getName());
            if (!TextUtils.isEmpty(it.logo)) keep.add(cached(it.logo).getName());
        }
        File[] files = dir.listFiles();
        if (files == null) return;
        int removed = 0;
        for (File f : files) if (!keep.contains(f.getName()) && f.delete()) removed++;
        if (removed > 0) Log.i(TAG, "removed " + removed + " pictures of an earlier selection");
    }

    private static byte[] get(String url, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return read(in, max);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[32 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) {
            if (out.size() + n > max) throw new IOException("too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
