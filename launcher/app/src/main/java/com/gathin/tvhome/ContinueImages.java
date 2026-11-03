package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thumbnails for the Continue watching cards (ContinueRow). The apps publish them as https:// URLs, so this is
 * the one place this build goes online: only for those rows' images, only over https, at most MAX_BYTES each.
 * Downloads land in a disk cache (cacheDir/continue, trimmed to DISK_BYTES) so Home does not fetch them again
 * after a reboot; decoded bitmaps are downscaled to the card and kept in a small memory cache.
 * content:// and android.resource:// images are read locally, with no network.
 */
final class ContinueImages {

    private static final String TAG = ContinueRow.TAG;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final long DISK_BYTES = 40L * 1024 * 1024;
    private static final int TIMEOUT_MS = 10_000;

    private final Context context;
    private final Handler main;
    private final File dir;
    private final int targetW, targetH;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    ContinueImages(Context context, Handler main, int targetW, int targetH) {
        this.context = context.getApplicationContext();
        this.main = main;
        this.targetW = targetW;
        this.targetH = targetH;
        dir = new File(context.getCacheDir(), "continue");
        pool.execute(this::trimDisk);
    }

    /** Shows the image for url in view (null clears it); a view rebound to another url meanwhile is left alone. */
    void load(String url, ImageView view) {
        view.setTag(R.id.cw_image_url, url);
        if (url == null || url.isEmpty()) {
            view.setImageDrawable(null);
            return;
        }
        Bitmap cached = memory.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            view.setAlpha(1f);
            return;
        }
        view.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap b = fetch(url);
            if (b == null) return;
            memory.put(url, b);
            main.post(() -> {
                if (!url.equals(view.getTag(R.id.cw_image_url))) return;
                view.setImageBitmap(b);
                view.setAlpha(0f);
                view.animate().alpha(1f).setDuration(200).start();
            });
        });
    }

    void clearMemory() { memory.evictAll(); }

    /**
     * Drops every cached thumbnail, on disk and in memory, that is not one of these: a title finished or taken off the
     * app's list takes its picture with it. Called with the urls of a list that was read successfully, never after a
     * failed read, which would otherwise empty the cache.
     */
    void retainOnly(java.util.Collection<String> urls) {
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (String url : urls) if (url != null && !url.isEmpty()) keep.add(url);
        for (String url : memory.snapshot().keySet()) if (!keep.contains(url)) memory.remove(url);
        pool.execute(() -> {
            java.util.Set<String> files = new java.util.HashSet<>();
            for (String url : keep) files.add(key(url));
            File[] cached = dir.listFiles();
            if (cached == null) return;
            int removed = 0;
            for (File f : cached) {
                if (f.getName().endsWith(".tmp") || files.contains(f.getName())) continue;
                if (f.delete()) removed++;
            }
            if (removed > 0) Log.d(TAG, "removed " + removed + " thumbnails no longer in continue watching");
        });
    }

    private Bitmap fetch(String url) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if ("content".equals(scheme) || "android.resource".equals(scheme)) {
                byte[] bytes;
                try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                    if (in == null) return null;
                    bytes = readCapped(in);
                }
                return bytes == null ? null : decode(bytes);
            }
            if (!"https".equals(scheme)) return null;
            File file = new File(dir, key(url));
            if (!file.isFile()) download(url, file);
            else file.setLastModified(System.currentTimeMillis());   // recently shown: last to be trimmed
            if (!file.isFile()) return null;
            return decodeFile(file);
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "thumbnail not loaded: " + e);
            return null;
        }
    }

    private void download(String url, File file) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != HttpURLConnection.HTTP_OK) return;
            // A redirect may only stay on https (HttpURLConnection never follows https -> http anyway).
            if (!"https".equals(c.getURL().getProtocol())) return;
            byte[] bytes;
            try (InputStream in = c.getInputStream()) {
                bytes = readCapped(in);
            }
            if (bytes == null) return;
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(dir, file.getName() + ".tmp");
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(file)) tmp.delete();
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        for (int n; (n = in.read(buf)) > 0; ) {
            total += n;
            if (total > MAX_BYTES) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private Bitmap decode(byte[] bytes) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        o.inSampleSize = sample(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
    }

    private Bitmap decodeFile(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0) {
            file.delete();   // not an image: fetch it again next time
            return null;
        }
        o.inSampleSize = sample(o.outWidth, o.outHeight);
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    /** The largest power of two that keeps the image at least the card's size (centre-crop needs both sides). */
    private int sample(int w, int h) {
        int s = 1;
        while (w / (s * 2) >= targetW && h / (s * 2) >= targetH) s *= 2;
        return s;
    }

    private void trimDisk() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_BYTES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= DISK_BYTES * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    private static String key(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(url.hashCode());
        }
    }
}
