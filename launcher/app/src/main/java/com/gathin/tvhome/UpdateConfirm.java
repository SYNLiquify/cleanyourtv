package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * This fork: the step before a launcher update downloads, in the same compact glass card as Home's "Launcher
 * update available" (popup_inputs with item_input rows): the version, the release's What's new (ReleaseNotes) as
 * short lines in a box that scrolls (Up from Install reaches it, Up/Down scroll it), then Install update and Cancel. Install starts the download; Android then asks once more with its
 * own screen. Home's update card and Settings > System > Updates both open it.
 */
final class UpdateConfirm {
    /** The notes shown at most; a longer list ends with "and N more". The box scrolls, so this only guards odd notes. */
    private static final int MAX_NOTES = 60;

    private UpdateConfirm() {
    }

    static void show(Activity activity, Updates.Offer offer, int accent, Runnable install) {
        float d = activity.getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LayoutInflater inflater = LayoutInflater.from(activity);
        View panel = inflater.inflate(R.layout.popup_inputs, null, false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        int pad = Math.round(14 * d);

        TextView head = text(activity, 15, R.string.font_medium, 0xB3FFFFFF);
        head.setText(activity.getString(R.string.update_confirm_title,
                offer.name.isEmpty() ? String.valueOf(offer.code) : offer.name));
        head.setPadding(pad, Math.round(10 * d), pad, Math.round(2 * d));
        rows.addView(head);

        TextView current = text(activity, 12, R.string.font_body, 0x80FFFFFF);
        current.setText(activity.getString(R.string.update_confirm_current, Updates.installedName(activity)));
        current.setPadding(pad, 0, pad, Math.round(8 * d));
        rows.addView(current);

        List<String> notes = offer.notes;
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(pad - Math.round(6 * d), Math.round(4 * d), pad - Math.round(6 * d), Math.round(8 * d));
        list.addView(heading(activity, activity.getString(R.string.update_whats_new), 0));
        if (notes.isEmpty()) list.addView(line(activity, activity.getString(R.string.update_no_notes), d));
        for (int i = 0; i < Math.min(notes.size(), MAX_NOTES); i++) {
            String note = notes.get(i);
            // A section of the notes, such as Mainline (the changes merged from upstream), gets a heading of its own.
            if (note.startsWith(ReleaseNotes.HEADING)) {
                list.addView(heading(activity, note.substring(ReleaseNotes.HEADING.length()), Math.round(12 * d)));
            } else {
                TextView t = line(activity, "", d);
                t.setText(new android.text.SpannableStringBuilder("•  ").append(ReleaseNotes.styled(note)));
                list.addView(t);
            }
        }
        if (notes.size() > MAX_NOTES) {
            list.addView(line(activity, activity.getString(R.string.update_more_notes, notes.size() - MAX_NOTES), d));
        }
        // The notes scroll in a box no taller than the room under the top bar leaves beside the title and the two
        // buttons. Up from Install selects it; OK starts scrolling it with Up/Down, and OK (or Back) again stops
        // scrolling and leaves it selected, so Down goes to Install from anywhere in the notes.
        int screen = activity.getResources().getDisplayMetrics().heightPixels;
        NotesBox scroll = new NotesBox(activity, Math.max(Math.round(120 * d), screen - Math.round(330 * d)));
        scroll.setFocusable(true);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(Math.round(24 * d));
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setScrollBarStyle(View.SCROLLBARS_INSIDE_INSET);
        android.graphics.drawable.StateListDrawable bg = new android.graphics.drawable.StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_activated}, tint(0x33FFFFFF, 10 * d));   // scrolling
        bg.addState(new int[]{android.R.attr.state_focused}, tint(0x1AFFFFFF, 10 * d));     // selected
        bg.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        scroll.addView(list);
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(-1, -2);
        box.setMargins(Math.round(6 * d), 0, Math.round(6 * d), Math.round(4 * d));
        rows.addView(scroll, box);

        View ok = row(inflater, rows, R.string.update_install);
        View cancel = row(inflater, rows, R.string.update_cancel);
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            install.run();
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        Accent.restyle(panel, d, accent);
        scroll.setBackground(bg);   // after restyle, which would fill it with the accent like a row

