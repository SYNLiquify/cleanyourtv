package com.gathin.tvhome;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.icu.text.PluralRules;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Downloadable languages (this fork). The APK carries English only (resourceConfigurations in build.gradle); every
 * other language is a pack downloaded on demand from this fork's GitHub branch: the same res/values-xx/*.xml the
 * repo keeps as sources, parsed on the TV into files/languages/<tag>.json. A chosen pack translates the launcher at
 * runtime: {@link Translated} answers getString and friends from it, and {@link #hook} sets the text that layout files
 * name (@string/ in android:text, hint, contentDescription, TextClock formats, fontFamily) as views are inflated.
 * Strings a pack lacks fall back to English. Installed packs are fetched again once per launcher version (refreshIfUpdated), so
 * new text gets translated too.
 */
final class LanguagePacks {

    static final String TAG = "TvHomeLang";
    private static final String BASE =
            "https://raw.githubusercontent.com/" + Updates.REPO + "/apple-tv-home/launcher/app/src/main/res/";
    private static final String[] FILES = {"strings.xml", "settings_menu.xml", "weather.xml", "continue_watching.xml",
            "lunar.xml", "fonts.xml", "bools.xml"};
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final int TIMEOUT_MS = 15000;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    private static final Map<String, Pack> CACHE = new HashMap<>();

    private LanguagePacks() {
    }

    /** One language's text: strings, plurals (name → quantity → text) and the few bools a language changes. */
    static final class Pack {
        final Map<String, String> strings = new HashMap<>();
        final Map<String, Map<String, String>> plurals = new HashMap<>();
        final Map<String, Boolean> bools = new HashMap<>();
        final Map<String, String[]> arrays = new HashMap<>();
    }

    // ---- which packs ------------------------------------------------------------------------------------------

    /** The res folders a tag's pack is made of, the parent first (Latin American Spanish builds on Spanish). */
    static String[] folders(String tag) {
        switch (tag) {
            case "es-419": return new String[]{"values-es", "values-b+es+419"};
            case "pt-BR": return new String[]{"values-pt"};
            case "pt-PT": return new String[]{"values-pt", "values-pt-rPT"};
            case "sr-Latn": return new String[]{"values-b+sr+Latn"};
            case "id": return new String[]{"values-in"};
            case "he": return new String[]{"values-iw"};
            case "zh-CN": return new String[]{"values-zh-rCN"};
            case "zh-TW": return new String[]{"values-zh-rTW"};
            default: return new String[]{"values-" + tag};
        }
    }

    static boolean builtIn(String tag) {
        // The offline build carries every language in the APK (build.gradle) and downloads none.
        if (BuildConfig.OFFLINE) return true;
        return tag == null || tag.isEmpty() || tag.equalsIgnoreCase("en");
    }

    private static File dir(Context ctx) {
        return new File(ctx.getFilesDir(), "languages");
    }

    private static File file(Context ctx, String tag) {
        return new File(dir(ctx), tag + ".json");
    }

    static boolean installed(Context ctx, String tag) {
        return builtIn(tag) || file(ctx, tag).isFile();
    }

    /** The downloaded packs' tags. */
    static Set<String> installedTags(Context ctx) {
        Set<String> out = new LinkedHashSet<>();
        File[] files = dir(ctx).listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".json")) out.add(n.substring(0, n.length() - 5));
            }
        }
        return out;
    }

    static long size(Context ctx, String tag) {
        return builtIn(tag) ? 0 : file(ctx, tag).length();
    }

    static boolean delete(Context ctx, String tag) {
        synchronized (CACHE) {
            CACHE.remove(tag);
        }
        return !builtIn(tag) && file(ctx, tag).delete();
    }

    // ---- the pack in use ----------------------------------------------------------------------------------------

    /**
     * The installed pack whose language best matches locale (its region first, then the language alone), or null:
     * English, or a language that is not installed.
     */
    static String installedTagFor(Context ctx, Locale locale) {
        return bestTag(installedTags(ctx), locale);
    }

    /** Of tags, the one that fits locale best (same region or script, then no region, then any), or null. */
    static String bestTag(Iterable<String> tags, Locale locale) {
        if (locale == null || "en".equals(locale.getLanguage())) return null;
        String best = null;
        int bestScore = 0;
        for (String tag : tags) {
            Locale t = Locale.forLanguageTag(tag);
            if (!t.getLanguage().equals(locale.getLanguage())) continue;
            int score = 1;
            if (t.getCountry().isEmpty() && t.getScript().isEmpty()) score = 2;
            if (!t.getCountry().isEmpty() && t.getCountry().equals(locale.getCountry())) score = 3;
            if (!t.getScript().isEmpty() && t.getScript().equals(locale.getScript())) score = 3;
            if ("zh-TW".equals(tag) && traditionalChinese(locale)) score = 3;
            if (score > bestScore) {
                best = tag;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean traditionalChinese(Locale l) {
        String c = l.getCountry();
        return "Hant".equals(l.getScript()) || "TW".equals(c) || "HK".equals(c) || "MO".equals(c);
    }

    static Pack load(Context ctx, String tag) {
        synchronized (CACHE) {
            Pack p = CACHE.get(tag);
            if (p != null) return p;
        }
        File f = file(ctx, tag);
        if (!f.isFile()) return null;
        try {
            JSONObject o = new JSONObject(read(f));
            Pack p = new Pack();
            JSONObject s = o.optJSONObject("strings");
            for (Iterator<String> it = s == null ? null : s.keys(); it != null && it.hasNext(); ) {
                String k = it.next();
                p.strings.put(k, s.getString(k));
            }
            JSONObject pl = o.optJSONObject("plurals");
            for (Iterator<String> it = pl == null ? null : pl.keys(); it != null && it.hasNext(); ) {
                String k = it.next();
                JSONObject q = pl.getJSONObject(k);
                Map<String, String> m = new HashMap<>();
                for (Iterator<String> qi = q.keys(); qi.hasNext(); ) {
                    String qk = qi.next();
                    m.put(qk, q.getString(qk));
                }
                p.plurals.put(k, m);
            }
            JSONObject b = o.optJSONObject("bools");
            for (Iterator<String> it = b == null ? null : b.keys(); it != null && it.hasNext(); ) {
                String k = it.next();
                p.bools.put(k, b.getBoolean(k));
            }
            JSONObject a = o.optJSONObject("arrays");
            for (Iterator<String> it = a == null ? null : a.keys(); it != null && it.hasNext(); ) {
                String k = it.next();
                JSONArray items = a.getJSONArray(k);
                String[] out = new String[items.length()];
                for (int i = 0; i < out.length; i++) out[i] = items.getString(i);
                p.arrays.put(k, out);
            }
            synchronized (CACHE) {
                CACHE.put(tag, p);
            }
            return p;
        } catch (Exception e) {
            Log.w(TAG, "could not read " + f + ": " + e);
            return null;
        }
    }

    /** base with this pack's Resources on top, configured for locale. */
    static Context wrap(Context base, Locale locale, Pack pack) {
        Configuration c = new Configuration(base.getResources().getConfiguration());
        c.setLocale(locale);
        Context configured = base.createConfigurationContext(c);
        Translated res = new Translated(configured.getResources(), pack, locale);
        return new ContextWrapper(configured) {
            private LayoutInflater inflater;

            @Override
            public Resources getResources() {
                return res;
            }

            // Activities, dialogs and the dream clone their inflater from this one, and a clone keeps its factory,
            // so every layout the launcher inflates gets its text from the pack.
            @Override
            public Object getSystemService(String name) {
                if (!LAYOUT_INFLATER_SERVICE.equals(name)) return super.getSystemService(name);
                if (inflater == null) {
                    inflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
                    hook(inflater, res);
                }
                return inflater;
            }
        };
    }

    // ---- download -------------------------------------------------------------------------------------------

    /** Downloads tag's pack from GitHub and saves it; done(true) on the main thread once it is ready. */
    static void install(Context context, String tag, Consumer<Boolean> done) {
        if (BuildConfig.OFFLINE) {
            done.accept(false);
            return;
        }
        Context ctx = context.getApplicationContext();
        WORKER.execute(() -> {
            boolean ok = false;
            try {
                Pack p = new Pack();
                int files = 0;
                for (String folder : folders(tag)) {
                    for (String name : FILES) {
                        String xml = get(BASE + folder + "/" + name);
                        if (xml == null) continue;
                        parse(xml, p);
                        files++;
                    }
                }
                if (files == 0 || p.strings.isEmpty()) throw new IOException("no translation found for " + tag);
                save(ctx, tag, p);
                Log.i(TAG, "installed " + tag + ": " + p.strings.size() + " strings from " + files + " files");
                synchronized (CACHE) {
                    CACHE.put(tag, p);
                }
                ok = true;
            } catch (Exception e) {
                Log.w(TAG, "could not download " + tag + ": " + e);
            }
            boolean result = ok;
            if (done != null) new Handler(Looper.getMainLooper()).post(() -> done.accept(result));
        });
    }

    private static boolean fetchedWanted;

    /**
     * Once per launcher version: fetches every installed pack again, so text a launcher update added or changed is
     * translated too. Home calls it as it starts; a pack that fails to download keeps its old text.
     * The language the launcher should be in (chosen, or the TV's) is fetched too when it is missing, so a TV that
     * showed Spanish before this fork went English-only stays in Spanish; home is rebuilt once it is in. That one is
     * tried once per process start until it is in.
     */
    static void refreshIfUpdated(android.app.Activity home) {
        if (BuildConfig.OFFLINE) return;
        Context ctx = home.getApplicationContext();
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("languages", Context.MODE_PRIVATE);
        int code = Updates.installedCode(ctx);
        if (prefs.getInt("refreshed_for", -1) != code) {
            prefs.edit().putInt("refreshed_for", code).apply();
            for (String tag : installedTags(ctx)) install(ctx, tag, null);
        }
        if (fetchedWanted) return;
        fetchedWanted = true;
        List<String> available = new ArrayList<>();
        for (String[] l : SettingsActivity.LANGUAGES) available.add(l[0]);
        Locale want = LauncherLocale.wanted(ctx);
        String wanted = bestTag(available, want);
        Log.i(TAG, "language wanted " + want + ": pack " + wanted + (wanted != null && installed(ctx, wanted) ? " (installed)" : ""));
        if (wanted == null || installed(ctx, wanted)) return;
        install(ctx, wanted, ok -> {
            if (ok && !home.isFinishing() && !home.isDestroyed()) home.recreate();
        });
    }

    private static void save(Context ctx, String tag, Pack p) throws Exception {
        JSONObject o = new JSONObject();
        JSONObject s = new JSONObject();
        for (Map.Entry<String, String> e : p.strings.entrySet()) s.put(e.getKey(), e.getValue());
        JSONObject pl = new JSONObject();
        for (Map.Entry<String, Map<String, String>> e : p.plurals.entrySet()) pl.put(e.getKey(), new JSONObject(e.getValue()));
        JSONObject b = new JSONObject();
        for (Map.Entry<String, Boolean> e : p.bools.entrySet()) b.put(e.getKey(), e.getValue());
        JSONObject a = new JSONObject();
        for (Map.Entry<String, String[]> e : p.arrays.entrySet()) {
            JSONArray items = new JSONArray();
            for (String item : e.getValue()) items.put(item);
            a.put(e.getKey(), items);
        }
        o.put("strings", s).put("plurals", pl).put("bools", b).put("arrays", a);
        File d = dir(ctx);
        if (!d.isDirectory() && !d.mkdirs()) throw new IOException("cannot create " + d);
        File tmp = new File(d, tag + ".json.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(file(ctx, tag))) throw new IOException("cannot save " + tag);
    }

    /** Reads Android string resources: string, string-array, plurals and bool, with Android's escapes and quoting undone. */
    static void parse(String xml, Pack into) throws Exception {
        XmlPullParser x = Xml.newPullParser();
        x.setInput(new StringReader(xml));
        String plural = null, array = null;
        List<String> items = new ArrayList<>();
        for (int ev = x.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = x.getName();
                String name = x.getAttributeValue(null, "name");
                if ("string".equals(tag) && name != null) {
                    into.strings.put(name, unescape(innerText(x)));
                } else if ("plurals".equals(tag) && name != null) {
                    plural = name;
                    into.plurals.put(name, new HashMap<>());
                } else if ("string-array".equals(tag) && name != null) {
                    array = name;
                    items.clear();
                } else if ("item".equals(tag) && array != null) {
                    items.add(unescape(innerText(x)));
                } else if ("item".equals(tag) && plural != null) {
                    String q = x.getAttributeValue(null, "quantity");
                    String text = unescape(innerText(x));
                    if (q != null) into.plurals.get(plural).put(q, text);
                } else if ("bool".equals(tag) && name != null) {
                    into.bools.put(name, Boolean.parseBoolean(innerText(x).trim()));
                }
            } else if (ev == XmlPullParser.END_TAG && "plurals".equals(x.getName())) {
                plural = null;
            } else if (ev == XmlPullParser.END_TAG && "string-array".equals(x.getName()) && array != null) {
                into.arrays.put(array, items.toArray(new String[0]));
                array = null;
            }
        }
    }

    /** The element's text, inline markup (b, i, xliff:g) dropped; leaves the parser on its end tag. */
    private static String innerText(XmlPullParser x) throws Exception {
        StringBuilder sb = new StringBuilder();
        int depth = 1;
        while (depth > 0) {
            int ev = x.next();
            if (ev == XmlPullParser.TEXT) sb.append(x.getText());
            else if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
            else if (ev == XmlPullParser.END_DOCUMENT) break;
        }
        return sb.toString();
    }

    /** Android's string rules: quoted runs keep their spaces, others collapse; \n, \t, \', \", \@, \?, \\ and \\u. */
    static String unescape(String raw) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false, space = false;
        int kept = 0;   // out's length up to its last character that is not a collapsed space (trailing ones go)
        for (int i = 0; i < raw.length(); i++, kept = space ? kept : out.length()) {
            char c = raw.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(++i);
                space = false;
                switch (n) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 < raw.length()) {
                            try {
                                out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                out.append('u');
                            }
                        }
                        break;
                    default: out.append(n); break;
                }
                continue;
            }
            if (!quoted && Character.isWhitespace(c)) {
                // Collapsed to one space, and none at the start.
                if (!space && out.length() > 0) out.append(' ');
                space = true;
                continue;
            }
            space = false;
            out.append(c);
        }
        if (!space) kept = out.length();
        return out.substring(0, kept);
    }

    private static String read(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** The file's text, or null when GitHub has no such file (404: most languages lack lunar, fonts and bools). */
    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        try {
            int code = c.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) return null;
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("GitHub answered " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > 1024 * 1024) throw new IOException("file too large");
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- runtime translation --------------------------------------------------------------------------------

    /** Resources that answer text from a pack, falling back to the APK's English. */
    @SuppressWarnings("deprecation")
    static final class Translated extends Resources {
        final Pack pack;
        private final PluralRules rules;

        Translated(Resources base, Pack pack, Locale locale) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
            this.pack = pack;
            this.rules = PluralRules.forLocale(locale);
        }

        /** The pack's text for a string resource id, or null. */
        String lookup(int id) {
            try {
                if (!"string".equals(getResourceTypeName(id))) return null;
                return pack.strings.get(getResourceEntryName(id));
            } catch (NotFoundException e) {
                return null;
            }
        }

        @Override
        public CharSequence getText(int id) throws NotFoundException {
            String s = lookup(id);
            return s != null ? s : super.getText(id);
        }

        @Override
        public CharSequence getText(int id, CharSequence def) {
            String s = lookup(id);
            return s != null ? s : super.getText(id, def);
        }

        @Override
        public String getString(int id) throws NotFoundException {
            String s = lookup(id);
            return s != null ? s : super.getString(id);
        }

        @Override
        public CharSequence getQuantityText(int id, int quantity) throws NotFoundException {
            try {
                Map<String, String> forms = pack.plurals.get(getResourceEntryName(id));
                if (forms != null) {
                    String s = forms.get(rules.select(quantity));
                    if (s == null) s = forms.get("other");
                    if (s != null) return s;
                }
            } catch (NotFoundException ignored) {
            }
            return super.getQuantityText(id, quantity);
        }

        @Override
        public String[] getStringArray(int id) throws NotFoundException {
            String[] a = array(id);
            return a != null ? a.clone() : super.getStringArray(id);
        }

        @Override
        public CharSequence[] getTextArray(int id) throws NotFoundException {
            String[] a = array(id);
            return a != null ? a.clone() : super.getTextArray(id);
        }

        private String[] array(int id) {
            try {
                return pack.arrays.get(getResourceEntryName(id));
            } catch (NotFoundException e) {
                return null;
            }
        }

        @Override
        public boolean getBoolean(int id) throws NotFoundException {
            try {
                Boolean b = pack.bools.get(getResourceEntryName(id));
                if (b != null) return b;
            } catch (NotFoundException ignored) {
            }
            return super.getBoolean(id);
        }
    }

    /** The pack text for a string id if res is translated, else null (UiFont uses it for a language's fonts). */
    static String override(Resources res, int id) {
        return res instanceof Translated ? ((Translated) res).lookup(id) : null;
    }

    /**
     * Translates text that layout files set: inflater gets a factory that creates each view as the inflater would and
     * then sets its @string/ attributes from the pack. Nothing happens when res is not a pack's.
     */
    static void hook(LayoutInflater inflater, Resources res) {
        if (!(res instanceof Translated) || inflater.getFactory() != null) return;
        Translated t = (Translated) res;
        try {
            inflater.setFactory2(new LayoutInflater.Factory2() {
                @Override
                public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
                    View v = create(name, context, attrs);
                    if (v != null) apply(v, attrs, t);
                    return v;
                }

                @Override
                public View onCreateView(String name, Context context, AttributeSet attrs) {
                    return onCreateView(null, name, context, attrs);
                }
            });
        } catch (IllegalStateException e) {
            Log.w(TAG, "inflater already has a factory: " + e);
        }
    }

    private static final String[] PREFIXES = {"android.widget.", "android.view.", "android.webkit."};
    private static final Map<String, Constructor<? extends View>> CONSTRUCTORS = new HashMap<>();

    /** The view, made with the inflating context as LayoutInflater makes it, or null to leave it to the inflater. */
    private static View create(String name, Context context, AttributeSet attrs) {
        try {
            Constructor<? extends View> c;
            synchronized (CONSTRUCTORS) {
                c = CONSTRUCTORS.get(name);
                if (c == null && !CONSTRUCTORS.containsKey(name)) {
                    c = constructor(name, context.getClassLoader());
                    CONSTRUCTORS.put(name, c);
                }
            }
            return c == null ? null : c.newInstance(context, attrs);
        } catch (Exception e) {
            Log.w(TAG, "could not create " + name + ": " + e);
            return null;
        }
    }

    private static Constructor<? extends View> constructor(String name, ClassLoader loader) {
        String[] candidates = name.indexOf('.') >= 0 ? new String[]{name}
                : new String[]{PREFIXES[0] + name, PREFIXES[1] + name, PREFIXES[2] + name};
        for (String cls : candidates) {
            try {
                Constructor<? extends View> c =
                        Class.forName(cls, false, loader).asSubclass(View.class).getConstructor(Context.class, AttributeSet.class);
                c.setAccessible(true);
                return c;
            } catch (ReflectiveOperationException | ClassCastException ignored) {
            }
        }
        return null;
    }

    private static void apply(View v, AttributeSet attrs, Translated t) {
        String desc = text(attrs, "contentDescription", t);
        if (desc != null) v.setContentDescription(desc);
        if (!(v instanceof TextView)) return;
        TextView tv = (TextView) v;
        String text = text(attrs, "text", t);
        if (text != null) tv.setText(text);
        String hint = text(attrs, "hint", t);
        if (hint != null) tv.setHint(hint);
        String font = text(attrs, "fontFamily", t);
        if (font != null && !font.startsWith("res/") && !font.startsWith("@")) {
            Typeface current = tv.getTypeface();
            tv.setTypeface(Typeface.create(font, current != null ? current.getStyle() : Typeface.NORMAL));
        }
        if (v instanceof TextClock) {
            String f12 = text(attrs, "format12Hour", t), f24 = text(attrs, "format24Hour", t);
            if (f12 != null) ((TextClock) v).setFormat12Hour(f12);
            if (f24 != null) ((TextClock) v).setFormat24Hour(f24);
        }
    }

    private static String text(AttributeSet attrs, String attr, Translated t) {
        int id = attrs.getAttributeResourceValue(ANDROID_NS, attr, 0);
        return id != 0 ? t.lookup(id) : null;
    }
}
