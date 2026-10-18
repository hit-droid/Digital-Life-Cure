package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 动作池搜索过滤纯逻辑单测（v1.171.0）。
 *
 * <p>锁住三条契约：①归一化 = trim + 小写；②空查询视为全部命中（调用方据此免过滤）；
 * ③匹配是大小写不敏感的 contains，null 字段不命中。</p>
 */
public class MotionFilterTest {

    @Test
    public void normalize_trimsAndLowercases() {
        assertEquals("idle", MotionFilter.normalize("  IDLE "));
        assertEquals("", MotionFilter.normalize(null));
        assertEquals("", MotionFilter.normalize("   "));
    }

    @Test
    public void isBlank_trueForNullEmptyWhitespace() {
        assertTrue(MotionFilter.isBlank(null));
        assertTrue(MotionFilter.isBlank(""));
        assertTrue(MotionFilter.isBlank("  \t "));
        assertFalse(MotionFilter.isBlank("a"));
    }

    @Test
    public void matches_isCaseInsensitiveContains() {
        assertTrue(MotionFilter.matches("Scene1.motion3.json", "scene1"));
        assertTrue(MotionFilter.matches("TapBody", "tap"));
        assertFalse(MotionFilter.matches("Idle", "wave"));
    }

    @Test
    public void matches_emptyQueryHitsEverything() {
        assertTrue(MotionFilter.matches(null, ""));
        assertTrue(MotionFilter.matches("anything", null));
        assertTrue(MotionFilter.matches("anything", ""));
    }

    @Test
    public void matches_nullHaystackMissesNonEmptyQuery() {
        assertFalse(MotionFilter.matches(null, "idle"));
    }

    @Test
    public void anyMatches_trueIfAnyFieldHits() {
        assertTrue(MotionFilter.anyMatches("f0", "Idle", "F01", "white.exp3.json"));
        assertTrue(MotionFilter.anyMatches("f01", "Idle", "F01"));
        assertTrue(MotionFilter.anyMatches("white", "F01", "white eyes.exp3.json"));
    }

    @Test
    public void anyMatches_falseIfNoFieldHits() {
        assertFalse(MotionFilter.anyMatches("zzz", "Idle", "F01", "white.exp3.json"));
    }

    @Test
    public void anyMatches_emptyQueryHitsEverything() {
        assertTrue(MotionFilter.anyMatches("", "Idle", "F01"));
        assertTrue(MotionFilter.anyMatches(null));
    }
}
