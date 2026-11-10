package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

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
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Launcher updates from the fork's GitHub releases (this fork). work\publish-update.ps1 builds the launcher on the PC
 * that installed it (the same signing key, or Android refuses the update), and publishes it as a release tagged
 * "launcher-<versionCode>" with the APK and its SHA-256 in the notes.
 *
 * The launcher asks GitHub for the latest release when the TV starts (BOOT_COMPLETED), when Home comes back after the
 * interval has passed (so also after standby), and on an inexact alarm every interval (Settings > System > Updates:
 * off, or 1-24 hours; 1 hour by default). A newer one is offered on Home and under Settings > System > Updates.
 * Installing downloads the APK, checks its size and SHA-256, and hands it to Android's package installer, which asks
 * for one press of Install: a sideloaded app cannot update itself silently. Nothing is fetched with checks off.
 */
public final class Updates extends BroadcastReceiver {

    static final String TAG = "TvHomeUpdate";
    static final String REPO = "SYNLiquify/cleanyourtv";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String ASSET = "cleanyourtv-launcher.apk";
    /** The offline build's APK in the same release, with its own checksum line ("sha256-offline: <hex>"). */
    private static final String OFFLINE_ASSET = "cleanyourtv-launcher-offline.apk";
    private static final Pattern TAG_CODE = Pattern.compile("launcher-(\\d+)");
    private static final Pattern SHA = Pattern.compile("sha256:\\s*([0-9a-fA-F]{64})");
    private static final Pattern SHA_OFFLINE = Pattern.compile("sha256-offline:\\s*([0-9a-fA-F]{64})");
    /** Settings > System > Updates > Channel, in Config.SETTINGS_NAME: which build updates install. */
    static final String CHANNEL_KEY = "update_channel";
    static final String ONLINE = "online", OFFLINE = "offline";
    /** The channel the stored offer was found for. */
    private static final String OFFER_CHANNEL = "offer_channel";
    private static final int TIMEOUT_MS = 15000;
    private static final long MAX_APK = 32L * 1024 * 1024;

    static final String INTERVAL_KEY = "update_interval_hours";
    static final int DEFAULT_HOURS = 1;
    static final int[] INTERVALS = {0, 1, 3, 6, 12, 24};
    private static final String PREFS = "updates";
    private static final String LAST_CHECK = "last_check";
    private static final String CODE = "code", NAME = "name", URL_KEY = "url", SIZE = "size", SHA_KEY = "sha",
            DISMISSED = "dismissed", ERROR = "error", JUST_UPDATED = "just_updated", START_PENDING = "start_pending", NOTES = "notes", BOOT_SEEN = "boot_seen";
    /** The least time between two checks that a Home press starts, within GitHub's 60 checks an hour. */
    static final long HOME_PRESS_GAP_MS = 60_000;

    private static final String ACTION_CHECK = "com.gathin.tvhome.UPDATE_CHECK";
    private static final String ACTION_INSTALLED = "com.gathin.tvhome.UPDATE_INSTALL_STATUS";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** What GitHub offers, if it is newer than this build. */
    static final class Offer {
        final int code;
        final String name, url, sha;
        final long size;
        /** What's new, one change a line (ReleaseNotes); empty when the release lists none. */
        final List<String> notes;

        Offer(int code, String name, String url, long size, String sha, String notes) {
            this.notes = notes == null || notes.isEmpty() ? Collections.emptyList() : Arrays.asList(notes.split("\n"));
            this.code = code;
            this.name = name;
            this.url = url;
            this.size = size;
            this.sha = sha;
        }
    }

    // ---- settings -------------------------------------------------------------------------------------------

    static int intervalHours(Context ctx) {
        return ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getInt(INTERVAL_KEY, DEFAULT_HOURS);
    }

