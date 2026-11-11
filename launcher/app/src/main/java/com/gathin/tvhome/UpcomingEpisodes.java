package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This fork: "Airs in N days" cards at the start of Continue watching (ContinueRow). Nuvio leaves an episode that has
 * not aired yet out of the rows it publishes (it cannot be played), so a show whose latest episode you have watched
 * drops off the row until the next one airs. The launcher remembers each show it saw in the row (its IMDb id, the
 * episode and the picture), and once a show has left the row it asks Cinemeta (public, no key) when the episode after
 * that one airs. One airing within MAX_DAYS gets a card that opens the show's page in the app; when the episode airs,
 * the app's own row takes over. Online build only.
 */
final class UpcomingEpisodes {
    private static final String TAG = "TvHomeCW";
    private static final String FILE = "upcoming_episodes.json";
    private static final String META = "https://v3-cinemeta.strem.io/meta/series/%s.json";
    /** An episode further off than this is not "continue watching" yet. */
    static final int MAX_DAYS = 30;
    /** A show not back in the row for this long is forgotten (a season break is shorter, mostly). */
    private static final long FORGET_MS = 120L * 24 * 60 * 60 * 1000;
    /** Cinemeta is asked again after this, or once the episode it named should have aired. */
    private static final long RECHECK_MS = 12L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 8000, MAX_BYTES = 4 * 1024 * 1024;
    private static final Pattern IMDB = Pattern.compile("tt\\d{5,}");

    private UpcomingEpisodes() {
    }

    /** One episode as Cinemeta lists it. */
    static final class Episode {
        final int season, episode;
        final String name;
        final long released;   // ms, 0 when not known

        Episode(int season, int episode, String name, long released) {
            this.season = season;
            this.episode = episode;
            this.name = name;
            this.released = released;
        }
    }

    /** The first episode after season s, episode e (specials, season 0, aside); null when there is none. */
    static Episode next(List<Episode> all, int s, int e) {
        Episode best = null;
        for (Episode it : all) {
            if (it.season <= 0) continue;
            boolean after = it.season > s || (it.season == s && it.episode > e);
            if (!after) continue;
            if (best == null || it.season < best.season || (it.season == best.season && it.episode < best.episode)) best = it;
        }
        return best;
    }

    /** Whole calendar days from now until the release, in zone; negative once that day has passed. */
    static long daysUntil(long releasedMs, long nowMs, ZoneId zone) {
        LocalDate from = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate();
        LocalDate to = Instant.ofEpochMilli(releasedMs).atZone(zone).toLocalDate();
        return ChronoUnit.DAYS.between(from, to);
    }

    /** Whether a card is due: the episode is still to come and airs within MAX_DAYS. */
    static boolean due(long releasedMs, long nowMs, ZoneId zone) {
        return releasedMs > nowMs && daysUntil(releasedMs, nowMs, zone) <= MAX_DAYS;
    }

    /** The card's pill: today, tomorrow or in N days. */
    static String label(Context ctx, long releasedMs) {
        long days = Math.max(0, daysUntil(releasedMs, System.currentTimeMillis(), ZoneId.systemDefault()));
        if (days == 0) return ctx.getString(R.string.cw_airs_today);
        if (days == 1) return ctx.getString(R.string.cw_airs_tomorrow);
        return ctx.getResources().getQuantityString(R.plurals.cw_airs_in, (int) days, (int) days);
    }

