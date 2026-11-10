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
    private static final int MAX_ITEMS = 40, MAX_CACHED_FILES = 60;
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
    private final File dir;
    private List<Item> items = Collections.emptyList();
    private int next;
    private boolean wanted, shown, loading;
    /** The backdrop on show: A or B; the other takes the next one and fades in over it. */
    private boolean onA = true;
    private Bitmap bitmapA, bitmapB;
    /** One number per step: a picture decoded for an older step (focus left meanwhile) is dropped. */
    private int generation;

    private final Runnable rotate = this::step;

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
        dir = new File(ctx.getCacheDir(), "suggestions");
    }

    static boolean nuvioInstalled(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        for (String pkg : NUVIO_APPS) {
            try {
                pm.getPackageInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        return false;
    }

    /** The offline build has none: it goes online only for updates. */
    static boolean enabled(Context ctx) {
        if (BuildConfig.OFFLINE) return false;
        SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        return p.getBoolean(KEY, nuvioInstalled(ctx));
    }

    /** The dock has focus: fade a suggestion in (fetching the lists first, if they are due). */
    void show() {
        if (!enabled(ctx)) {
            hide();
            return;
        }
        wanted = true;
        if (shown || loading) return;
        if (items.isEmpty() || listStale()) {
            loading = true;
            worker.execute(() -> {
                List<Item> found = loadItems();
                main.post(() -> {
                    loading = false;
                    if (!found.isEmpty()) items = found;
                    if (wanted && !items.isEmpty() && !shown) step();
                });
            });
            return;
        }
        step();
    }

    /** Focus left the dock (or Home went away): fade out and stop rotating. */
    void hide() {
        wanted = false;
        main.removeCallbacks(rotate);
        generation++;
        if (!shown) return;
        shown = false;
        layer.animate().cancel();
        layer.animate().alpha(0f).setDuration(400).withEndAction(() -> {
            if (!shown) {
                layer.setVisibility(View.GONE);
                release();
            }
        }).start();
    }

    /** Home stopped: let go of the pictures at once. */
    void stop() {
        hide();
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
        Item item = items.get(next % items.size());
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
            layer.setVisibility(View.VISIBLE);
            layer.animate().cancel();
            layer.animate().alpha(1f).setDuration(FADE_MS).start();
            return;
        }
        // B lies over A, both under the scrim and the text: B fades in over A, or out to show the new A under it.
        backB.animate().cancel();
        if (in == backB) {
            backB.setAlpha(0f);
            backB.animate().alpha(1f).setDuration(FADE_MS).withLayer().start();
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

    /** Movies and series taking turns; fetched again when the saved copy is older than LIST_MAX_AGE_MS. */
    private List<Item> loadItems() {
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
            List<Item> out = new ArrayList<>();
            for (int i = 0; out.size() < MAX_ITEMS && (i < movies.size() || i < series.size()); i++) {
                if (i < movies.size()) out.add(movies.get(i));
                if (i < series.size() && out.size() < MAX_ITEMS) out.add(series.get(i));
            }
            next = out.isEmpty() ? 0 : new java.util.Random().nextInt(out.size());
            trimCache();
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
            File file = new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length());
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

    /** Keeps the newest pictures only. */
    private void trimCache() {
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_CACHED_FILES) return;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = MAX_CACHED_FILES; i < files.length; i++) files[i].delete();
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