    static void setIntervalHours(Context ctx, int hours) {
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putInt(INTERVAL_KEY, hours).apply();
        schedule(ctx);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** This build: "offline" leaves out the features that reach anything but GitHub (weather). */
    static String build() {
        return BuildConfig.OFFLINE ? OFFLINE : ONLINE;
    }

    /** The build updates install; this one until the owner picks the other in Settings. */
    static String channel(Context ctx) {
        String c = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(CHANNEL_KEY, build());
        return OFFLINE.equals(c) ? OFFLINE : ONLINE;
    }

    /** A new channel forgets the offer found for the old one; the caller checks again. */
    static void setChannel(Context ctx, String channel) {
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit().putString(CHANNEL_KEY, channel).apply();
        prefs(ctx).edit().remove(CODE).remove(OFFER_CHANNEL).remove(DISMISSED).apply();
    }

    /** Whether release code on channel is worth installing: newer, or this version as the other build. */
    private static boolean wanted(Context ctx, int code, String channel) {
        int installed = installedCode(ctx);
        return code > installed || (!channel.equals(build()) && code >= installed);
    }

    /** The running launcher's version code: this APK's own, so no PackageManager call. */
    static int installedCode(Context ctx) {
        return BuildConfig.VERSION_CODE;
    }

    static String installedName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    /** The newer release last seen, or null (none, or this build is already as new). */
    static Offer offer(Context ctx) {
        SharedPreferences p = prefs(ctx);
        int code = p.getInt(CODE, 0);
        String channel = channel(ctx);
        if (!channel.equals(p.getString(OFFER_CHANNEL, ONLINE)) || !wanted(ctx, code, channel)) return null;
        return new Offer(code, p.getString(NAME, ""), p.getString(URL_KEY, ""), p.getLong(SIZE, 0), p.getString(SHA_KEY, ""),
                p.getString(NOTES, ""));
    }

    static long lastCheck(Context ctx) {
        return prefs(ctx).getLong(LAST_CHECK, 0);
    }

    /** The last check's failure, or null when it worked. */
    static String lastError(Context ctx) {
        return prefs(ctx).getString(ERROR, null);
    }

    /** Home offers each version once; Later hides it there (Settings still shows it). */
    static boolean dismissed(Context ctx, Offer o) {
        return o != null && prefs(ctx).getInt(DISMISSED, 0) >= o.code;
    }

    static void dismiss(Context ctx, Offer o) {
        if (o != null) prefs(ctx).edit().putInt(DISMISSED, o.code).apply();
    }

    /** The version an update just installed, until Home has shown it once; null otherwise. */
    static String justUpdated(Context ctx) {
        return prefs(ctx).getString(JUST_UPDATED, null);
    }

    static void clearJustUpdated(Context ctx) {
        prefs(ctx).edit().remove(JUST_UPDATED).apply();
    }

    /** PackageInfo.lastUpdateTime of the update already announced (noticeIfUpdated). */
    private static final String NOTICED_UPDATE = "noticed_update";
    /** An update older than this when the launcher first starts after it is not announced any more. */
    private static final long NOTICE_WINDOW_MS = 10 * 60 * 1000L;

    /**
     * Whether Android installed a new launcher since the last start, announced once: Home's card (justUpdated), and
     * with toast, "Launcher updated to 0.6.0. Press Home to go back." over whatever app the installer left in front.
     * Called from every way the launcher starts after an update (the installer's answer, a key helper bound again,
     * Home), since MY_PACKAGE_REPLACED alone never comes on some firmware (a Hisense on Android 11).
     */
    static synchronized void noticeIfUpdated(Context context, boolean toast) {
        Context ctx = context.getApplicationContext();
        PackageInfo info;
        try {
            info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }
        SharedPreferences p = prefs(ctx);
        long updated = info.lastUpdateTime;
        if (p.getLong(NOTICED_UPDATE, 0) == updated) return;
        // Not on a first install, and not for an update long ago (the first start of a launcher that checks this
        // after an older one was updated in the past). The first update into this version counts.
        boolean fresh = updated > info.firstInstallTime && System.currentTimeMillis() - updated < NOTICE_WINDOW_MS;
        p.edit().putLong(NOTICED_UPDATE, updated).apply();
        if (!fresh) return;
        String version = installedName(ctx);
        p.edit().putString(JUST_UPDATED, version).apply();
        // The downloaded APK is installed now: drop it, or it sits in the cache (Storage's Update download).
        if (new File(ctx.getCacheDir(), "update.apk").delete()) Log.i(TAG, "removed the installed update's APK");
        Log.i(TAG, "updated to " + version);
        if (!toast) return;
        Context words = LauncherLocale.wrap(ctx);
        String text = words.getString(R.string.update_done_hint, version);
        // Twice in a row, about 7 seconds in all: one long toast (3.5 s) is easy to miss in the middle of a video.
        MAIN.post(() -> android.widget.Toast.makeText(words, text, android.widget.Toast.LENGTH_LONG).show());
        MAIN.postDelayed(() -> android.widget.Toast.makeText(words, text, android.widget.Toast.LENGTH_LONG).show(), 3600);
    }

    // ---- checking -------------------------------------------------------------------------------------------

    /** Sets (or with checks off, cancels) the repeating alarm that checks in the background. */
    static void schedule(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, Updates.class).setAction(ACTION_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        int hours = intervalHours(ctx);
        if (hours <= 0) return;
        long every = hours * AlarmManager.INTERVAL_HOUR;
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + every, every, pi);
    }