    /**
     * Remembers the shows in rows (the app's own, just read) and returns the cards for remembered shows no longer in
     * them whose next episode airs soon, soonest first. Runs on ContinueRow's loading thread; may reach Cinemeta.
     * One at a time: two loads close together share the file.
     */
    static synchronized List<ContinueRow.Item> update(Context ctx, List<ContinueRow.Item> rows) {
        if (BuildConfig.OFFLINE) return Collections.emptyList();
        long now = System.currentTimeMillis();
        File file = new File(ctx.getFilesDir(), FILE);
        JSONObject shows = readJson(file);
        boolean changed = false;
        Set<String> inRow = new HashSet<>();
        try {
            for (ContinueRow.Item it : rows) {
                Show show = Show.of(it);
                if (show == null) continue;
                inRow.add(show.id);
                JSONObject old = shows.optJSONObject(show.id);
                JSONObject entry = show.toJson(now);
                // Same episode as before: what Cinemeta said still holds.
                if (old != null && old.optInt("season") == show.season && old.optInt("episode") == show.episode
                        && old.has("next")) {
                    entry.put("checked", old.optLong("checked")).put("next", old.get("next"));
                }
                if (old == null || !sameShow(old, entry)) changed = true;
                shows.put(show.id, entry);
            }
            List<ContinueRow.Item> out = new ArrayList<>();
            ZoneId zone = ZoneId.systemDefault();
            List<String> ids = new ArrayList<>();
            for (Iterator<String> keys = shows.keys(); keys.hasNext(); ) ids.add(keys.next());
            for (String id : ids) {
                JSONObject entry = shows.getJSONObject(id);
                if (inRow.contains(id)) continue;   // the app shows it itself
                if (now - entry.optLong("seen") > FORGET_MS) {
                    shows.remove(id);
                    changed = true;
                    continue;
                }
                JSONObject next = entry.optJSONObject("next");
                long checked = entry.optLong("checked");
                boolean stale = next == null || now - checked > RECHECK_MS
                        || (next.optLong("released") > 0 && next.optLong("released") <= now);
                if (stale) {
                    Episode found = lookUp(id, entry.optInt("season"), entry.optInt("episode"));
                    if (found != LOOKUP_FAILED) {
                        next = new JSONObject();
                        if (found != null) {
                            next.put("season", found.season).put("episode", found.episode)
                                    .put("released", found.released);
                            if (!TextUtils.isEmpty(found.name)) next.put("name", found.name);
                        }
                        entry.put("checked", now).put("next", next);
                        changed = true;
                    }
                }
                if (next == null || !next.has("released")) continue;
                long released = next.optLong("released");
                if (!due(released, now, zone)) continue;
                out.add(card(ctx, id, entry, next));
            }
            Collections.sort(out, (a, b) -> Long.compare(a.airs, b.airs));
            if (changed) writeJson(file, shows);
            return out;
        } catch (JSONException e) {
            Log.w(TAG, "upcoming episodes: " + e);
            return Collections.emptyList();
        }
    }

    private static boolean sameShow(JSONObject a, JSONObject b) {
        return a.optInt("season") == b.optInt("season") && a.optInt("episode") == b.optInt("episode")
                && TextUtils.equals(a.optString("image"), b.optString("image"))
                && b.optLong("seen") - a.optLong("seen") < 60 * 60 * 1000;   // "seen" kept fresh within the hour
    }

