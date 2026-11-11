package com.gathin.tvhome;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.Locale;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SettingsSearchTest {

    /** A string of a language's settings_menu.xml, as the app shows it. */
    private static String string(String folder, String name) throws Exception {
        File res = new File("src/main/res");
        if (!res.isDirectory()) res = new File("app/src/main/res");
        NodeList strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File(res, folder + "/settings_menu.xml")).getElementsByTagName("string");
        for (int i = 0; i < strings.getLength(); i++) {
            Element s = (Element) strings.item(i);
            if (name.equals(s.getAttribute("name"))) return s.getTextContent().replace("\\'", "'");
        }
        throw new AssertionError(folder + " has no " + name);
    }

    private static int dim(String folder, String typed, Locale locale) throws Exception {
        return SettingsSearch.match(typed, string(folder, "sm_qs_dim"), string(folder, "sm_qs_title"),
                string(folder, "sm_qs_dim_keywords") + ",", locale);
    }

    @Test public void findsDimScreenInEnglish() throws Exception {
        for (String typed : new String[]{"dim", "Dim", "dim screen", "screen", "brightness", "bright", "darker"}) {
            assertEquals(typed, SettingsSearch.WORD, dim("values", typed, Locale.ENGLISH));
        }
        assertEquals(SettingsSearch.ANYWHERE, dim("values", "quick", Locale.ENGLISH));   // its page
        assertEquals(SettingsSearch.NONE, dim("values", "color", Locale.ENGLISH));
    }

    @Test public void findsDimScreenInJapanese() throws Exception {
        Locale ja = Locale.JAPANESE;
        for (String typed : new String[]{"画面", "画面を暗く", "明るさ", "輝度", "暗く", "減光"}) {
            assertEquals(typed, SettingsSearch.WORD, dim("values-ja", typed, ja));
        }
        assertEquals(SettingsSearch.ANYWHERE, dim("values-ja", "暗くする", ja));   // inside its name
        assertEquals(SettingsSearch.NONE, dim("values-ja", "色", ja));
    }

    @Test public void everyLanguageHasItsOwnKeywords() throws Exception {
        File res = new File("src/main/res");
        if (!res.isDirectory()) res = new File("app/src/main/res");
        File[] folders = res.listFiles((dir, name) -> name.startsWith("values-")
                && new File(dir, name + "/settings_menu.xml").isFile());
        assertFalse(folders == null || folders.length == 0);
        String english = string("values", "sm_qs_dim_keywords");
        for (File f : folders) {
            assertFalse(f.getName(), english.equals(string(f.getName(), "sm_qs_dim_keywords")));
        }
    }

    @Test public void namesRankAsBefore() {
        assertEquals(SettingsSearch.WORD, SettingsSearch.match("color", "Custom color", "Look", "", Locale.ENGLISH));
        assertEquals(SettingsSearch.WORD, SettingsSearch.match("cus", "Custom color", "Look", "", Locale.ENGLISH));
        assertEquals(SettingsSearch.ANYWHERE, SettingsSearch.match("olor", "Custom color", "Look", "", Locale.ENGLISH));
        assertEquals(SettingsSearch.ANYWHERE, SettingsSearch.match("look", "Custom color", "Look", "", Locale.ENGLISH));
        assertEquals(SettingsSearch.NONE, SettingsSearch.match("dim", "Custom color", "Look", "", Locale.ENGLISH));
        assertEquals(SettingsSearch.NONE, SettingsSearch.match("  ", "Custom color", "Look", "", Locale.ENGLISH));
    }

    @Test public void keywordsSplitOnEveryComma() {
        assertEquals(SettingsSearch.WORD, SettingsSearch.match("輝度", "画面を暗くする", "", "明るさ、輝度", Locale.JAPANESE));
        assertEquals(SettingsSearch.WORD, SettingsSearch.match("إضاءة", "تعتيم الشاشة", "", "السطوع، إضاءة",
                new Locale("ar")));
        assertEquals(SettingsSearch.WORD, SettingsSearch.match("night", "Dim screen", "", "Brightness,,Night ",
                Locale.ENGLISH));
    }
}
