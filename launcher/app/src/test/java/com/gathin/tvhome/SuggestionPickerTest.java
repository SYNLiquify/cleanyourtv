package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class SuggestionPickerTest {
    private static List<NuvioSuggestions.Item> catalog(String prefix, int n) {
        List<NuvioSuggestions.Item> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            NuvioSuggestions.Item it = new NuvioSuggestions.Item();
            it.id = prefix + i;
            it.name = prefix + " title " + i;
            out.add(it);
        }
        return out;
    }

    private static Set<String> keys(List<NuvioSuggestions.Item> items) {
        Set<String> out = new HashSet<>();
        for (NuvioSuggestions.Item it : items) out.add(SuggestionPicker.key(it));
        return out;
    }

    @Test
    public void sameDaySameTitlesNextDayOthers() {
        List<NuvioSuggestions.Item> m = catalog("tm", 100), s = catalog("ts", 100);
        Set<String> none = Collections.emptySet();
        SuggestionPicker.Pick a = SuggestionPicker.pick(m, s, none, none, 20_000);
        SuggestionPicker.Pick again = SuggestionPicker.pick(m, s, none, none, 20_000);
        assertEquals(40, a.items.size());
        assertEquals(keys(a.items), keys(again.items));
        // The next day, with today's titles seen: none of them come back.
        Set<String> seen = keys(a.items);
        SuggestionPicker.Pick tomorrow = SuggestionPicker.pick(m, s, seen, seen, 20_001);
        assertEquals(40, tomorrow.items.size());
        Set<String> overlap = keys(tomorrow.items);
        overlap.retainAll(seen);
        assertTrue(overlap.isEmpty());
        assertFalse(tomorrow.restarted);
        assertNotEquals(keys(a.items), keys(tomorrow.items));
    }

    @Test
    public void moviesAndSeriesTakeTurns() {
        SuggestionPicker.Pick p = SuggestionPicker.pick(catalog("tm", 30), catalog("ts", 30), Collections.emptySet(),
                Collections.emptySet(), 1);
        for (int i = 0; i < p.items.size(); i++) assertEquals(i % 2 == 0, p.items.get(i).id.startsWith("tm"));
    }

    @Test
    public void startsOverWithoutYesterdayWhenEverythingWasSeen() {
        List<NuvioSuggestions.Item> m = catalog("tm", 30), s = catalog("ts", 30);
        Set<String> seen = keys(m);
        seen.addAll(keys(s));
        Set<String> yesterday = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            yesterday.add("tm" + i);
            yesterday.add("ts" + i);
        }
        SuggestionPicker.Pick p = SuggestionPicker.pick(m, s, seen, yesterday, 7);
        assertTrue(p.restarted);
        assertEquals(40, p.items.size());
        Set<String> overlap = keys(p.items);
        overlap.retainAll(yesterday);
        assertTrue(overlap.isEmpty());
    }
}
