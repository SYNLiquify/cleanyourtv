package com.gathin.tvhome;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in; a
 * ClockRefresh keeps its TextClocks current where Android would not.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle), and drawn small as the preview on
 * Settings > Clock > Screensaver. This fork: ScreensaverStyle lays it out (a fixed clock, the date and the weather on
 * spots of their own, sizes, the glow's colour and points).
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** The glow's colour change: 16 steps over 8 s. Animated per frame it redrew the full-screen gradient ~480 times. */
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final ClockRefresh clockRefresh;
    private final ImageView ambient;
    private final ScreensaverStyle style;
    /** The glow's points: points[0] is ambient, behind the clock; the others wander the screen. */
    private final ImageView[] points;
    private final GradientDrawable[] glows;
    private final int[] glowColors;
    /** The date or the weather on a spot of its own, and the spot. */
    private final List<View> ownViews = new ArrayList<>();
    private final List<String> ownSpots = new ArrayList<>();
    private int colorIndex;
    /** clock_wallpaper or clock_glow false: something else (a picture, or plain black) replaced the coloured glow. */
    private boolean wallpaperShown;
    /** clock_wallpaper: the picture start() decodes, its darkening, and the resources to draw it with. */
    private File wallpaperFile;
    private int wallpaperShade;
    private Resources wallpaperRes;
    /** One number per decode request: a result whose number is no longer the current one is dropped. Main thread only. */
    private int wallpaperGeneration;
    /** clock_wallpaper with a collection: the pictures, how long each lasts, and how the next one is picked. */
    private List<File> rotation;
    private long rotationMs;
    private long shownMs;
    private String order;
    private Map<String, Set<String>> moods;
    private String latitude, longitude;

    /** The weather under the date (config.xml weather): Home's cached reading, fetched again when it is due. */
    private final Weather weather;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ScreensaverStyle.FIXED.equals(style.clockMode)) moveClock();
            movePoints();
            shiftGlow();
            advanceWallpaper();
            if (weather != null) weather.refreshIfStale();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        Context ctx = root.getContext();
        style = ScreensaverStyle.load(ctx);
        content = root.findViewById(R.id.dreamContent);
        FrameLayout frame = (FrameLayout) content.getParent();
        // Sizes: S, M, L or XL against the layout's own (M).
        scale(root.findViewById(R.id.dreamClock), ScreensaverStyle.SIZES[style.clockSize]);
        scale(root.findViewById(R.id.dreamAmPm), ScreensaverStyle.SIZES[style.clockSize]);
        TextView date = root.findViewById(R.id.dreamDate);
        TextView weatherView = root.findViewById(R.id.dreamWeather);
        scale(date, ScreensaverStyle.SIZES[style.dateSize]);
        scale(weatherView, ScreensaverStyle.SIZES[style.weatherSize]);
        place(frame, date, style.dateMode, style.dateSpot);
        place(frame, weatherView, style.weatherMode, style.weatherSpot);
        // The whole screen, not only the clock: the date may have left it for a spot of its own.
        clockRefresh = new ClockRefresh(frame);
        ambient = root.findViewById(R.id.dreamAmbient);
        int count = ScreensaverStyle.GLOW_OFF.equals(style.glow) ? 1 : style.glowPoints;
        points = new ImageView[count];
        glows = new GradientDrawable[count];
        glowColors = new int[count];
        colorIndex = random.nextInt(PALETTE.length);
        for (int i = 0; i < count; i++) {
            ImageView point = i == 0 ? ambient : new ImageView(ctx);
            if (i > 0) {
                point.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                frame.addView(point, i, new FrameLayout.LayoutParams(-1, -1));   // over the glow, under the clock
            }
            GradientDrawable g = new GradientDrawable();
            g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            float reach = i == 0 ? 0.55f : 0.42f;
            point.setBackground(g);
            point.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> g.setGradientRadius((r - l) * reach));
            points[i] = point;
            glows[i] = g;
            setGlow(i, ScreensaverStyle.GLOW_CUSTOM.equals(style.glow) ? style.glowColor : paletteColor(i));
        }
        if (ScreensaverStyle.GLOW_OFF.equals(style.glow)) {
            wallpaperShown = true;   // plain black, and no colour cycle; clock_wallpaper may still put a picture here
            ambient.setBackground(null);
        }
        weather = ScreensaverStyle.OFF.equals(style.weatherMode) ? null
                : new Weather(ctx, handler, weatherView);   // shown or not as Settings > Weather say
    }

    private static void scale(TextView v, float factor) {
        if (v != null && factor != 1f) v.setTextSize(TypedValue.COMPLEX_UNIT_PX, v.getTextSize() * factor);
    }

    /** The date or the weather: left under the clock, moved to a spot of its own, or hidden. */
    private void place(FrameLayout frame, TextView v, String mode, String spot) {
        if (ScreensaverStyle.OFF.equals(mode)) {
            v.setVisibility(View.GONE);
            ((ViewGroup) v.getParent()).removeView(v);   // so nothing (the weather) shows it again
            return;
        }
        if (!ScreensaverStyle.OWN.equals(mode)) return;
        ((ViewGroup) v.getParent()).removeView(v);
        frame.addView(v, new FrameLayout.LayoutParams(-2, -2));
        v.setGravity(gravity(spot));   // a weather line that wraps lines up with its spot too
        ownViews.add(v);
        ownSpots.add(spot);
        // The weather appears, and its text changes, after the first layout: keep it on its spot.
        v.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) placeOwn(view, ownSpots.get(ownViews.indexOf(view)));
        });
    }

    /** A view on one of the nine spots, kept a little in from the edges. */
    private static void placeOwn(View v, String spot) {
        View parent = (View) v.getParent();
        if (parent == null || parent.getWidth() == 0) return;
        float marginX = parent.getWidth() * 0.06f, marginY = parent.getHeight() * 0.08f;
        v.setX(marginX + Math.max(0, parent.getWidth() - 2 * marginX - v.getWidth()) * ScreensaverStyle.across(spot));
        v.setY(marginY + Math.max(0, parent.getHeight() - 2 * marginY - v.getHeight()) * ScreensaverStyle.down(spot));
    }

    /** Left, centre or right, as the spot is across: how a group's lines line up there. */
    private static int gravity(String spot) {
        switch (spot.charAt(0)) {
            case 'l': return android.view.Gravity.LEFT;
            case 'r': return android.view.Gravity.RIGHT;
            default: return android.view.Gravity.CENTER_HORIZONTAL;
        }
    }

    private int paletteColor(int point) {
        return PALETTE[(colorIndex + point * 2) % PALETTE.length];
    }

    void start() {
        content.setAlpha(0f);
        for (View v : ownViews) v.setAlpha(0f);
        content.post(() -> {
            placeClock();
            placePoints();
            content.animate().alpha(1f).setDuration(1200).start();
            for (int i = 0; i < ownViews.size(); i++) {
                placeOwn(ownViews.get(i), ownSpots.get(i));
                ownViews.get(i).animate().alpha(1f).setDuration(1200).start();
            }
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
        clockRefresh.start();
        if (weather != null) weather.refreshIfStale();
        if (wallpaperFile != null && !wallpaperShown) loadWallpaper();
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);   // the tick and any glow steps still queued
        clockRefresh.stop();
        content.animate().cancel();
        for (View v : ownViews) v.animate().cancel();
        for (ImageView p : points) p.animate().cancel();
        wallpaperGeneration++;   // a decode still running must not attach its picture to a stopped scene
    }

    /**
     * clock_wallpaper: put the picture Home is showing behind the clock, darkened by clock_wallpaper_dim, in
     * place of the glow, and carry a collection's rotation on while the screensaver is up. Decoded off the main
     * thread at screen size in RGB_565 (4 MB at 1080p), like Home's own; until then, or if it cannot be read,
     * the glow stays.
     * Call before start(), which starts the decode (again after a stop() that dropped an unfinished one).
     */
    void useHomeWallpaper(Context ctx) {
        Config cfg;
        try {
            cfg = Config.load(ctx);
        } catch (RuntimeException e) {
            return;
        }
        if (!cfg.clockWallpaper) {
            // clock_glow false: plain black behind the clock (OLED); the clock itself still moves every minute.
            if (!cfg.clockGlow) {
                wallpaperShown = true;   // also stops the glow's colour cycle
                ambient.setBackground(null);
                for (int i = 1; i < points.length; i++) points[i].setVisibility(View.GONE);
            }
            return;
        }
        // What Home plays, and Config puts the single wallpaper in it when a theme has no collection.
        // Reading cfg.wallpaper first held the screensaver on wallpaper.jpg while Home rotated.
        if (cfg.wallpapers.isEmpty()) {
            wallpaperFile = null;
        } else {
            // Start on the picture Home is showing. WallpaperPlayer records it under the same
            // preferences and the screensaver runs in the same process, so this is just a read.
            rotation = new ArrayList<>(cfg.wallpapers);
            String shown = ctx.getSharedPreferences("wallpaper_rotation", Context.MODE_PRIVATE)
                    .getString("image", "");
            File current = WallpaperRotation.find(rotation, shown);
            wallpaperFile = current != null ? current : rotation.get(0);
            // Home only rotates while it is visible, so the screensaver carries the rotation on
            // itself; without this it holds one picture for as long as it is up.
            rotationMs = cfg.wallpaperRotationMinutes * 60_000L;
            order = cfg.wallpaperOrder;
            moods = cfg.wallpaperMoods;
            latitude = moods.isEmpty() ? "" : ctx.getString(R.string.latitude);
            longitude = moods.isEmpty() ? "" : ctx.getString(R.string.longitude);
        }
        wallpaperShade = (cfg.clockWallpaperDim * 255 / 100) << 24;
        wallpaperRes = ctx.getResources();
    }

    private void loadWallpaper() {
        File file = wallpaperFile;
        int shade = wallpaperShade;
        Resources res = wallpaperRes;
        DisplayMetrics dm = res.getDisplayMetrics();
        int w = dm.widthPixels, h = dm.heightPixels;
        int generation = ++wallpaperGeneration;
        new Thread(() -> {
            Bitmap bmp = decode(file, w, h);
            if (bmp == null) return;
            handler.post(() -> {
                if (generation != wallpaperGeneration || wallpaperShown) {
                    bmp.recycle();   // stopped meanwhile (or already shown): this picture was never attached
                    return;
                }
                wallpaperShown = true;
                // As the image, not the background: a background is stretched to the view, so a picture of
                // another shape than the screen would be distorted. centerCrop scales and crops it like Home.
                ambient.setBackground(null);
                for (int i = 1; i < points.length; i++) points[i].setVisibility(View.GONE);   // the picture instead
                ambient.setImageDrawable(new LayerDrawable(new Drawable[]{
                        new BitmapDrawable(res, bmp), new ColorDrawable(shade)}));
            });
        }, "clock-wallpaper").start();
    }

    /** WallpaperPlayer's decode: sampled and scaled down to cover the screen, never bigger than 4K. */
    private static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), options);
            int width = options.outWidth, height = options.outHeight;
            if (width <= 0 || height <= 0) return null;
            int sample = WallpaperSize.sampleSize(width, height, screenW, screenH);
            if (!WallpaperSize.accepted(width / sample, height / sample)) return null;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            int[] scale = WallpaperSize.coverScale(width / sample, height / sample, screenW, screenH);
            if (scale != null) {
                options.inScaled = true;
                options.inDensity = scale[0];
                options.inTargetDensity = scale[1];
            }
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
        } catch (Exception | OutOfMemoryError failure) {
            return null;
        }
    }

    /** Step to the next picture once its turn is up; the tick already runs every minute. */
    private void advanceWallpaper() {
        if (rotation == null || rotationMs <= 0 || rotation.size() < 2) return;
        shownMs += MOVE_MS;
        if (shownMs < rotationMs) return;
        shownMs = 0;
        // The same pick as Home's, so a shuffled collection stays shuffled and wallpaper_moods still
        // decides what may show at this hour.
        File next = WallpaperRotation.pick(playable(), wallpaperFile.getName(), order, random);
        if (next.equals(wallpaperFile)) return;   // a mood group of one
        wallpaperFile = next;
        wallpaperShown = false;
        loadWallpaper();
    }

    /** WallpaperPlayer's filter: the pictures wallpaper_moods allows at this hour, or all of them. */
    private List<File> playable() {
        if (moods.isEmpty()) return rotation;
        LocalDateTime now = LocalDateTime.now();
        int[] sun = WallpaperMood.sun(now.toLocalDate(), latitude, longitude, ZoneId.systemDefault());
        return WallpaperMood.select(rotation, moods,
                WallpaperMood.timeOfDay(now.getHour() * 60 + now.getMinute(), sun[0], sun[1]));
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /**
     * A random spot that keeps the whole clock on screen, or its fixed spot (ScreensaverStyle), with the glow centred
     * behind it.
     */
    private void placeClock() {
        View parent = (View) content.getParent();
        float x, y;
        if (ScreensaverStyle.FIXED.equals(style.clockMode)) {
            // Lines up with its spot: the time, date and weather right-aligned on the right, centred in the middle.
            ((android.widget.LinearLayout) content).setGravity(gravity(style.clockSpot));
            placeOwn(content, style.clockSpot);
            x = content.getX();
            y = content.getY();
        } else {
            int maxX = Math.max(0, parent.getWidth() - content.getWidth());
            int maxY = Math.max(0, parent.getHeight() - content.getHeight());
            x = maxX * (0.1f + 0.8f * random.nextFloat());
            y = maxY * (0.1f + 0.8f * random.nextFloat());
            content.setX(x);
            content.setY(y);
        }
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glows[0].setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    /** The other glow points: anywhere on screen, a different place each minute. */
    private void placePoints() {
        for (int i = 1; i < points.length; i++) {
            glows[i].setGradientCenter(0.1f + 0.8f * random.nextFloat(), 0.1f + 0.8f * random.nextFloat());
        }
    }

    /** The points fade out, move and fade back in: cheap (a layer's alpha) where moving the gradient is not. */
    private void movePoints() {
        if (points.length < 2 || wallpaperShown) return;
        for (int i = 1; i < points.length; i++) {
            ImageView point = points[i];
            GradientDrawable g = glows[i];
            point.animate().alpha(0f).setDuration(1500).withLayer().withEndAction(() -> {
                g.setGradientCenter(0.1f + 0.8f * random.nextFloat(), 0.1f + 0.8f * random.nextFloat());
                point.animate().alpha(1f).setDuration(2500).withLayer().start();
            }).start();
        }
    }

    /** The colour cycle: every point on to its next palette colour. A custom colour stays. */
    private void shiftGlow() {
        if (wallpaperShown || !ScreensaverStyle.GLOW_CYCLE.equals(style.glow)) return;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int p = 0; p < points.length; p++) {
            int point = p, from = glowColors[p], to = paletteColor(p);
            for (int i = 1; i <= GLOW_STEPS; i++) {
                float f = (float) i / GLOW_STEPS;
                handler.postDelayed(() -> setGlow(point, (int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
            }
        }
    }

    private void setGlow(int point, int color) {
        glowColors[point] = color;
        int c = color & 0x00FFFFFF;
        glows[point].setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}
