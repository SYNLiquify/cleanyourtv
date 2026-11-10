package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * The weather line under the date (this fork, config.xml weather): the temperature in °F, the sky and today's high
 * and low, e.g. "72°F · PARTLY CLOUDY · H 78° L 61°".
 *
 * Where: config.xml latitude/longitude when set; otherwise the public IP's location from ipwho.is (with a VPN up,
 * that is the VPN server's city). What: Open-Meteo, no key. Both answers are cached in SharedPreferences, so a
 * restart or a reboot shows the last reading at once: the place for a day, the weather for 30 minutes. Fetching runs
 * on its own thread, only from refreshIfStale(), which Home calls when it resumes and on its minute tick; a failed
 * fetch keeps the last reading and tries again 5 minutes later.
 */
final class Weather {

    private static final String TAG = "TvHomeWeather";
    private static final String PREFS = "weather";
    private static final long PLACE_MAX_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final long WEATHER_MAX_AGE_MS = 30 * 60 * 1000L;
    private static final long RETRY_MS = 5 * 60 * 1000L;
    /** refreshNow: the shortest time between two fetches it starts. */
    private static final long REFRESH_GAP_MS = 60 * 1000L;
    /** A reading older than this is not shown at all: an old temperature reads as a wrong one. */
    private static final long SHOW_MAX_AGE_MS = 6 * 60 * 60 * 1000L;
    private static final int TIMEOUT_MS = 10_000;

    // The Settings menu's choices (SettingsActivity), in Config.SETTINGS_NAME.
    static final String SHOW_KEY = "weather_show";
    static final String UNITS_KEY = "weather_units";            // "f" or "c"
    static final String CONDITIONS_KEY = "weather_conditions";
    static final String HILO_KEY = "weather_hilo";
    static final String PLACE_KEY = "weather_place";            // "Denver, Colorado"; empty: the IP's location
    static final String LAT_KEY = "weather_lat", LON_KEY = "weather_lon";

    private final Context context;
    private final Handler main;
    /** The screen's own context for the line's words, so they follow the launcher's language (LauncherLocale); the
     *  application context (context) is not wrapped and would always give the TV's. */
    private final Context words;
    private final TextView view;
    private final SharedPreferences prefs;
    private final SharedPreferences settings;
    private final String fixedLat, fixedLon;
    private volatile boolean fetching;
    private long lastAttempt;

    /** This fork: Home's compact top bar: "☁ 62°F", an icon for the sky and the temperature only. */
    private boolean compact;

    void setCompact(boolean compact) {
        this.compact = compact;
        show();
    }

    Weather(Context context, Handler main, TextView view) {
        this.context = context.getApplicationContext();
        this.words = context;
        this.main = main;
        this.view = view;
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        settings = this.context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        fixedLat = context.getString(R.string.latitude).trim();
        fixedLon = context.getString(R.string.longitude).trim();
        show();
    }

    static boolean enabled(Context ctx) {
        // The offline build has no weather: it reaches nothing but GitHub (themes, languages, updates).
        if (BuildConfig.OFFLINE) return false;
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getBoolean(SHOW_KEY, ctx.getResources().getBoolean(R.bool.weather));
    }

    /** Forgets the cached reading and place, so the next refresh fetches both again (a new location, Refresh now). */
    static void invalidate(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("time").remove("temp")
                .remove("placeTime").apply();
    }

    /** Shows the cached reading, and fetches a new one when it is due (at most every 30 minutes). */
    void refreshIfStale() {
        show();
        if (!enabled(context)) return;
        long now = System.currentTimeMillis();
        boolean due = now - prefs.getLong("time", 0) > WEATHER_MAX_AGE_MS;
        if (!due || fetching || now - lastAttempt < RETRY_MS) return;
        start(now);
    }

    /**
     * This fork: fetches now, whatever the age of the reading, for the Home button and for the network coming back
     * after the TV wakes; a failed fetch (the TV still offline, a weather service briefly down) is then retried on
     * the next press instead of 5 minutes later. At most once a minute, so pressing Home again and again asks nothing.
     */
    void refreshNow() {
        show();
        if (!enabled(context)) return;
        long now = System.currentTimeMillis();
        if (fetching || now - lastAttempt < REFRESH_GAP_MS) return;
        start(now);
    }

    private void start(long now) {
        lastAttempt = now;
        fetching = true;
        new Thread(() -> {
            try {
                fetch();
            } catch (IOException | RuntimeException | org.json.JSONException e) {
                Log.d(TAG, "weather not updated: " + e);
            } finally {
                fetching = false;
                main.post(this::show);
            }
        }, "weather").start();
    }

