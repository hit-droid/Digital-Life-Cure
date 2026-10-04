package com.digitallife.ui.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v1.147.0（#88）：{@link ContactFilter} 本地搜索过滤回归。
 */
public class ContactFilterTest {

    private static final List<String> MODELS = Arrays.asList(
            "Shizuku", "Haru", "shizuku-kai", "Mao", null);

    @Test
    public void filter_emptyQueryReturnsAllNonNullInOrder() {
        List<String> r = ContactFilter.filter(Arrays.asList("a", "b", "c"), "");
        assertEquals(Arrays.asList("a", "b", "c"), r);
    }

    @Test
    public void filter_nullQueryReturnsAll() {
        List<String> r = ContactFilter.filter(Arrays.asList("a", "b"), null);
        assertEquals(Arrays.asList("a", "b"), r);
    }

    @Test
    public void filter_blankQueryTreatedAsEmpty() {
        assertEquals(3, ContactFilter.filter(Arrays.asList("a", "b", "c"), "   ").size());
    }

    @Test
    public void filter_substringMatch() {
        assertEquals(Arrays.asList("Shizuku", "shizuku-kai"),
                ContactFilter.filter(MODELS, "shizuku"));
    }

    @Test
    public void filter_caseInsensitive() {
        assertEquals(Arrays.asList("Shizuku", "shizuku-kai"),
                ContactFilter.filter(MODELS, "SHIZUKU"));
    }

    @Test
    public void filter_trimsQuery() {
        assertEquals(Arrays.asList("Haru"), ContactFilter.filter(MODELS, "  haru  "));
    }

    @Test
    public void filter_noMatchReturnsEmpty() {
        assertTrue(ContactFilter.filter(MODELS, "zzz").isEmpty());
    }

    @Test
    public void filter_nullNamesSafe() {
        assertTrue(ContactFilter.filter(null, "x").isEmpty());
    }

    @Test
    public void filter_nullElementsSkipped() {
        // MODELS 里含一个 null，子串 "shizuku" 命中 Shizuku / shizuku-kai，不因 null 崩溃
        assertEquals(Arrays.asList("Shizuku", "shizuku-kai"),
                ContactFilter.filter(MODELS, "shizuku"));
    }

    @Test
    public void filter_returnsNewListDoesNotMutateInput() {
        List<String> src = new ArrayList<>(Arrays.asList("a", "b"));
        List<String> r = ContactFilter.filter(src, "a");
        r.add("x");
        assertEquals(2, src.size());
    }

    @Test
    public void hasQuery_trueOnlyWhenNonBlank() {
        assertTrue(ContactFilter.hasQuery("x"));
        assertFalse(ContactFilter.hasQuery(""));
        assertFalse(ContactFilter.hasQuery("   "));
        assertFalse(ContactFilter.hasQuery(null));
    }
}