    /** Checks when the interval has passed since the last check (Home coming back, the TV waking up). */
    static void checkIfDue(Context ctx, Consumer<Offer> done) {
        int hours = intervalHours(ctx);
        if (hours <= 0) return;
        long since = System.currentTimeMillis() - lastCheck(ctx);
        if (since >= 0 && since < hours * AlarmManager.INTERVAL_HOUR) return;
        check(ctx, done);
    }

    /**
     * Removes an update download left in the cache: older versions kept it after installing, and a download
     * interrupted mid-way leaves one too. Home calls it as it starts; no install is running in a fresh process.
     */
    static void removeLeftoverDownload(Context ctx) {
        if (downloading) return;   // Home restarted while an install in this process is still writing it
        File apk = new File(ctx.getCacheDir(), "update.apk");
        if (apk.exists() && apk.delete()) Log.i(TAG, "removed a leftover update download");
    }

    /** A Home press (this fork): checks unless checks are Off or the last one was under a minute ago. */
    static void checkOnHomePress(Context ctx, Consumer<Offer> done) {
        if (intervalHours(ctx) <= 0) return;
        long since = System.currentTimeMillis() - lastCheck(ctx);
        if (since >= 0 && since < HOME_PRESS_GAP_MS) return;
        Log.i(TAG, "checking: Home pressed");
        check(ctx, done);
    }

    /**
     * The TV started or woke up: check, and if that fails (Wi-Fi often connects a little later) keep the check
     * pending, so Home runs it again as soon as the network is up ({@link #checkIfStartPending}).
     */
    static void checkOnStart(Context ctx, Consumer<Offer> done) {
        if (intervalHours(ctx) <= 0) return;
        prefs(ctx).edit().putBoolean(START_PENDING, true).apply();
        Log.i(TAG, "checking: TV started or woke up");
        check(ctx, done);
    }

