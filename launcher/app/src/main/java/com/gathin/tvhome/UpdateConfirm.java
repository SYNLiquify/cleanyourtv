package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
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
 * short lines, then Install update and Cancel. Install starts the download; Android then asks once more with its
 * own screen. Home's update card and Settings > System > Updates both open it.
 */
final class UpdateConfirm {
    /** The notes shown at most; a longer list ends with "and N more". */
    private static final int MAX_NOTES = 5;

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
        list.setPadding(pad, 0, pad, Math.round(8 * d));
        TextView heading = text(activity, 11, R.string.font_medium, 0x80FFFFFF);
        heading.setText(activity.getString(R.string.update_whats_new).toUpperCase(java.util.Locale.getDefault()));
        heading.setLetterSpacing(0.12f);
        list.addView(heading);
        if (notes.isEmpty()) list.addView(line(activity, activity.getString(R.string.update_no_notes), d));
        for (int i = 0; i < Math.min(notes.size(), MAX_NOTES); i++) list.addView(line(activity, "•  " + notes.get(i), d));
        if (notes.size() > MAX_NOTES) {
            list.addView(line(activity, activity.getString(R.string.update_more_notes, notes.size() - MAX_NOTES), d));
        }
        rows.addView(list);

        View ok = row(inflater, rows, R.string.update_install);
        View cancel = row(inflater, rows, R.string.update_cancel);
        ok.setOnClickListener(v -> {
            dialog.dismiss();
            install.run();
        });
        cancel.setOnClickListener(v -> dialog.dismiss());
        Accent.restyle(panel, d, accent);

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

    private static TextView line(Activity a, String s, float d) {
        TextView t = text(a, 13, R.string.font_body, 0xCCFFFFFF);
        t.setText(s);
        t.setMaxLines(2);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setPadding(0, Math.round(4 * d), 0, 0);
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
