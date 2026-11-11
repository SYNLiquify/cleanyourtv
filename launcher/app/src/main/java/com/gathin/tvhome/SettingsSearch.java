package com.gathin.tvhome;

import java.util.Locale;

/**
 * This fork: how a setting matches what is typed in Settings search (SettingsActivity.showResults). WORD: its name, or
 * one of its other words (keywords, a comma-separated list the search finds it by but does not show), starts with it
 * or has a word that does; these come first. ANYWHERE: one of them has it anywhere, or its page does.
 */
final class SettingsSearch {

    static final int NONE = 0, ANYWHERE = 1, WORD = 2;
    /** What separates keywords: a comma, and the Arabic and the ideographic and full-width ones. */
    private static final String SEPARATORS = "[,،、，]";

    private SettingsSearch() {
    }

    static int match(String typed, String name, String where, String keywords, Locale locale) {
        String q = typed.trim().toLowerCase(locale);
        if (q.isEmpty()) return NONE;
        int best = rank(q, name.toLowerCase(locale));
        for (String word : keywords.split(SEPARATORS)) {
            if (best == WORD) break;
            best = Math.max(best, rank(q, word.trim().toLowerCase(locale)));
        }
        if (best == NONE && where.toLowerCase(locale).contains(q)) best = ANYWHERE;
        return best;
    }

    private static int rank(String q, String text) {
        if (text.isEmpty()) return NONE;
        if (text.startsWith(q) || text.contains(" " + q)) return WORD;
        return text.contains(q) ? ANYWHERE : NONE;
    }
}
