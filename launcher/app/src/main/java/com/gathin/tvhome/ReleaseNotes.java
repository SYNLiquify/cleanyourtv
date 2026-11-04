package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.List;

/**
 * This fork: the "What's new" list of a release's notes, as work\publish-update.ps1 writes them: a "## What's new"
 * heading, then one "- **Title.** First sentence." line per change. The install confirmation (UpdateConfirm) shows
 * the bold titles; a line without bold shows whole, with markdown marks taken out.
 */
final class ReleaseNotes {
    private ReleaseNotes() {
    }

    static List<String> parse(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) return out;
        boolean inList = false, sawHeading = false;
        for (String raw : body.split("\r?\n")) {
            String line = raw.trim();
            if (line.startsWith("#")) {
                sawHeading = true;
                inList = line.toLowerCase(java.util.Locale.ROOT).contains("what's new")
                        || line.toLowerCase(java.util.Locale.ROOT).contains("what’s new");
                continue;
            }
            // Notes without headings (older releases): every bullet counts.
            if ((inList || !sawHeading) && (line.startsWith("- ") || line.startsWith("* "))) {
                String item = line.substring(2).trim();
                int start = item.indexOf("**"), end = start < 0 ? -1 : item.indexOf("**", start + 2);
                if (start >= 0 && end > start) item = item.substring(start + 2, end);
                item = item.replace("**", "").replace("`", "").trim();
                if (!item.isEmpty()) out.add(item);
            }
        }
        return out;
    }
}
