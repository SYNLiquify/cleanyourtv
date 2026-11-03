package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Continue watching under the dock (config.xml continue_watching_apps): the preview programs those apps publish
 * to the TV provider (Nuvio's "Continue watching" channel), as a sideways-scrolling row of thumbnail cards: the
 * show or film name top left, season and episode with the episode title bottom left, the time left bottom right
 * above a progress line along the card's foot. Reading needs READ_TV_LISTINGS
 * (`pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS`); without it, or with no rows, the row stays
 * hidden and takes no room. Read off the main thread when Home resumes and when the provider reports a change
 * while Home is on screen. Thumbnails come through ContinueImages.
 */
final class ContinueRow {

    static final String TAG = "TvHomeCW";
    /** Not in the public SDK's Manifest.permission; defined by the TV provider's framework manifest. */
    private static final String READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";
    /** Nuvio writes this duration when it knows only a percentage (AndroidTvChannelManager), so no time is left to show. */
    private static final long SYNTHETIC_DURATION_MS = 100_000L;
    /** More than this is history, not something to pick up again. */
    private static final int MAX_ITEMS = 20;
    private static final String[] PROJECTION = {
            TvContract.PreviewPrograms._ID,
            TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME,
            TvContract.PreviewPrograms.COLUMN_TITLE,
            TvContract.PreviewPrograms.COLUMN_EPISODE_TITLE,
            TvContract.PreviewPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.PreviewPrograms.COLUMN_DURATION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.PreviewPrograms.COLUMN_INTENT_URI,
            TvContract.PreviewPrograms.COLUMN_TYPE,
            TvContract.PreviewPrograms.COLUMN_WEIGHT,
            TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI,
            TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI,
    };

    /** The gap under the dock band, the label and the space above the cards. */
    private static final float TOP_DP = 10 + 18 + 6;
    static final float CARD_H_DP = 128;
    /** 16:9 would make the row too tall for the screen; this keeps three and a half cards in view. */
    static final float CARD_W_DP = 228;
    private static final float GAP_DP = 22;
    /** TOP_DP, the cards, and a few dp for a focused card's zoom before the grid starts. */
    static final float ROOM_DP = TOP_DP + CARD_H_DP + 10;

    static final class Item {
        long id;
        String pkg, title, episodeTitle, season, episode, intentUri, image;
        int type;
        long duration, position, weight;
    }

    interface Host {
        /** A card took focus: the glass and the shelf above the dock make way. */
        void onContinueFocused(int index);
        /** Up from a card: back to the dock tile under that x (in the row's frame, which is Home's root). */
        void onContinueUp(float centerX);
        /** Down from a card: on to the grid tile under that x. */
        void onContinueDown(float centerX);
        /** The number of cards changed (0 hides the row); Home makes room for it or gives the room back. */
        void onContinueCountChanged(int count);
    }

    private final Context context;
    private final Host host;
    private final Handler main;
    private final Set<String> packages;
    private final float density;
    private final ContinueImages images;
    /** The row's frame, full screen width over Home; MainActivity moves it with the dock and clips it at the grid's top. */
    final FrameLayout view;
    private final TextView label;
    private final RecyclerView list;
    private final Adapter adapter = new Adapter();
    private List<Item> items = Collections.emptyList();
    private int generation;
    private boolean observing;
    private int lastIndex;

    private final ContentObserver observer;
    private final Runnable reload = this::load;

    ContinueRow(Context context, Host host, Handler main, List<String> packages) {
        this.context = context;
        this.host = host;
        this.main = main;
        this.packages = new HashSet<>(packages);
        this.density = context.getResources().getDisplayMetrics().density;
        images = new ContinueImages(context, main, Math.round(CARD_W_DP * density), Math.round(CARD_H_DP * density));
        view = new FrameLayout(context);
        view.setClipChildren(false);
        view.setClipToPadding(false);
        view.setVisibility(View.GONE);
        label = new TextView(context);
        label.setText(context.getString(R.string.cw_label).toUpperCase(Locale.getDefault()));
        label.setTypeface(UiFont.get(context, R.string.font_medium));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setLetterSpacing(0.16f);
        label.setTextColor(0x99FFFFFF);
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = Math.round(10 * density);
        view.addView(label, labelLp);

        list = new RecyclerView(context);
        list.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setItemAnimator(null);
        list.setHasFixedSize(true);
        // Closed to Android's own focus search: it ignores the translation MainActivity moves this row with, so it
        // sees the cards near the top of the screen and Up from the dock landed on them instead of the top bar.
        // Only focus() and focusNearest() open the row, and it closes again once focus has left it.
        list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        list.setFocusable(false);
        adapter.setHasStableIds(true);
        list.setAdapter(adapter);
        int gap = Math.round(GAP_DP * density);
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull android.graphics.Rect out, @NonNull View v,
                                                 @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                if (parent.getChildAdapterPosition(v) > 0) out.left = gap;
            }
        });
        // A few dp of room above and below for the focused card's zoom.
        int zoom = Math.round(6 * density);
        list.setPadding(0, zoom, 0, zoom);
        FrameLayout.LayoutParams listLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(CARD_H_DP * density) + 2 * zoom);
        listLp.topMargin = Math.round(TOP_DP * density) - zoom;
        view.addView(list, listLp);

        observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                main.removeCallbacks(reload);
                main.postDelayed(reload, 400);
            }
        };
    }

    boolean enabled() { return !packages.isEmpty(); }

    int count() { return items.size(); }

    /** The room the row takes under the dock band, in px. 0 while it is empty. */
    int roomPx() { return count() == 0 ? 0 : Math.round(ROOM_DP * density); }

    void start() {
        if (!enabled()) return;
        if (!observing) {
            try {
                context.getContentResolver().registerContentObserver(TvContract.PreviewPrograms.CONTENT_URI, true, observer);
                observing = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "cannot watch preview programs: " + e);
            }
        }
        load();
    }

    void stop() {
        main.removeCallbacks(reload);
        if (observing) {
            context.getContentResolver().unregisterContentObserver(observer);
            observing = false;
        }
    }

    void trimMemory() { images.clearMemory(); }

    private void load() {
        final int gen = ++generation;
        new Thread(() -> {
            List<Item> found = query(context, packages);
            if (found != null) {
                // The list was read: thumbnails of titles no longer on it are deleted.
                List<String> urls = new ArrayList<>();
                for (Item it : found) urls.add(it.image);
                images.retainOnly(urls);
            }
            main.post(() -> {
                if (gen == generation) show(found != null ? found : Collections.emptyList());
            });
        }, "continue-watching").start();
    }

    /** The rows, or null when they could not be read (no permission, provider error): not the same as none. */
    static List<Item> query(Context context, Set<String> packages) {
        if (context.checkSelfPermission(READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_TV_LISTINGS not granted: pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS");
            return null;
        }
        List<Item> out = new ArrayList<>();
        // No selection: without ACCESS_ALL_EPG_DATA the provider refuses one (docs/watch-next.md), so filter here.
        try (Cursor c = context.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI, PROJECTION,
                null, null, null)) {
            if (c == null) return null;   // no provider answered: unknown, not empty
            while (c.moveToNext()) {
                String pkg = c.getString(1);
                if (pkg == null || !packages.contains(pkg)) continue;
                Item it = new Item();
                it.id = c.getLong(0);
                it.pkg = pkg;
                it.title = c.getString(2);
                it.episodeTitle = c.getString(3);
                it.season = c.getString(4);
                it.episode = c.getString(5);
                it.duration = c.isNull(6) ? 0 : c.getLong(6);
                it.position = c.isNull(7) ? 0 : c.getLong(7);
                it.intentUri = c.getString(8);
                it.type = c.isNull(9) ? -1 : c.getInt(9);
                it.weight = c.isNull(10) ? 0 : c.getLong(10);
                it.image = !TextUtils.isEmpty(c.getString(11)) ? c.getString(11) : c.getString(12);
                if (TextUtils.isEmpty(it.title)) continue;
                // A title played to its end lingers in some apps' rows; it has nothing left to continue.
                if (it.duration > 0 && it.duration != SYNTHETIC_DURATION_MS && it.position >= it.duration * 97 / 100) continue;
                out.add(it);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "preview programs unreadable: " + e);
            return null;
        }
        // The app's own order: Nuvio weighs its list Int.MAX_VALUE - index, so heavier first is its order.
        Collections.sort(out, (a, b) -> a.weight != b.weight ? Long.compare(b.weight, a.weight) : Long.compare(a.id, b.id));
        if (out.size() > MAX_ITEMS) out = new ArrayList<>(out.subList(0, MAX_ITEMS));
        Log.d(TAG, out.size() + " continue watching rows");
        return out;
    }

    private void show(List<Item> found) {
        int before = count();
        boolean hadFocus = view.hasFocus();
        // The row emptied under the focus: hand it to the dock before the cards go.
        if (hadFocus && found.isEmpty()) host.onContinueUp(focusedCenterX());
        items = found;
        adapter.notifyDataSetChanged();
        view.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        if (hadFocus && !items.isEmpty() && !list.hasFocus()) focus(lastIndex);
        if (count() != before) host.onContinueCountChanged(count());
    }

    /**
     * Lines the row up with the dock: the first card under the first tile, the last one able to stop under the
     * last tile's right edge (endInset is the room from there to the screen's edge). In between, cards run on to
     * the screen's edges.
     */
    void place(int firstLeft, int endInset) {
        // The row is cut off at that right edge: a card scrolled in from there stops short of it by its focus zoom,
        // so its ring is not cut too.
        int end = endInset + Math.round(8 * density);
        if (list.getPaddingLeft() != firstLeft || list.getPaddingRight() != end) {
            list.setPadding(firstLeft, list.getPaddingTop(), end, list.getPaddingBottom());
            label.setTranslationX(firstLeft + 4 * density);
            // The cards were laid out before the padding came: unless one is in use, start the row again from its
            // first card, under the dock's first tile.
            if (!list.hasFocus()) ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(0, 0);
        }
    }

    /** Focuses card index, scrolling it into the row first when it is off screen. */
    boolean focus(int index) {
        if (items.isEmpty()) return false;
        open();
        final int i = Math.max(0, Math.min(index, items.size() - 1));
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(i);
        if (vh != null) return vh.itemView.requestFocus();
        list.scrollToPosition(i);
        list.post(() -> {
            RecyclerView.ViewHolder h = list.findViewHolderForAdapterPosition(i);
            if (h != null) h.itemView.requestFocus();
        });
        return true;
    }

    /** Focuses the card on screen whose centre is nearest x (a dock or grid tile's centre, in Home's root frame). */
    void focusNearest(float x) {
        open();
        View best = null;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            float c = centerX(child);
            if (child.getRight() <= 0 || child.getLeft() >= list.getWidth()) continue;   // scrolled out of view
            float d = Math.abs(c - x);
            if (d < bestD) {
                bestD = d;
                best = child;
            }
        }
        if (best != null) best.requestFocus();
        else focus(lastIndex);
    }

    int lastIndex() { return lastIndex; }

    /** Lets the cards take focus, for a move the launcher sends here itself. */
    private void open() {
        list.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
    }

    /** Shuts the row to focus search again once focus has gone elsewhere (checked after the move settles). */
    private void closeIfLeft() {
        list.post(() -> {
            if (!list.hasFocus()) list.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        });
    }

    private float centerX(View card) {
        return list.getLeft() + card.getLeft() + card.getWidth() / 2f;
    }

    private float focusedCenterX() {
        View f = list.getFocusedChild();
        return f != null ? centerX(f) : 0f;
    }

    private String subtitleLine(Item it) {
        if (!isEpisode(it)) return "";   // a film: its name alone, top left
        if (!TextUtils.isEmpty(it.season) && !TextUtils.isEmpty(it.episode)) return context.getString(R.string.cw_season_episode, it.season, it.episode);
        if (!TextUtils.isEmpty(it.episode)) return context.getString(R.string.cw_episode_only, it.episode);
        return context.getString(R.string.cw_season_only, it.season);
    }

    private static boolean isEpisode(Item it) {
        return it.type == TvContract.PreviewPrograms.TYPE_TV_EPISODE
                || !TextUtils.isEmpty(it.season) || !TextUtils.isEmpty(it.episode);
    }

    private static boolean started(Item it) {
        return it.duration > 0 && it.position > 0;
    }

    private String remaining(Item it) {
        // No progress yet: the next episode the app lined up rather than one left part-way.
        if (!started(it)) return context.getString(R.string.cw_up_next);
        if (it.duration == SYNTHETIC_DURATION_MS) {
            return context.getString(R.string.cw_watched, Math.round(100f * it.position / it.duration));
        }
        long minutes = Math.round(Math.max(0, it.duration - it.position) / 60000.0);
        if (minutes < 1) return context.getString(R.string.cw_almost_done);
        long h = minutes / 60, m = minutes % 60;
        if (h == 0) return context.getString(R.string.cw_left_m, m);
        if (m == 0) return context.getString(R.string.cw_left_h, h);
        return context.getString(R.string.cw_left_hm, h, m);
    }

    /** Opens the title where the app said: its intent URI, held to the app's own package, else the app itself. */
    private void open(int index) {
        if (index < 0 || index >= items.size()) return;
        Item it = items.get(index);
        Intent intent = null;
        if (it.intentUri != null) {
            try {
                intent = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
                ComponentName cn = intent.getComponent();
                if (cn != null && !it.pkg.equals(cn.getPackageName())) intent = null;   // only into the app that wrote the row
                else intent.setPackage(it.pkg).setSelector(null);
            } catch (URISyntaxException e) {
                intent = null;
            }
        }
        PackageManager pm = context.getPackageManager();
        if (intent == null) intent = pm.getLeanbackLaunchIntentForPackage(it.pkg);
        if (intent == null) return;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open the row's intent, opening the app: " + e);
            Intent app = pm.getLeanbackLaunchIntentForPackage(it.pkg);
            if (app != null) {
                try {
                    context.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    // ---- cards ---------------------------------------------------------------------------------------

    private final class Adapter extends RecyclerView.Adapter<Card> {
        @Override public int getItemCount() { return items.size(); }

        @Override public long getItemId(int position) { return items.get(position).id; }

        @NonNull @Override public Card onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Card(makeCard());
        }

        @Override public void onBindViewHolder(@NonNull Card h, int position) {
            Item it = items.get(position);
            h.title.setText(it.title);
            h.episode.setText(subtitleLine(it));
            h.episode.setVisibility(h.episode.getText().length() == 0 ? View.GONE : View.VISIBLE);
            boolean epTitle = isEpisode(it) && !TextUtils.isEmpty(it.episodeTitle);
            h.episodeTitle.setText(epTitle ? it.episodeTitle : "");
            h.episodeTitle.setVisibility(epTitle ? View.VISIBLE : View.GONE);
            h.left.setText(remaining(it));
            h.line.setVisibility(started(it) ? View.VISIBLE : View.INVISIBLE);
            h.line.setFraction(started(it) ? Math.min(1f, it.position / (float) it.duration) : 0f);
            images.load(it.image, h.image);
            h.itemView.setContentDescription(it.title + ", " + h.episode.getText() + " " + h.episodeTitle.getText()
                    + ", " + h.left.getText());
        }

        @Override public void onViewRecycled(@NonNull Card h) {
            images.load(null, h.image);
        }
    }

    private static final class Card extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title, episode, episodeTitle, left;
        final ProgressLine line;

        Card(View v) {
            super(v);
            Object[] parts = (Object[]) v.getTag();
            image = (ImageView) parts[0];
            title = (TextView) parts[1];
            episode = (TextView) parts[2];
            episodeTitle = (TextView) parts[3];
            left = (TextView) parts[4];
            line = (ProgressLine) parts[5];
        }
    }

    private View makeCard() {
        int w = Math.round(CARD_W_DP * density), h = Math.round(CARD_H_DP * density);
        float radius = 16 * density;
        FrameLayout card = new FrameLayout(context);
        card.setLayoutParams(new RecyclerView.LayoutParams(w, h));
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);
        // The launcher's empty-tile tone, until the thumbnail arrives (or when there is none).
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF26262B, 0xFF16161A});
        bg.setCornerRadius(radius);
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        card.setClipToOutline(true);
        // Focus: the glass highlight's crisp white rim; at rest a faint edge like the dock band's.
        GradientDrawable rest = new GradientDrawable();
        rest.setCornerRadius(radius);
        rest.setStroke(Math.round(density), 0x1FFFFFFF);
        GradientDrawable lit = new GradientDrawable();
        lit.setCornerRadius(radius);
        lit.setStroke(Math.round(2.5f * density), 0xF2FFFFFF);
        card.setForeground(rest);

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // Scrims: dark at the top for the name, darker at the foot for the episode, time and progress.
        View top = new View(context);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x00000000}));
        card.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(h * 0.45f), Gravity.TOP));
        View foot = new View(context);
        foot.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xE6000000, 0x00000000}));
        card.addView(foot, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(h * 0.6f), Gravity.BOTTOM));

        int pad = Math.round(14 * density);
        TextView title = text(17, R.string.font_medium, 0xFFFFFFFF);
        title.setShadowLayer(6 * density, 0, density, 0x80000000);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
        titleLp.setMargins(pad, Math.round(12 * density), pad, 0);
        card.addView(title, titleLp);

        // Foot: season/episode and the episode's name on the left, the time left on the right, the line under both.
        LinearLayout bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.BOTTOM);
        LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView episode = text(11, R.string.font_medium, 0xD9FFFFFF);
        episode.setLetterSpacing(0.08f);
        TextView episodeTitle = text(13, R.string.font_regular, 0xB3FFFFFF);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = Math.round(3 * density);
        info.addView(episode);
        info.addView(episodeTitle, etLp);
        bottom.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView left = text(11, R.string.font_medium, 0xFFFFFFFF);
        // The top bar's frosted pill, small.
        GradientDrawable pill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x4DFFFFFF, 0x26FFFFFF});
        pill.setCornerRadius(100 * density);
        pill.setStroke(Math.round(density), 0x33FFFFFF);
        left.setBackground(pill);
        left.setPadding(Math.round(9 * density), Math.round(4 * density), Math.round(9 * density), Math.round(4 * density));
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        leftLp.setMarginStart(Math.round(10 * density));
        bottom.addView(left, leftLp);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        bottomLp.setMargins(pad, 0, pad, Math.round(16 * density));
        card.addView(bottom, bottomLp);

        ProgressLine line = new ProgressLine(context);
        FrameLayout.LayoutParams lineLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(4 * density), Gravity.BOTTOM);
        lineLp.setMargins(pad, 0, pad, Math.round(7 * density));
        card.addView(line, lineLp);

        card.setTag(new Object[]{image, title, episode, episodeTitle, left, line});

        card.setOnFocusChangeListener((v, focused) -> {
            v.setForeground(focused ? lit : rest);
            v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f).setDuration(150).start();
            v.setTranslationZ(focused ? 1f : 0f);
            if (focused) {
                lastIndex = list.getChildAdapterPosition(v);
                host.onContinueFocused(lastIndex);
            } else {
                closeIfLeft();
            }
        });
        card.setOnClickListener(v -> open(list.getChildAdapterPosition(v)));
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int i = list.getChildAdapterPosition(v);
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    v.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    host.onContinueUp(centerX(v));
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    v.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
                    host.onContinueDown(centerX(v));
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (i <= 0) return true;   // the row's ends stop focus, as in the dock
                    v.playSoundEffect(SoundEffectConstants.NAVIGATION_LEFT);
                    return focus(i - 1);
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (i >= items.size() - 1) return true;
                    v.playSoundEffect(SoundEffectConstants.NAVIGATION_RIGHT);
                    return focus(i + 1);
                default:
                    return false;
            }
        });
        return card;
    }

    private TextView text(float sp, int font, int color) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(context, font));
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** A thin rounded track with the watched part filled. */
    private static final class ProgressLine extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private float fraction;

        ProgressLine(Context context) {
            super(context);
            track.setColor(0x40FFFFFF);
            fill.setColor(0xFFFFFFFF);
        }

        void setFraction(float f) {
            if (f == fraction) return;
            fraction = f;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), rad = h / 2f;
            r.set(0, 0, getWidth(), h);
            c.drawRoundRect(r, rad, rad, track);
            if (fraction > 0f) {
                r.right = Math.max(h, getWidth() * fraction);
                c.drawRoundRect(r, rad, rad, fill);
            }
        }
    }
}