    /**
     * Once per TV start: the start check. The boot broadcast is not enough, because the Hisense holds BOOT_COMPLETED
     * back from apps it does not list, so Home calls this as it comes up too; whichever comes first runs it.
     */
    static void checkOnceAfterBoot(Context ctx, Consumer<Offer> done) {
        long bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        long seen = prefs(ctx).getLong(BOOT_SEEN, 0);
        if (Math.abs(bootedAt - seen) < 60_000) return;   // this start's check already ran
        prefs(ctx).edit().putLong(BOOT_SEEN, bootedAt).apply();
        checkOnStart(ctx, done);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean RETRYING = new java.util.concurrent.atomic.AtomicBoolean();

    /** Runs a start check that has not got through yet (one at a time); Home calls it when the network comes up. */
    static void checkIfStartPending(Context ctx, Consumer<Offer> done) {
        if (!prefs(ctx).getBoolean(START_PENDING, false) || intervalHours(ctx) <= 0) return;
        if (!RETRYING.compareAndSet(false, true)) return;
        Log.i(TAG, "checking: start check again, now online");
        check(ctx, o -> {
            RETRYING.set(false);
            if (done != null) done.accept(o);
        });
    }

    /** Asks GitHub for the latest release; done gets the newer offer, or null, on the main thread. */
    static void check(Context context, Consumer<Offer> done) {
        Context ctx = context.getApplicationContext();
        WORKER.execute(() -> {
            Offer found = null;
            SharedPreferences.Editor e = prefs(ctx).edit().putLong(LAST_CHECK, System.currentTimeMillis());
            try {
                JSONObject r = new JSONObject(latest(ctx));
                Matcher m = TAG_CODE.matcher(r.optString("tag_name"));
                if (!m.matches()) throw new IOException("no launcher release");
                int code = Integer.parseInt(m.group(1));
                String channel = channel(ctx);
                boolean offline = OFFLINE.equals(channel);
                String asset = offline ? OFFLINE_ASSET : ASSET;
                String url = null;
                long size = 0;
                JSONArray assets = r.optJSONArray("assets");
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (asset.equals(a.optString("name"))) {
                        url = a.optString("browser_download_url");
                        size = a.optLong("size");
                    }
                }
                Matcher s = (offline ? SHA_OFFLINE : SHA).matcher(r.optString("body"));
                if (url == null || !url.startsWith("https://") || !s.find()) throw new IOException("release has no APK or checksum");
                String notes = TextUtils.join("\n", ReleaseNotes.parse(r.optString("body")));
                e.putInt(CODE, code).putString(NOTES, notes).putString(NAME, r.optString("name", "")).putString(URL_KEY, url).putLong(SIZE, size)
                        .putString(SHA_KEY, s.group(1).toLowerCase(Locale.US)).remove(ERROR).remove(START_PENDING).putString(OFFER_CHANNEL, channel);
                Log.i(TAG, "check done: latest " + code + " (" + channel + "), installed " + installedCode(ctx) + " (" + build() + ")");
                if (wanted(ctx, code, channel)) {
                    found = new Offer(code, r.optString("name", ""), url, size, s.group(1).toLowerCase(Locale.US), notes);
                }
            } catch (Exception ex) {
                Log.w(TAG, "check failed: " + ex);
                e.putString(ERROR, ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
            e.apply();
            Offer result = found;
            if (done != null) MAIN.post(() -> done.accept(result));
        });
    }

    // ---- installing ---------------------------------------------------------------------------------------

    interface Progress {
        /** percent 0-100 while downloading; -1 when the installer has the APK; a message on failure. */
        void update(int percent, String failure);
    }

    /**
     * Downloads the offer, checks it, and opens Android's installer for it. The launcher's process ends when the
     * update goes in; Home comes back on the new version.
     */
    static void install(Context context, Offer o, Progress progress) {
        Context ctx = context.getApplicationContext();
        WORKER.execute(() -> {
            File apk = new File(ctx.getCacheDir(), "update.apk");
            downloading = true;
            try {
                download(o, apk, p -> MAIN.post(() -> progress.update(p, null)));
                if (o.size > 0 && apk.length() != o.size) throw new IOException("download incomplete");
                if (!o.sha.equalsIgnoreCase(sha256(apk))) throw new IOException("checksum does not match");
                PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(ctx.getPackageName());
                params.setSize(apk.length());
                int id = pi.createSession(params);
                try (PackageInstaller.Session session = pi.openSession(id)) {
                    try (InputStream in = new FileInputStream(apk);
                         OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                        byte[] buf = new byte[64 * 1024];
                        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                        session.fsync(out);
                    }
                    // Android has its own copy in the session now, so ours is not needed: drop it straight away
                    // rather than wait for MY_PACKAGE_REPLACED, which the Hisense never delivers to the launcher.
                    if (apk.delete()) Log.i(TAG, "handed the APK to Android and removed the download");
                    Intent status = new Intent(ctx, Updates.class).setAction(ACTION_INSTALLED);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    session.commit(PendingIntent.getBroadcast(ctx, id, status, flags).getIntentSender());
                    Log.i(TAG, "session " + id + " committed for " + o.name);
                }
                // Some firmware (a Hisense on Android 11) commits the session but never sends the "needs the user"
                // answer below, so the confirm screen never opened. If it has not come in a few seconds, open
                // Android's confirm screen for this session ourselves: the same intent Android would have sent.
                MAIN.postDelayed(() -> {
                    if (confirmOpenedFor != id) openConfirm(ctx, confirmIntent(ctx, id), id);
                }, CONFIRM_FALLBACK_MS);
                MAIN.post(() -> progress.update(-1, null));
            } catch (Exception ex) {
                Log.w(TAG, "install failed: " + ex);
                apk.delete();
                String why = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                MAIN.post(() -> progress.update(0, why));
            } finally {
                downloading = false;
            }
        });
    }

    /** An install is downloading or handing over update.apk (removeLeftoverDownload leaves it alone meanwhile). */
    private static volatile boolean downloading;

    /** Long enough for Android to check the APK it was handed; the screen closes itself if opened earlier. */
    private static final long CONFIRM_FALLBACK_MS = 1000;
    /** The session whose confirm screen was opened, so the answer and the fallback don't open it twice. */
    private static volatile int confirmOpenedFor = -1;

    /** Android's "install this update?" screen for session id, as PackageInstaller builds it. */
    private static Intent confirmIntent(Context ctx, int id) {
        Intent confirm = new Intent("android.content.pm.action.CONFIRM_INSTALL")   // PackageInstaller.ACTION_CONFIRM_INSTALL, hidden from apps
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, id);
        android.content.pm.ResolveInfo r = ctx.getPackageManager().resolveActivity(confirm, 0);
        if (r != null && r.activityInfo != null) confirm.setPackage(r.activityInfo.packageName);
        return confirm;
    }

    private static void openConfirm(Context ctx, Intent confirm, int id) {
        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(confirm);
            confirmOpenedFor = id;
            Log.i(TAG, "opened the installer for session " + id);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open the installer: " + e);
        }
    }

