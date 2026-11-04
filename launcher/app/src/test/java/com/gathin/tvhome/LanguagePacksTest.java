package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.junit.Test;

public class LanguagePacksTest {
    private static final List<String> TAGS = Arrays.asList("es", "es-419", "pt-BR", "pt-PT", "zh-CN", "zh-TW",
            "sr-Latn", "id", "he", "ja");

    @Test
    public void androidEscapesAndQuotesAreUndone() {
        assertEquals("TV's language", LanguagePacks.unescape("TV\\'s language"));
        assertEquals("a\nb", LanguagePacks.unescape("a\\nb"));
        assertEquals("say \"hi\"", LanguagePacks.unescape("say \\\"hi\\\""));
        assertEquals("  kept  ", LanguagePacks.unescape("\"  kept  \""));
        assertEquals("one two", LanguagePacks.unescape("  one \n   two "));
        assertEquals("@home", LanguagePacks.unescape("\\@home"));
        assertEquals("é!", LanguagePacks.unescape("\\u00e9!"));
    }

    @Test
    public void theClosestPackIsChosen() {
        assertEquals("es", LanguagePacks.bestTag(TAGS, Locale.forLanguageTag("es-ES")));
        assertEquals("es-419", LanguagePacks.bestTag(TAGS, Locale.forLanguageTag("es-419")));
        assertEquals("pt-PT", LanguagePacks.bestTag(TAGS, Locale.forLanguageTag("pt-PT")));
        assertEquals("pt-BR", LanguagePacks.bestTag(TAGS, Locale.forLanguageTag("pt-BR")));
        assertEquals("zh-TW", LanguagePacks.bestTag(TAGS, Locale.forLanguageTag("zh-HK")));
        assertEquals("zh-CN", LanguagePacks.bestTag(TAGS, Locale.forLanguageTag("zh-CN")));
        assertEquals("sr-Latn", LanguagePacks.bestTag(TAGS, Locale.forLanguageTag("sr-Latn-RS")));
        assertEquals("id", LanguagePacks.bestTag(TAGS, Locale.forLanguageTag("id-ID")));
        assertEquals("he", LanguagePacks.bestTag(TAGS, Locale.forLanguageTag("iw-IL")));
        assertNull(LanguagePacks.bestTag(TAGS, Locale.forLanguageTag("en-US")));
        assertNull(LanguagePacks.bestTag(TAGS, Locale.forLanguageTag("fr-FR")));
    }

    @Test
    public void regionalPacksBuildOnTheirParent() {
        assertArrayEquals(new String[]{"values-es", "values-b+es+419"}, LanguagePacks.folders("es-419"));
        assertArrayEquals(new String[]{"values-pt", "values-pt-rPT"}, LanguagePacks.folders("pt-PT"));
        assertArrayEquals(new String[]{"values-in"}, LanguagePacks.folders("id"));
        assertArrayEquals(new String[]{"values-ja"}, LanguagePacks.folders("ja"));
    }
}