    private void fetch() throws IOException, org.json.JSONException {
        double lat, lon;
        long now = System.currentTimeMillis();
        String chosenLat = settings.getString(LAT_KEY, ""), chosenLon = settings.getString(LON_KEY, "");
        if (!chosenLat.isEmpty() && !chosenLon.isEmpty()) {
            // A city picked in Settings.
            lat = Double.parseDouble(chosenLat);
            lon = Double.parseDouble(chosenLon);
        } else if (!fixedLat.isEmpty() && !fixedLon.isEmpty()) {
            lat = Double.parseDouble(fixedLat);
            lon = Double.parseDouble(fixedLon);
        } else if (prefs.contains("lat") && now - prefs.getLong("placeTime", 0) < PLACE_MAX_AGE_MS) {
            lat = Double.longBitsToDouble(prefs.getLong("lat", 0));
            lon = Double.longBitsToDouble(prefs.getLong("lon", 0));
        } else {
            JSONObject geo = new JSONObject(get("https://ipwho.is/?fields=success,latitude,longitude,city"));
            if (!geo.optBoolean("success", false)) throw new IOException("no location for this address");
            lat = geo.getDouble("latitude");
            lon = geo.getDouble("longitude");
            prefs.edit().putLong("lat", Double.doubleToRawLongBits(lat)).putLong("lon", Double.doubleToRawLongBits(lon))
                    .putString("city", geo.optString("city", "")).putLong("placeTime", now).apply();
        }
        String url = String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
                + "&temperature_unit=fahrenheit&timezone=auto&forecast_days=1", lat, lon);
        JSONObject w = new JSONObject(get(url));
        JSONObject current = w.getJSONObject("current");
        JSONObject daily = w.getJSONObject("daily");
        prefs.edit()
                .putInt("temp", (int) Math.round(current.getDouble("temperature_2m")))
                .putInt("code", current.getInt("weather_code"))
                .putInt("hi", (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))
                .putInt("lo", (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)))
                .putLong("time", System.currentTimeMillis())
                .apply();
    }

    private void show() {
        long age = System.currentTimeMillis() - prefs.getLong("time", 0);
        if (!enabled(context) || !prefs.contains("temp") || age > SHOW_MAX_AGE_MS) {
            view.setVisibility(android.view.View.GONE);
            return;
        }
        // Read in °F; shown in the unit chosen in Settings, with the parts it keeps.
        boolean celsius = "c".equals(settings.getString(UNITS_KEY, "f"));
        StringBuilder text = new StringBuilder(words.getString(celsius ? R.string.weather_temp_c : R.string.weather_temp_f,
                unit(prefs.getInt("temp", 0), celsius)));
        if (compact) {
            text.insert(0, icon(prefs.getInt("code", 0)) + "  ");
            if (!TextUtils.equals(view.getText(), text)) view.setText(text);
            view.setVisibility(android.view.View.VISIBLE);
            return;
        }
        String sep = words.getString(R.string.weather_separator);
        if (settings.getBoolean(CONDITIONS_KEY, true)) text.append(sep).append(sky(prefs.getInt("code", 0)));
        if (settings.getBoolean(HILO_KEY, true)) {
            text.append(sep).append(words.getString(R.string.weather_hilo,
                    unit(prefs.getInt("hi", 0), celsius), unit(prefs.getInt("lo", 0), celsius)));
        }
        if (!TextUtils.equals(view.getText(), text)) view.setText(text);
        view.setVisibility(android.view.View.VISIBLE);
    }

    private static int unit(int fahrenheit, boolean celsius) {
        return celsius ? (int) Math.round((fahrenheit - 32) * 5 / 9.0) : fahrenheit;
    }

    // ---- city lookup for Settings ---------------------------------------------------------------------

    static final class Place {
        final String name, region, country;
        final double lat, lon;

        Place(String name, String region, String country, double lat, double lon) {
            this.name = name;
            this.region = region;
            this.country = country;
            this.lat = lat;
            this.lon = lon;
        }

        /** "Denver, Colorado" (the region, or the country when there is none). */
        String label() {
            return name + (!region.isEmpty() ? ", " + region : !country.isEmpty() ? ", " + country : "");
        }

        String detail() {
            return region.isEmpty() ? country : region + ", " + country;
        }
    }

    /**
     * Places matching "City" or "City, State" (Open-Meteo's geocoding, no key). The part after the comma ranks the
     * results whose state or country it names first (US state abbreviations included). Blocking; off the main thread.
     */
    static java.util.List<Place> search(String query) throws IOException, org.json.JSONException {
        if (BuildConfig.OFFLINE) throw new IOException("no weather in the offline build");
        String[] parts = query.split(",", 2);
        String city = parts[0].trim();
        String within = parts.length > 1 ? parts[1].trim().toLowerCase(Locale.US) : "";
        String state = US_STATES.get(within.toUpperCase(Locale.US));
        if (state != null) within = state.toLowerCase(Locale.US);
        java.util.List<Place> found = new java.util.ArrayList<>();
        if (city.isEmpty()) return found;
        JSONObject r = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name="
                + java.net.URLEncoder.encode(city, "UTF-8")));
        org.json.JSONArray results = r.optJSONArray("results");
        if (results == null) return found;
        java.util.List<Place> rest = new java.util.ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject p = results.getJSONObject(i);
            Place place = new Place(p.optString("name"), p.optString("admin1", ""), p.optString("country", ""),
                    p.getDouble("latitude"), p.getDouble("longitude"));
            String region = place.region.toLowerCase(Locale.US), country = place.country.toLowerCase(Locale.US);
            boolean match = !within.isEmpty() && (region.startsWith(within) || country.startsWith(within)
                    || within.equals(p.optString("country_code", "").toLowerCase(Locale.US)));
            (match ? found : rest).add(place);
        }
        found.addAll(rest);
        return found;
    }

    private static final java.util.Map<String, String> US_STATES = new java.util.HashMap<>();
    static {
        String[] s = {"AL", "Alabama", "AK", "Alaska", "AZ", "Arizona", "AR", "Arkansas", "CA", "California",
                "CO", "Colorado", "CT", "Connecticut", "DE", "Delaware", "FL", "Florida", "GA", "Georgia", "HI", "Hawaii",
                "ID", "Idaho", "IL", "Illinois", "IN", "Indiana", "IA", "Iowa", "KS", "Kansas", "KY", "Kentucky",
                "LA", "Louisiana", "ME", "Maine", "MD", "Maryland", "MA", "Massachusetts", "MI", "Michigan",
                "MN", "Minnesota", "MS", "Mississippi", "MO", "Missouri", "MT", "Montana", "NE", "Nebraska",
                "NV", "Nevada", "NH", "New Hampshire", "NJ", "New Jersey", "NM", "New Mexico", "NY", "New York",
                "NC", "North Carolina", "ND", "North Dakota", "OH", "Ohio", "OK", "Oklahoma", "OR", "Oregon",
                "PA", "Pennsylvania", "RI", "Rhode Island", "SC", "South Carolina", "SD", "South Dakota",
                "TN", "Tennessee", "TX", "Texas", "UT", "Utah", "VT", "Vermont", "VA", "Virginia", "WA", "Washington",
                "WV", "West Virginia", "WI", "Wisconsin", "WY", "Wyoming", "DC", "District of Columbia"};
        for (int i = 0; i < s.length; i += 2) US_STATES.put(s[i], s[i + 1]);
    }

    /**
     * The sky as one symbol for the compact top bar: sun (a moon at night), sun and cloud, cloud, umbrella, snowflake,
     * lightning. U+FE0E asks for the plain text glyph, not a coloured emoji.
     */
    private static String icon(int code) {
        int hour = java.time.LocalTime.now().getHour();
        boolean night = hour < 6 || hour >= 19;
        String c;
        if (code == 0 || code == 1) c = night ? "\u263E" : "\u2600";
        else if (code == 2) c = night ? "\u263E" : "\u26C5";
        else if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) c = "\u2602";
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) c = "\u2744";
        else if (code >= 95) c = "\u26A1";
        else c = "\u2601";
        return c + "\uFE0E";
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    private String sky(int code) {
        int res;
        if (code == 0) res = R.string.weather_clear;
        else if (code == 1) res = R.string.weather_mainly_clear;
        else if (code == 2) res = R.string.weather_partly_cloudy;
        else if (code == 3) res = R.string.weather_overcast;
        else if (code == 45 || code == 48) res = R.string.weather_fog;
        else if (code >= 51 && code <= 57) res = R.string.weather_drizzle;
        else if (code >= 61 && code <= 67) res = R.string.weather_rain;
        else if (code >= 71 && code <= 77) res = R.string.weather_snow;
        else if (code >= 80 && code <= 82) res = R.string.weather_showers;
        else if (code == 85 || code == 86) res = R.string.weather_snow_showers;
        else if (code >= 95) res = R.string.weather_thunderstorm;
        else res = R.string.weather_cloudy;
        return words.getString(res);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Accept", "application/json");
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > 256 * 1024) throw new IOException("answer too large");
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
