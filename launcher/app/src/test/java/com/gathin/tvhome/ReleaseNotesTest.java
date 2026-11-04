package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class ReleaseNotesTest {
    @Test
    public void boldTitlesOfWhatsNew() {
        String body = "## What's new\n\n"
                + "- **Check for an update on every Home press.** Each Home press checks.\n"
                + "- **Open the install confirm screen when the TV never asks.** On the Hisense...\r\n\n"
                + "## Installing\n\n- not a change\n\nsha256: abc";
        assertEquals(Arrays.asList("Check for an update on every Home press.",
                "Open the install confirm screen when the TV never asks."), ReleaseNotes.parse(body));
    }

    @Test
    public void oldNotesWithoutHeadings() {
        assertTrue(ReleaseNotes.parse("Built from apple-tv-home at e0384f68.\n\nsha256: abc").isEmpty());
        assertEquals(Arrays.asList("A plain `line`".replace("`", "")), ReleaseNotes.parse("- A plain `line`"));
        assertTrue(ReleaseNotes.parse(null).isEmpty());
    }
}
