package com.gathin.tvhome;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * locale: the language the launcher shows itself in, instead of the TV's. A China-market Xiaomi offers Chinese and
 * English only, so a Vietnamese owner saw the launcher in Chinese although it is translated into Vietnamese.
 * Each activity wraps its base context here; an empty or malformed tag leaves the TV's language.
 */
final class LauncherLocale {
    // Subtags after the language, so "sr-Latn" and "ar-EG-u-nu-latn" (Arabic with Western digits) pass too.
    private static final Pattern TAG = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{1,8})*");

    private LauncherLocale() {
    }

    /** The Locale for a config tag such as "vi", "pt-BR" or "ar-EG-u-nu-latn", or null for "" or anything that is not a plain tag. */
    static Locale parse(String tag) {
        if (tag == null || !TAG.matcher(tag.trim()).matches()) return null;
        return Locale.forLanguageTag(tag.trim());
    }

    /**
     * This fork: puts the chosen language back as the process default. Android resets the default to the TV's
     * language when the process starts or the configuration changes, and the clock's date (TextClock) and other
     * Context-free formatters read the default, so Home calls this each time it comes to the front.
     */
    static void applyDefault(Locale locale) {
        if (locale != null && !locale.equals(Locale.getDefault())) Locale.setDefault(locale);
    }

    /**
     * The language the launcher shows: the chosen one, else the TV's. This fork ships English only and downloads the
     * rest (LanguagePacks), so a language without an installed pack shows as English, with the TV's region kept,
     * and the dates match the text.
     */
    static Locale effective(Context base) {
        return effective(base, null);
    }

    /** As effective(base), with a Config already loaded (null: load it only if it is needed). */
    static Locale effective(Context base, Config loaded) {
        Locale locale = wanted(base, loaded);
        if (locale == null) return Locale.ENGLISH;
        if (BuildConfig.OFFLINE || "en".equals(locale.getLanguage())
                || LanguagePacks.installedTagFor(base, locale) != null) return locale;
        return new Locale("en", locale.getCountry());
    }

    /** The chosen language, else the TV's (the system's, as base may already be in the launcher's), installed or not. */
    static Locale wanted(Context base) {
        return wanted(base, null);
    }

    /**
     * As wanted(base). A language chosen in Settings wins over config.xml and the theme (Config.load), so it is read
     * from there straight away: every activity's attachBaseContext asks, and Config.load reads and parses the theme's
     * config.json and about a hundred resources each time. loaded: a Config already loaded, or null.
     */
    static Locale wanted(Context base, Config loaded) {
        String setting;
        try {
            android.content.SharedPreferences choices = base.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
            setting = choices.contains(Config.LOCALE_KEY) ? choices.getString(Config.LOCALE_KEY, "").trim() : null;
        } catch (IllegalStateException lockedAtBoot) {
            setting = null;   // credential storage before the first unlock (a direct-boot helper)
        }
        if (setting == null) setting = (loaded != null ? loaded : Config.load(base)).locale;
        Locale chosen = parse(setting);
        return chosen != null ? chosen : android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0);
    }

    /** base in the launcher's language: an installed pack's text on top, or English. */
    static Context wrap(Context base) {
        Locale locale = effective(base);
        // Formatters that take no Context (the clock's date, the lunar line) follow the default locale.
        if (!locale.equals(Locale.getDefault())) Locale.setDefault(locale);
        String tag = LanguagePacks.installedTagFor(base, locale);
        LanguagePacks.Pack pack = tag == null ? null : LanguagePacks.load(base, tag);
        if (pack != null) return LanguagePacks.wrap(base, locale, pack);
        Configuration c = new Configuration(base.getResources().getConfiguration());
        if (locale.equals(c.getLocales().get(0))) return base;
        c.setLocale(locale);
        return base.createConfigurationContext(c);
    }
}