        dialog.setContentView(panel);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // Where Home's update card sits, under the top bar, a little wider for the notes.
            w.setLayout(Math.round(400 * d), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.y = Math.round(120 * d);
            lp.dimAmount = 0.35f;
            w.setAttributes(lp);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.setOnShowListener(x -> ok.requestFocus());
        dialog.show();
    }

    /** One item_input row with only its title, as Home's menus use them. */
    private static View row(LayoutInflater inflater, LinearLayout rows, int title) {
        View row = inflater.inflate(R.layout.item_input, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        row.findViewById(R.id.status).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        rows.addView(row);
        return row;
    }

    /**
     * The notes' box. Selected, Up/Down move on like any row; OK makes it scroll (activated: brighter, with a scroll
     * bar), and then Up/Down scroll it; OK or Back stops scrolling and leaves it selected.
     */
    private static final class NotesBox extends android.widget.ScrollView {
        private final int maxHeight;

        NotesBox(Activity activity, int maxHeight) {
            super(activity);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        }

        private void scrolling(boolean on) {
            setActivated(on);
            setVerticalScrollBarEnabled(on);
            if (on) awakenScrollBars();
        }

        @Override
        public boolean dispatchKeyEvent(android.view.KeyEvent event) {
            int key = event.getKeyCode();
            boolean ok = key == android.view.KeyEvent.KEYCODE_DPAD_CENTER || key == android.view.KeyEvent.KEYCODE_ENTER
                    || key == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER;
            boolean back = key == android.view.KeyEvent.KEYCODE_BACK && isActivated();
            if (ok || back) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP && !event.isCanceled()) {
                    scrolling(!isActivated());
                }
                return true;
            }
            if (!isActivated()) {
                // Not scrolling: Up/Down leave the box like any row (Down to Install), never scroll it.
                if (key == android.view.KeyEvent.KEYCODE_DPAD_UP || key == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                        || key == android.view.KeyEvent.KEYCODE_PAGE_UP || key == android.view.KeyEvent.KEYCODE_PAGE_DOWN) {
                    if (event.getAction() != android.view.KeyEvent.ACTION_DOWN) return true;
                    View next = focusSearch(key == android.view.KeyEvent.KEYCODE_DPAD_UP
                            || key == android.view.KeyEvent.KEYCODE_PAGE_UP ? FOCUS_UP : FOCUS_DOWN);
                    if (next != null) next.requestFocus();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
            // Scrolling: Up/Down only scroll, and stay in the box at either end.
            if (key == android.view.KeyEvent.KEYCODE_DPAD_UP || key == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) {
                    int step = Math.max(1, getHeight() / 3);
                    smoothScrollBy(0, key == android.view.KeyEvent.KEYCODE_DPAD_UP ? -step : step);
                    awakenScrollBars();
                }
                return true;
            }
            if (key == android.view.KeyEvent.KEYCODE_DPAD_LEFT || key == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) return true;
            return super.dispatchKeyEvent(event);
        }

        @Override
        protected void onFocusChanged(boolean gained, int direction, android.graphics.Rect previous) {
            super.onFocusChanged(gained, direction, previous);
            if (!gained) scrolling(false);
        }
    }

    private static android.graphics.drawable.GradientDrawable tint(int color, float radius) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** A small spaced-out capital heading over a section of the notes. */
    private static TextView heading(Activity a, String s, int top) {
        TextView t = text(a, 11, R.string.font_medium, 0x80FFFFFF);
        t.setText(s.toUpperCase(java.util.Locale.getDefault()));
        t.setLetterSpacing(0.12f);
        t.setPadding(0, top, 0, 0);
        return t;
    }

    private static TextView line(Activity a, String s, float d) {
        TextView t = text(a, 13, R.string.font_body, 0xCCFFFFFF);
        t.setText(s);
        t.setPadding(0, Math.round(6 * d), 0, 0);   // whole: the box scrolls
        t.setLineSpacing(0, 1.1f);
        return t;
    }

    private static TextView text(Activity a, int sp, int font, int color) {
        TextView t = new TextView(a);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(a, font));
        t.setTextColor(color);
        t.setGravity(Gravity.LEFT);
        return t;
    }
}