    private static void download(Offer o, File to, Consumer<Integer> percent) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(o.url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + c.getResponseCode());
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : o.size;
            if (total > MAX_APK) throw new IOException("APK too large");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[64 * 1024];
                long got = 0;
                int last = -1;
                for (int n; (n = in.read(buf)) > 0; ) {
                    got += n;
                    if (got > MAX_APK) throw new IOException("APK too large");
                    out.write(buf, 0, n);
                    int p = total > 0 ? (int) (got * 100 / total) : 0;
                    if (p != last) {
                        last = p;
                        percent.accept(p);
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static final String LATEST_ETAG = "latest_etag", LATEST_BODY = "latest_body";

    /**
     * The latest release's JSON. Asked with the last answer's ETag: when nothing changed GitHub answers 304, which does
     * not count against its 60 requests an hour for the TV's address (shared with Themes and other TVs), and the
     * kept answer is used.
     */
    private static String latest(Context ctx) throws IOException {
        SharedPreferences p = prefs(ctx);
        String etag = p.getString(LATEST_ETAG, null), kept = p.getString(LATEST_BODY, null);
        HttpURLConnection c = (HttpURLConnection) new URL(LATEST).openConnection();
        if (etag != null && kept != null) c.setRequestProperty("If-None-Match", etag);
        String body = read(c);
        if (body == null) {
            Log.i(TAG, "check: release unchanged (304)");
            return kept;
        }
        String tag = c.getHeaderField("ETag");
        if (tag != null) p.edit().putString(LATEST_ETAG, tag).putString(LATEST_BODY, body).apply();
        return body;
    }

    /** The answer's text, or null for 304 Not Modified. */
    private static String read(HttpURLConnection c) throws IOException {
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED) return null;
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) throw new IOException("GitHub answered " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int total = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    total += n;
                    if (total > 512 * 1024) throw new IOException("answer too large");
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    // ---- broadcasts ---------------------------------------------------------------------------------------

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, "received " + action + " status=" + intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -99));
        // Any of these may be the first thing to run after an update (the installer's answer, boot, the replace).
        noticeIfUpdated(ctx, true);
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // The TV started (or this launcher was just updated): set the alarm again and check now.
            schedule(ctx);
            // MY_PACKAGE_REPLACED: the notice above has said the update is in and Home is back.
            if (intervalHours(ctx) > 0 && Intent.ACTION_BOOT_COMPLETED.equals(action)) {
                // Kept pending if the network isn't up yet; Home checks again once it is.
                PendingResult pending = goAsync();
                checkOnceAfterBoot(ctx, o -> pending.finish());
            }
        } else if (ACTION_CHECK.equals(action)) {
            PendingResult pending = goAsync();
            check(ctx, o -> pending.finish());
        } else if (ACTION_INSTALLED.equals(action)) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Android's own "install this update?" screen; one press of Install.
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                int id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
                if (confirm != null && confirmOpenedFor != id) openConfirm(ctx, confirm, id);
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                Log.w(TAG, "install ended with " + status + ": " + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }
}
