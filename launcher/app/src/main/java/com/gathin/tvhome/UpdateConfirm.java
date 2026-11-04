package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * This fork: the step before a launcher update downloads. A glass card names the version, lists the release's
 * "What's new" (ReleaseNotes) and asks Install or Cancel; Install starts the download, and Android then asks once
 * more with its own screen. Home's update card and Settings > System > Updates both open it.
 */
final class UpdateConfirm {
    /** The notes shown at most; a longer list ends with "and N more". */
    private static final int MAX_NOTES = 7;

    private UpdateConfirm() {
    }

    static void show(Activity activity, Updates.Offer offer, int accent, Runnable install) {
        float d = activity.getResources().getDisplayMetrics().density;
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(32 * d);
        card.setPadding(pad, Math.round(28 * d), pad, Math.round(26 * d));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF2262A33, 0xF5121418});
        bg.setCornerRadius(28 * d);
        bg.setStroke(Math.max(1, Math.round(d)), 0x33FFFFFF);
        card.setBackground(bg);

        TextView kicker = text(activity, 12, R.string.font_medium, 0x99FFFFFF);
        kicker.setText(activity.getString(R.string.update_confirm_kicker).toUpperCase(Locale.getDefault()));
        kicker.setLetterSpacing(0.18f);
        card.addView(kicker);

        TextView title = text(activity, 26, R.string.font_regular, 0xFFFFFFFF);
        title.setText(activity.getString(R.string.update_confirm_title,
                offer.name.isEmpty() ? String.valueOf(offer.code) : offer.name));
        card.addView(title, margins(0, Math.round(6 * d)));

        TextView current = text(activity, 15, R.string.font_regular, 0x99FFFFFF);
        current.setText(activity.getString(R.string.update_confirm_current, Updates.installedName(activity)));
        card.addView(current, margins(0, Math.round(4 * d)));

        TextView heading = text(activity, 12, R.string.font_medium, 0x99FFFFFF);
        heading.setText(activity.getString(R.string.update_whats_new).toUpperCase(Locale.getDefault()));
        heading.setLetterSpacing(0.18f);
        card.addView(heading, margins(0, Math.round(22 * d)));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        List<String> notes = offer.notes;
        if (notes.isEmpty()) {
            TextView none = text(activity, 16, R.string.font_regular, 0xB3FFFFFF);
            none.setText(R.string.update_no_notes);
            list.addView(none, margins(0, Math.round(8 * d)));
        }
        for (int i = 0; i < Math.min(notes.size(), MAX_NOTES); i++) {
            list.addView(bullet(activity, notes.get(i), d), margins(0, Math.round(8 * d)));
        }
        if (notes.size() > MAX_NOTES) {
            TextView more = text(activity, 15, R.string.font_regular, 0x99FFFFFF);
            more.setText(activity.getString(R.string.update_more_notes, notes.size() - MAX_NOTES));
            list.addView(more, margins(Math.round(22 * d), Math.round(8 * d)));
        }
        card.addView(list);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        TextView cancel = button(activity, R.string.update_cancel, accent, d);
        TextView ok = button(activity, R.string.update_install, accent, d);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, Math.round(52 * d));
        gap.setMarginEnd(Math.round(14 * d));
        buttons.addView(cancel, gap);
        buttons.addView(ok, new LinearLayout.LayoutParams(-2, Math.round(52 * d)));
        card.addView(buttons, margins(0, Math.round(28 * d)));

        cancel.setOnClickListener(v -> dialog.dismiss());
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            install.run();
        });

        dialog.setContentView(card);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.round(680 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
        }
        dialog.setOnShowListener(x -> ok.requestFocus());
        dialog.show();
    }

    private static TextView bullet(Activity a, String note, float d) {
        TextView t = text(a, 17, R.string.font_regular, 0xE6FFFFFF);
        t.setText("•  " + note);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        // The second line lines up with the first line's text, not the dot.
        t.setPadding(0, 0, Math.round(8 * d), 0);
        return t;
    }

    private static TextView button(Activity a, int label, int accent, float d) {
        TextView b = text(a, 17, R.string.font_medium, 0xFFFFFFFF);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(Math.round(28 * d), 0, Math.round(28 * d), 0);
        b.setMinWidth(Math.round(140 * d));
        b.setBackground(Accent.pill(26 * d, d, accent));
        b.setTextColor(Accent.ink(accent, 0xFFFFFFFF));
        return b;
    }

    private static TextView text(Activity a, int sp, int font, int color) {
        TextView t = new TextView(a);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(UiFont.get(a, font));
        t.setTextColor(color);
        t.setGravity(Gravity.LEFT);
        return t;
    }

    private static LinearLayout.LayoutParams margins(int start, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMarginStart(start);
        lp.topMargin = top;
        return lp;
    }
}
