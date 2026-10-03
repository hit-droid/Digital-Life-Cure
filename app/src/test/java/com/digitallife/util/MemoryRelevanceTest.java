package com.digitallife.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * {@link MemoryRelevance} 回归。
 *
 * 重点锁住 v1.121.0 修的那个 bug：对话大脑侧原先用「整句 query 做 contains」，
 * 中文永远召不回——用户问"我喜欢喝什么咖啡"匹配不到记忆"喜欢喝美式"。
 */
public class MemoryRelevanceTest {

    // ==================== 关键词提取 ====================

    @Test
    public void extractKeywords_englishKeepsLen3PlusAndDropsStopwords() {
        List<String> kws = MemoryRelevance.extractKeywords("what coffee do I like");
        assertTrue("应保留 coffee：" + kws, kws.contains("coffee"));
        assertFalse("what 是停用词：" + kws, kws.contains("what"));
        assertFalse("like 是停用词：" + kws, kws.contains("like"));
        assertFalse("do 仅 2 字符应被丢弃：" + kws, kws.contains("do"));
    }

    @Test
    public void extractKeywords_chineseProducesSlidingNgrams() {
        List<String> kws = MemoryRelevance.extractKeywords("美式咖啡");
        assertTrue("缺 2 字片段 美式：" + kws, kws.contains("美式"));
        assertTrue("缺 2 字片段 咖啡：" + kws, kws.contains("咖啡"));
        assertTrue("缺 3 字片段 美式咖：" + kws, kws.contains("美式咖"));
        assertTrue("缺整体 美式咖啡：" + kws, kws.contains("美式咖啡"));
    }

    @Test
    public void extractKeywords_mixedChineseEnglish() {
        List<String> kws = MemoryRelevance.extractKeywords("喝美式 coffee");
        assertTrue(kws.contains("coffee"));
        assertTrue(kws.contains("美式"));
    }

    @Test
    public void extractKeywords_nullOrEmptyReturnsEmptyList() {
        assertTrue(MemoryRelevance.extractKeywords(null).isEmpty());
        assertTrue(MemoryRelevance.extractKeywords("").isEmpty());
    }

    // ==================== 命中计数 / 相关度 ====================

    @Test
    public void countHits_countsPresentKeywordsOnly() {
        List<String> kws = java.util.Arrays.asList("美式", "咖啡", "拿铁");
        assertEquals(2, MemoryRelevance.countHits(kws, "喜欢喝美式咖啡"));
        assertEquals(0, MemoryRelevance.countHits(kws, "今天天气不错"));
        assertEquals(0, MemoryRelevance.countHits(java.util.Collections.<String>emptyList(), "任意内容"));
        assertEquals(0, MemoryRelevance.countHits(kws, null));
    }

    @Test
    public void relevance_fullOverlapIsOne() {
        double r = MemoryRelevance.relevance("美式咖啡", "喜欢喝美式咖啡");
        assertEquals(1.0, r, 1e-9);
    }

    @Test
    public void relevance_partialOverlapIsBetweenZeroAndOne() {
        double r = MemoryRelevance.relevance("美式咖啡", "美式");
        assertTrue("应 > 0：" + r, r > 0);
        assertTrue("应 < 1：" + r, r < 1);
    }

    @Test
    public void relevance_noOverlapIsZero() {
        assertEquals(0.0, MemoryRelevance.relevance("美式咖啡", "今天天气不错"), 1e-9);
    }

    @Test
    public void relevance_emptyQueryIsZero() {
        assertEquals(0.0, MemoryRelevance.relevance("", "喜欢喝美式"), 1e-9);
        assertEquals(0.0, MemoryRelevance.relevance(null, "喜欢喝美式"), 1e-9);
    }

    // ==================== matches（核心回归） ====================

    @Test
    public void matches_regression_wholeSentenceContainsWouldFail() {
        // 旧实现：content.contains("我喜欢喝什么咖啡") == false
        assertTrue(MemoryRelevance.matches("我喜欢喝什么咖啡", "喜欢喝美式"));
    }

    @Test
    public void matches_englishIsCaseInsensitive() {
        assertTrue(MemoryRelevance.matches("COFFEE", "I like coffee"));
    }

    @Test
    public void matches_irrelevantContentIsFalse() {
        assertFalse(MemoryRelevance.matches("今天天气怎么样", "喜欢喝美式"));
    }

    @Test
    public void matches_nullContentIsFalse() {
        assertFalse(MemoryRelevance.matches("美式咖啡", null));
        assertFalse(MemoryRelevance.matches(null, "喜欢喝美式"));
    }
}
