package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;

/**
 * This fork: the "What's new" list of a release's notes, as work\publish-update.ps1 writes them: a "## What's new"
 * heading, then one "- **Title.** First sentence." line per change. Each line is kept whole, its **bold** marks
 * included, and the install confirmation (UpdateConfirm) shows it with styled(): the title in bold, then the rest. A "## Mainline" section (the
 * changes merged from upstream) follows as a HEADING line, then its own bullets.
 */
final class ReleaseNotes {
    /** Starts an item that is a section heading (e.g. "# Mainline"), not a change. */
    static final String HEADING = "# ";

    /** A note as shown: each **bold** run in bold, the marks themselves left out. */
    static CharSequence styled(String note) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        boolean bold = false;
        for (String part : note.split("\\*\\*", -1)) {
            int start = out.length();
            out.append(part);
            if (bold && out.length() > start) {
                out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, out.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            bold = !bold;
        }
        return out;
    }

    private ReleaseNotes() {
    }

    static List<String> parse(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        boolean inList = false, sawHeading = false;
        String pendingHeading = null;   // written before the section's first bullet, so an empty section adds nothing
        for (String raw : body.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("#")) {
                sawHeading = true;
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                boolean mainline = lower.contains("mainline");
                inList = mainline || lower.contains("what's new") || lower.contains("what’s new");
                pendingHeading = mainline ? HEADING + line.replaceFirst("^#+\\s*", "").trim() : null;
                continue;
            }
            // Notes without headings (older releases): every bullet counts.
            if ((inList || !sawHeading) && (line.startsWith("- ") || line.startsWith("* "))) {
                String item = line.substring(2).replace("`", "").trim();
                if (item.replace("**", "").trim().isEmpty()) continue;
                if (item.isEmpty()) continue;
                if (pendingHeading != null) {
                    out.add(pendingHeading);
                    pendingHeading = null;
                }
                out.add(item);
            }
        }
        return out;
    }
}