    private static ContinueRow.Item card(Context ctx, String id, JSONObject entry, JSONObject next) {
        ContinueRow.Item it = new ContinueRow.Item();
        it.id = -1L - (id.hashCode() & 0x7fffffffL);   // apart from the provider's row ids, stable for the adapter
        it.pkg = entry.optString("pkg");
        it.title = entry.optString("title");
        it.season = String.valueOf(next.optInt("season"));
        it.episode = String.valueOf(next.optInt("episode"));
        it.episodeTitle = next.optString("name", null);
        it.image = entry.optString("image", null);
        it.type = TvContract.PreviewPrograms.TYPE_TV_EPISODE;
        it.airs = next.optLong("released");
        // The show's page in the app: its id and type, no episode to play.
        Intent open = new Intent(Intent.ACTION_VIEW)
                .setComponent(ComponentName.unflattenFromString(entry.optString("component")))
                .putExtra("contentId", entry.optString("contentId", id))
                .putExtra("contentType", entry.optString("contentType", "series"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        it.intentUri = open.getComponent() != null ? open.toUri(Intent.URI_INTENT_SCHEME) : null;
        return it;
    }

    /** A show as the app's row has it: what the card needs later, from the row and its link into the app. */
    private static final class Show {
        String id, pkg, title, image, component, contentId, contentType;
        int season, episode;

        static Show of(ContinueRow.Item it) {
            if (it.intentUri == null || TextUtils.isEmpty(it.season) || TextUtils.isEmpty(it.episode)) return null;
            Intent intent;
            try {
                intent = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
            } catch (URISyntaxException e) {
                return null;
            }
            Show s = new Show();
            try {
                s.season = Integer.parseInt(it.season.trim());
                s.episode = Integer.parseInt(it.episode.trim());
            } catch (NumberFormatException e) {
                return null;
            }
            String contentId = intent.getStringExtra("contentId");
            String type = intent.getStringExtra("contentType");
            if (type != null && type.toLowerCase(Locale.ROOT).equals("movie")) return null;
            // The IMDb id: Cinemeta knows shows by it. From the id the app gave, else anywhere in the link.
            Matcher m = IMDB.matcher(contentId != null && IMDB.matcher(contentId).find() ? contentId : it.intentUri);
            if (!m.find()) return null;
            ComponentName cn = intent.getComponent();
            if (cn == null || !it.pkg.equals(cn.getPackageName())) return null;
            s.id = m.group();
            s.pkg = it.pkg;
            s.title = it.title;
            s.image = it.image;
            s.component = cn.flattenToString();
            s.contentId = contentId != null ? contentId : s.id;
            s.contentType = type != null ? type : "series";
            return s;
        }

        JSONObject toJson(long now) throws JSONException {
            JSONObject o = new JSONObject()
                    .put("pkg", pkg).put("title", title).put("component", component)
                    .put("contentId", contentId).put("contentType", contentType)
                    .put("season", season).put("episode", episode).put("seen", now);
            if (image != null) o.put("image", image);
            return o;
        }
    }

    /** Returned when Cinemeta could not be asked: try again next time, keep what was known. */
    private static final Episode LOOKUP_FAILED = new Episode(-1, -1, null, 0);

    /** The episode after season s, episode e, by Cinemeta; null when it lists none, LOOKUP_FAILED on error. */
    private static Episode lookUp(String id, int s, int e) {
        try {
            JSONObject meta = new JSONObject(new String(get(String.format(Locale.ROOT, META, id)),
                    StandardCharsets.UTF_8)).optJSONObject("meta");
            JSONArray videos = meta != null ? meta.optJSONArray("videos") : null;
            List<Episode> all = new ArrayList<>();
            if (videos != null) {
                for (int i = 0; i < videos.length(); i++) {
                    JSONObject v = videos.optJSONObject(i);
                    if (v == null) continue;
                    int season = v.optInt("season", -1);
                    int episode = v.optInt("episode", v.optInt("number", -1));
                    if (season < 0 || episode < 0) continue;
                    all.add(new Episode(season, episode, v.optString("name", null), parseDate(v.optString("released"))));
                }
            }
            Episode next = next(all, s, e);
            Log.i(TAG, id + " after S" + s + "E" + e + ": "
                    + (next == null ? "nothing listed" : "S" + next.season + "E" + next.episode + " " + next.released));
            return next;
        } catch (IOException | JSONException | RuntimeException ex) {
            Log.w(TAG, "Cinemeta " + id + ": " + ex);
            return LOOKUP_FAILED;
        }
    }

    static long parseDate(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static byte[] get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[32 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (out.size() + n > MAX_BYTES) throw new IOException("too large");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject readJson(File file) {
        if (!file.isFile()) return new JSONObject();
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        } catch (IOException | JSONException e) {
            return new JSONObject();
        }
    }

    private static void writeJson(File file, JSONObject o) {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) tmp.delete();
    }
}
