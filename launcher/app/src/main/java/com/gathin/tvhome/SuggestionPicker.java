package com.gathin.tvhome;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * This fork: the day's Nuvio suggestions (NuvioSuggestions). Each day takes PER_KIND movies and PER_KIND series at
 * random from the whole catalogs, the same all day and different the next. Titles that already had a day of their
 * own are left out until a catalog runs out of fresh ones; then it starts over, still without yesterday's.
 */
final class SuggestionPicker {
    static final int PER_KIND = 20;

    private SuggestionPicker() {
    }

    /** The day's titles, movies and series taking turns; restarted: a catalog ran out of titles not yet seen. */
    static final class Pick {
        final List<NuvioSuggestions.Item> items;
        final boolean restarted;

        Pick(List<NuvioSuggestions.Item> items, boolean restarted) {
            this.items = items;
            this.restarted = restarted;
        }
    }

    static Pick pick(List<NuvioSuggestions.Item> movies, List<NuvioSuggestions.Item> series, Set<String> seen,
                     Set<String> yesterday, long day) {
        Random random = new Random(day * 1_000_003L + 17);
        boolean[] restarted = {false};
        List<NuvioSuggestions.Item> m = fresh(movies, seen, yesterday, random, restarted);
        List<NuvioSuggestions.Item> s = fresh(series, seen, yesterday, random, restarted);
        List<NuvioSuggestions.Item> out = new ArrayList<>();
        for (int i = 0; i < m.size() || i < s.size(); i++) {
            if (i < m.size()) out.add(m.get(i));
            if (i < s.size()) out.add(s.get(i));
        }
        return new Pick(out, restarted[0]);
    }

    private static List<NuvioSuggestions.Item> fresh(List<NuvioSuggestions.Item> all, Set<String> seen,
                                                     Set<String> yesterday, Random random, boolean[] restarted) {
        List<NuvioSuggestions.Item> pool = without(all, seen, yesterday);
        if (pool.size() < PER_KIND) {
            restarted[0] = true;
            pool = without(all, Collections.emptySet(), yesterday);   // every title had its turn: start over
            if (pool.size() < PER_KIND) pool = new ArrayList<>(all);  // a catalog too small to skip yesterday's
        }
        Collections.shuffle(pool, random);
        return pool.subList(0, Math.min(PER_KIND, pool.size()));
    }

    private static List<NuvioSuggestions.Item> without(List<NuvioSuggestions.Item> all, Collection<String> a,
                                                       Collection<String> b) {
        List<NuvioSuggestions.Item> out = new ArrayList<>();
        for (NuvioSuggestions.Item it : all) {
            String key = key(it);
            if (!a.contains(key) && !b.contains(key)) out.add(it);
        }
        return out;
    }

    /** A title's key: its IMDb id, else its name. */
    static String key(NuvioSuggestions.Item it) {
        return it.id != null && !it.id.isEmpty() ? it.id : it.name;
    }
}
