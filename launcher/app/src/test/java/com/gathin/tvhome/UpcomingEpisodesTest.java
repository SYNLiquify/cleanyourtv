package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class UpcomingEpisodesTest {
    private static UpcomingEpisodes.Episode ep(int s, int e, String released) {
        return new UpcomingEpisodes.Episode(s, e, "S" + s + "E" + e, Instant.parse(released).toEpochMilli());
    }

    private static final List<UpcomingEpisodes.Episode> SHOW = Arrays.asList(
            ep(0, 1, "2026-01-01T00:00:00Z"),            // a special: never "next"
            ep(4, 6, "2026-11-20T11:00:00Z"),
            ep(4, 4, "2026-11-06T11:00:00Z"),
            ep(4, 5, "2026-11-13T11:00:00Z"),
            ep(5, 1, "2027-10-01T11:00:00Z"),
            ep(3, 10, "2025-11-30T11:00:00Z"));

    @Test
    public void nextIsTheEpisodeRightAfter() {
        UpcomingEpisodes.Episode n = UpcomingEpisodes.next(SHOW, 4, 4);
        assertEquals(4, n.season);
        assertEquals(5, n.episode);
        // The end of a season: on to the next one, skipping specials.
        n = UpcomingEpisodes.next(SHOW, 3, 10);
        assertEquals(4, n.season);
        assertEquals(4, n.episode);
        n = UpcomingEpisodes.next(SHOW, 4, 6);
        assertEquals(5, n.season);
        assertEquals(1, n.episode);
        assertNull(UpcomingEpisodes.next(SHOW, 5, 1));
    }

    @Test
    public void daysCountCalendarDaysInTheTvsZone() {
        ZoneId chicago = ZoneId.of("America/Chicago");
        long now = Instant.parse("2026-11-10T20:00:00Z").toEpochMilli();   // 2 pm, the 10th
        long airs = Instant.parse("2026-11-13T11:00:00Z").toEpochMilli();  // 5 am, the 13th
        assertEquals(3, UpcomingEpisodes.daysUntil(airs, now, chicago));
        // Late in the evening it is still the same day: one more to go than UTC would say.
        long lateEvening = Instant.parse("2026-11-11T04:30:00Z").toEpochMilli();   // 10:30 pm, the 10th
        assertEquals(3, UpcomingEpisodes.daysUntil(airs, lateEvening, chicago));
        assertEquals(0, UpcomingEpisodes.daysUntil(airs, airs - 60_000, chicago));
    }

    @Test
    public void dueOnlyWhenStillToComeAndWithinAMonth() {
        ZoneId utc = ZoneId.of("UTC");
        long now = Instant.parse("2026-11-10T12:00:00Z").toEpochMilli();
        assertTrue(UpcomingEpisodes.due(Instant.parse("2026-11-13T11:00:00Z").toEpochMilli(), now, utc));
        assertFalse(UpcomingEpisodes.due(Instant.parse("2026-11-10T11:00:00Z").toEpochMilli(), now, utc));   // aired
        assertTrue(UpcomingEpisodes.due(Instant.parse("2026-12-10T11:00:00Z").toEpochMilli(), now, utc));
        assertFalse(UpcomingEpisodes.due(Instant.parse("2026-12-11T11:00:00Z").toEpochMilli(), now, utc));
    }

    @Test
    public void parsesCinemetaDates() {
        assertEquals(Instant.parse("2026-11-13T11:00:00Z").toEpochMilli(),
                UpcomingEpisodes.parseDate("2026-11-13T11:00:00.000Z"));
        assertEquals(0, UpcomingEpisodes.parseDate("not a date"));
    }
}
