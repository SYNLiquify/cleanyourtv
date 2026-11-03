package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The repo's themes, for the Settings menu (this fork). config.json and screenshot.jpg of each theme are in the
 * APK's assets (app/build.gradle, bundleThemes); applying one writes its config.json where tools/theme.ps1 would put
 * it (Config.dir) and downloads its wallpapers from the repo on GitHub into the same place, as theme.ps1 pushes them.
 */
final class ThemeLibrary {

    static final String TAG = "TvHomeThemes";
    private static final String RAW = "https://raw.githubusercontent.com/Gathin23/cleanyourtv/main/themes/";
    /** Wallpaper paths the index may name: the single picture, or one file in wallpapers/ (as b64push.py allows). */
    private static final Pattern FILE = Pattern.compile("wallpaper\\.jpg|wallpapers/[A-Za-z0-9._-]+");
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]+");
    /** Keys of a theme that would replace the owner's own apps rather than the look: left out on apply. */
    private static final String[] OWNER_KEYS = {"dock_apps", "hidden_apps", "app_keys", "top_bar_apps", "library_apps"};
    /** On-TV choices Config clears when the theme changes; kept across a switch made from the Settings menu. */
    private static final String[] KEPT_CHOICES = {"columns", Config.ROWS_BELOW_DOCK_KEY, "animations", "advanced_effects",
            Config.DOCK_OPACITY_KEY, Config.CLOCK_FORMAT_KEY};
    static final String CURRENT_KEY = "theme_name";

    static final class Theme {
        final String name;
        final List<String> files;
        /** The repo's blob ids of its config.json and screenshot.jpg; null in the list bundled with the APK. */
        final String configSha, shotSha;

        Theme(String name, List<String> files, String configSha, String shotSha) {
            this.name = name;
            this.files = files;
            this.configSha = configSha;
            this.shotSha = shotSha;
        }

        /** "nature-calm" as "Nature Calm". */
        String title() {
            StringBuilder sb = new StringBuilder();
            for (String part : name.split("-")) {
                if (part.isEmpty()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
            }
            return sb.toString();
        }
    }

    interface Progress {
        void onFile(int done, int total);
    }

    private static final String TREE = "https://api.github.com/repos/Gathin23/cleanyourtv/git/trees/main?recursive=1";
    /** A theme list checked this recently is used as it is, with no call to GitHub. */
    private static final long RECHECK_MS = 10 * 60 * 1000L;
    /** The API's full tree is read again at least this often, for changes the README does not show. */
    private static final long API_MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final String README = RAW + "README.md";

    /**
     * {status, ETag} of the themes README on raw.githubusercontent.com, asked conditionally with known: "304" when it
     * has not changed since. {"error", null} when it could not be reached; the caller then reads the API as it would
     * without a README check.
     */
    private static String[] readmeTag(String known) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(README).openConnection();
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            if (known != null) c.setRequestProperty("If-None-Match", known);
            try {
                int code = c.getResponseCode();
                return new String[]{Integer.toString(code), c.getHeaderField("ETag")};
            } finally {
                c.disconnect();
            }
        } catch (IOException e) {
            return new String[]{"error", null};
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void writeText(File f, String s) throws IOException {
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** The themes as last read from GitHub (refresh), or the list bundled with the APK before any refresh. */
    static List<Theme> list(Context ctx) {
        File saved = new File(store(ctx), "index.json");
        if (saved.isFile()) {
            try (InputStream in = new java.io.FileInputStream(saved)) {
                List<Theme> themes = parse(new String(read(in, 1 << 20), StandardCharsets.UTF_8));
                if (!themes.isEmpty()) return themes;
            } catch (IOException | org.json.JSONException e) {
                Log.w(TAG, "saved theme index unreadable: " + e);
            }
        }
        try (InputStream in = ctx.getAssets().open("themes/index.json")) {
            return parse(new String(read(in, 1 << 20), StandardCharsets.UTF_8));
        } catch (IOException | org.json.JSONException e) {
            Log.w(TAG, "no theme index: " + e);
            return new ArrayList<>();
        }
    }

    /**
     * The repo's themes as they are on GitHub now: one call for the whole file tree (GitHub's API, no key), saved for
     * list() and for when GitHub cannot be reached. Every theme with a config.json and a screenshot.jpg, with its
     * wallpapers. Blocking; off the main thread. Throws when GitHub did not answer (offline, or its hourly limit).
     */
    static List<Theme> refresh(Context ctx) throws IOException {
        return refresh(ctx, false);
    }

    /** force: check even if the list was checked in the last few minutes (Settings' Refresh button). */
    static List<Theme> refresh(Context ctx, boolean force) throws IOException {
        File dir = store(ctx);
        File saved = new File(dir, "index.json"), apiStamp = new File(dir, "index.api"), readmeTag = new File(dir, "readme.etag");
        long now = System.currentTimeMillis();
        // GitHub's API allows about 60 calls an hour per address without an account, fewer behind a shared VPN
        // address, and an unchanged (304) answer still counts. So the API is the last resort: a check within the
        // last few minutes is not repeated; otherwise the themes README (a row per theme) is asked for on
        // raw.githubusercontent.com, which that limit does not cover, conditionally, and an unchanged README keeps
        // the saved list. The API's full tree is read only when the README changed, there is no list yet, or the
        // list is a day old (a theme's new wallpapers do not touch the README).
        if (!force && saved.isFile() && now - saved.lastModified() < RECHECK_MS) {
            Log.d(TAG, "theme list checked recently; no call to GitHub");
            return list(ctx);
        }
        String apiTime = readText(apiStamp);
        boolean apiFresh = saved.isFile() && apiTime != null && now - parseLong(apiTime) < API_MAX_AGE_MS;
        String knownReadme = readText(readmeTag);
        String[] readme = readmeTag(apiFresh ? knownReadme : null);
        if (apiFresh && "304".equals(readme[0])) {
            Log.d(TAG, "themes README unchanged; saved list kept, no API call");
            saved.setLastModified(now);
            return list(ctx);
        }
        byte[] body;
        HttpURLConnection c = (HttpURLConnection) new URL(TREE).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "cleanyourtv-launcher");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = c.getResponseCode();
            Log.d(TAG, "GitHub API theme list: HTTP " + code + ", "
                    + c.getHeaderField("X-RateLimit-Remaining") + " calls left this hour");
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("GitHub answered HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                body = read(in, 4 << 20);
            }
        } finally {
            c.disconnect();
        }
        String newReadmeTag = readme[1];
        try {
            JSONObject tree = new JSONObject(new String(body, StandardCharsets.UTF_8));
            java.util.Map<String, JSONObject> byName = new java.util.TreeMap<>();
            JSONArray entries = tree.getJSONArray("tree");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                String path = e.optString("path");
                if (!"blob".equals(e.optString("type")) || !path.startsWith("themes/")) continue;
                String rest = path.substring("themes/".length());
                int slash = rest.indexOf('/');
                if (slash <= 0) continue;
                String name = rest.substring(0, slash), file = rest.substring(slash + 1);
                if (!NAME.matcher(name).matches()) continue;
                JSONObject t = byName.get(name);
                if (t == null) {
                    t = new JSONObject().put("name", name).put("files", new JSONArray());
                    byName.put(name, t);
                }
                if (file.equals("config.json")) t.put("config_sha", e.optString("sha"));
                else if (file.equals("screenshot.jpg")) t.put("shot_sha", e.optString("sha"));
                else if (FILE.matcher(file).matches()) t.getJSONArray("files").put(file);
            }
            JSONArray index = new JSONArray();
            for (JSONObject t : byName.values()) if (t.has("config_sha") && t.has("shot_sha")) index.put(t);
            String json = index.toString();
            List<Theme> themes = parse(json);
            if (themes.isEmpty()) throw new IOException("no themes in the repo tree");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no room for the theme index");
            try (OutputStream out = new FileOutputStream(saved)) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            writeText(apiStamp, Long.toString(System.currentTimeMillis()));
            if (newReadmeTag != null) writeText(readmeTag, newReadmeTag);
            return themes;
        } catch (org.json.JSONException e) {
            throw new IOException("repo tree unreadable: " + e.getMessage());
        }
    }

    private static List<Theme> parse(String json) throws org.json.JSONException {
        List<Theme> out = new ArrayList<>();
        JSONArray index = new JSONArray(json);
        for (int i = 0; i < index.length(); i++) {
            JSONObject t = index.getJSONObject(i);
            String name = t.getString("name");
            if (!NAME.matcher(name).matches()) continue;
            List<String> files = new ArrayList<>();
            JSONArray f = t.getJSONArray("files");
            for (int j = 0; j < f.length(); j++) if (FILE.matcher(f.getString(j)).matches()) files.add(f.getString(j));
            out.add(new Theme(name, files, t.optString("config_sha", null), t.optString("shot_sha", null)));
        }
        return out;
    }

    /** Where the GitHub index and the previews downloaded from it are kept (app storage, not the theme folder). */
    private static File store(Context ctx) {
        return new File(ctx.getFilesDir(), "themes");
    }

    /**
     * The theme's screenshot, sampled down to about this width: the copy saved here, or the bundled one. With fetch,
     * the repo's current version is downloaded first when GitHub's id for it changed (Settings asks for that only once
     * the owner is in the Themes page); the saved copy stays when it cannot be fetched.
     */
    static Bitmap preview(Context ctx, Theme theme, int width, boolean fetch) {
        File dir = new File(store(ctx), theme.name);
        File shot = new File(dir, "screenshot.jpg");
        if (fetch && previewStale(ctx, theme)) {
            File stamp = new File(dir, "screenshot.sha");
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no room for previews");
                File tmp = new File(dir, "screenshot.tmp");
                download(RAW + theme.name + "/screenshot.jpg", tmp);
                if (!tmp.renameTo(shot)) throw new IOException("cannot keep the preview");
                try (OutputStream out = new FileOutputStream(stamp)) {
                    out.write(theme.shotSha.getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Log.d(TAG, "preview not refreshed for " + theme.name + ": " + e);
            }
        }
        if (shot.isFile()) {
            Bitmap b = decode(() -> new java.io.FileInputStream(shot), width);
            if (b != null) return b;
        }
        return decode(() -> ctx.getAssets().open("themes/" + theme.name + "/screenshot.jpg"), width);
    }

    /** True when GitHub has a newer screenshot for the theme than the copy saved here. */
    static boolean previewStale(Context ctx, Theme theme) {
        return theme.shotSha != null
                && !theme.shotSha.equals(readText(new File(new File(store(ctx), theme.name), "screenshot.sha")));
    }

    private interface Source {
        InputStream open() throws IOException;
    }

    private static Bitmap decode(Source source, int width) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = source.open()) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= width) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream in = source.open()) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String readText(File f) {
        if (!f.isFile()) return null;
        try (InputStream in = new java.io.FileInputStream(f)) {
            return new String(read(in, 1 << 20), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    static String current(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(CURRENT_KEY, "");
    }

    /**
     * Applies a theme: downloads its wallpapers into a staging folder first, so a failed download leaves the current
     * theme as it was, then swaps them in with its config.json. Blocking; run it off the main thread.
     */
    static void apply(Context ctx, Theme theme, Progress progress) throws IOException {
        File dir = Config.dir(ctx);
        if (dir == null) throw new IOException("no storage for the theme");
        File staging = new File(dir, ".theme-staging");
        deleteTree(staging);
        if (!staging.mkdirs()) throw new IOException("cannot stage the theme");
        try {
            int done = 0;
            for (String path : theme.files) {
                progress.onFile(done, theme.files.size());
                File target = new File(staging, path);
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot stage " + path);
                download(RAW + theme.name + "/" + path, target);
                done++;
            }
            progress.onFile(done, theme.files.size());
            // The repo's current config.json; the copy bundled with the APK only for a theme it still knows.
            String config;
            try {
                config = new String(get(RAW + theme.name + "/config.json", 1 << 20), StandardCharsets.UTF_8);
            } catch (IOException offline) {
                try (InputStream in = ctx.getAssets().open("themes/" + theme.name + "/config.json")) {
                    config = new String(read(in, 1 << 20), StandardCharsets.UTF_8);
                }
            }
            JSONObject json = new JSONObject(config);
            for (String key : OWNER_KEYS) json.remove(key);
            String clean = json.toString(2);
            keep(ctx, theme, staging, clean);
            install(ctx, dir, staging, clean, theme.name);
        } catch (org.json.JSONException e) {
            throw new IOException("theme config unreadable: " + e.getMessage());
        } finally {
            deleteTree(staging);
        }
    }

    // ---- downloaded themes ------------------------------------------------------------------------------------

    /** Where a theme applied once is kept (its config.json and wallpapers), to switch back to without a download. */
    private static File saved(Context ctx, String name) {
        return new File(new File(store(ctx), name), "saved");
    }

    /** Keeps a copy of a theme just downloaded (and its preview, for the Downloaded section). A failure only means no copy. */
    private static void keep(Context ctx, Theme theme, File staging, String config) {
        File dst = saved(ctx, theme.name);
        try {
            deleteTree(dst);
            copyTree(staging, dst);
            writeText(new File(dst, Config.FILE_NAME), config);
            File shot = new File(new File(store(ctx), theme.name), "screenshot.jpg");
            if (!shot.isFile() && theme.shotSha != null) {
                download(RAW + theme.name + "/screenshot.jpg", shot);
                writeText(new File(shot.getParentFile(), "screenshot.sha"), theme.shotSha);
            }
        } catch (IOException e) {
            Log.w(TAG, "downloaded copy of " + theme.name + " not kept: " + e);
            deleteTree(dst);
        }
    }

    /** The themes kept on this TV, by name. */
    static List<Theme> downloaded(Context ctx) {
        List<Theme> out = new ArrayList<>();
        File[] dirs = store(ctx).listFiles();
        if (dirs == null) return out;
        java.util.Arrays.sort(dirs);
        for (File d : dirs) {
            File copy = new File(d, "saved");
            if (!NAME.matcher(d.getName()).matches() || !new File(copy, Config.FILE_NAME).isFile()) continue;
            List<String> files = new ArrayList<>();
            if (new File(copy, Config.WALLPAPER_NAME).isFile()) files.add(Config.WALLPAPER_NAME);
            File[] walls = new File(copy, "wallpapers").listFiles();
            if (walls != null) for (File w : walls) files.add("wallpapers/" + w.getName());
            out.add(new Theme(d.getName(), files, null, null));
        }
        return out;
    }

    /** Applies a theme kept on this TV: no download. */
    static void applyDownloaded(Context ctx, Theme theme) throws IOException {
        File dir = Config.dir(ctx);
        if (dir == null) throw new IOException("no storage for the theme");
        File copy = saved(ctx, theme.name);
        File staging = new File(dir, ".theme-staging");
        deleteTree(staging);
        try {
            copyTree(copy, staging);
            File config = new File(staging, Config.FILE_NAME);
            String json = readText(config);
            if (json == null) throw new IOException("no config kept for " + theme.name);
            config.delete();
            install(ctx, dir, staging, json, theme.name);
        } finally {
            deleteTree(staging);
        }
    }

    /** Deletes a theme kept on this TV; one in use stays in use. */
    static void removeDownloaded(Context ctx, String name) {
        if (NAME.matcher(name).matches()) deleteTree(saved(ctx, name));
    }

    private static void copyTree(File from, File to) throws IOException {
        if (from.isDirectory()) {
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("cannot create " + to);
            File[] children = from.listFiles();
            if (children != null) for (File c : children) copyTree(c, new File(to, c.getName()));
        } else {
            java.nio.file.Files.copy(from.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Back to the launcher's own look: no theme config, no wallpapers. */
    static void reset(Context ctx) throws IOException {
        File dir = Config.dir(ctx);
        if (dir == null) throw new IOException("no storage");
        install(ctx, dir, null, null, "");
    }

    private static void install(Context ctx, File dir, File staging, String config, String name) throws IOException {
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        // Config clears these when it sees a new theme; the owner chose them in Settings, so they come back after.
        SharedPreferences.Editor keep = prefs.edit();
        for (String key : KEPT_CHOICES) {
            Object v = prefs.getAll().get(key);
            if (v instanceof Integer) keep.putInt(key, (Integer) v);
            else if (v instanceof Boolean) keep.putBoolean(key, (Boolean) v);
            else if (v instanceof String) keep.putString(key, (String) v);
        }
        new File(dir, Config.WALLPAPER_NAME).delete();
        deleteTree(new File(dir, "wallpapers"));
        if (staging != null) {
            File[] staged = staging.listFiles();
            if (staged != null) for (File f : staged) {
                if (!f.renameTo(new File(dir, f.getName()))) throw new IOException("cannot install " + f.getName());
            }
        }
        File configFile = new File(dir, Config.FILE_NAME);
        if (config == null) {
            configFile.delete();
        } else {
            try (OutputStream out = new FileOutputStream(configFile)) {
                out.write(config.getBytes(StandardCharsets.UTF_8));
            }
        }
        prefs.edit().remove(Config.WALLPAPER_CHOICE_KEY).putString(CURRENT_KEY, name).commit();
        Config.load(ctx);   // sees the new theme now, and clears the choices it clears on a theme change
        keep.putLong(Config.SETTINGS_REVISION_KEY, prefs.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).commit();
    }

    private static void download(String url, File target) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[32 * 1024];
                long total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > 8L * 1024 * 1024) throw new IOException("wallpaper too large");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] get(String url, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setRequestProperty("User-Agent", "cleanyourtv-launcher");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            try (InputStream in = c.getInputStream()) {
                return read(in, max);
            }
        } finally {
            c.disconnect();
        }
    }

    private static byte[] read(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
            total += n;
            if (total > max) throw new IOException("too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteTree(c);
        f.delete();
    }
}
