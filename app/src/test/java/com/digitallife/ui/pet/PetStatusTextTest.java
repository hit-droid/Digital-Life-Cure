package com.digitallife.ui.pet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link PetStatusText} 的回归（Issue #27 第 1 条：桌宠状态胶囊）。
 *
 * 纯逻辑、不依赖 Android，所以跑在普通 JVM 上就够，不必上 Robolectric。
 */
public class PetStatusTextTest {

    // ==================== 情绪标签 ====================

    @Test
    public void dominantLabel_mapsEachDimension() {
        assertEquals("开心", PetStatusText.dominantLabel("happy"));
        assertEquals("难过", PetStatusText.dominantLabel("sad"));
        assertEquals("生气", PetStatusText.dominantLabel("angry"));
        assertEquals("惊讶", PetStatusText.dominantLabel("surprised"));
        assertEquals("害羞", PetStatusText.dominantLabel("shy"));
        assertEquals("平静", PetStatusText.dominantLabel("calm"));
    }

    @Test
    public void dominantLabel_fallsBackToCalm() {
        // EmotionState#dominant() 在全零时返回 "neutral"，未知维度也不能渲染成空
        assertEquals("平静", PetStatusText.dominantLabel("neutral"));
        assertEquals("平静", PetStatusText.dominantLabel(null));
        assertEquals("平静", PetStatusText.dominantLabel(""));
        assertEquals("平静", PetStatusText.dominantLabel("whatever"));
    }

    // ==================== 百分比 ====================

    @Test
    public void pct_convertsFractionToPercent() {
        assertEquals(0, PetStatusText.pct(0f));
        assertEquals(50, PetStatusText.pct(0.5f));
        assertEquals(100, PetStatusText.pct(1f));
        assertEquals(62, PetStatusText.pct(0.623f));
    }

    @Test
    public void pct_clampsOutOfRange() {
        // 亲密度/精力外部会做夹取，但这里再兜一层：越界值不能渲染成 120% 或 -5%
        assertEquals(0, PetStatusText.pct(-1f));
        assertEquals(100, PetStatusText.pct(2f));
        assertEquals(0, PetStatusText.pct(Float.NaN));
    }

    // ==================== 胶囊文案 ====================

    @Test
    public void capsule_showsIntimacyEnergyAndMood() {
        String s = PetStatusText.capsule(0.62f, 0.71f, "happy");
        assertEquals("亲密度 62% · 精力 71% · 开心", s);
    }

    @Test
    public void capsule_neverEmpty() {
        // 胶囊不能出现空文本——空文本时视图会隐藏，等于状态面板白做
        String s = PetStatusText.capsule(0f, 0f, null);
        assertFalse(s.isEmpty());
        assertTrue(s.contains("亲密度"));
        assertTrue(s.contains("精力"));
    }

    @Test
    public void capsule_clampsGarbageInput() {
        String s = PetStatusText.capsule(9f, -3f, "zzz");
        assertTrue(s.contains("100%"));
        assertTrue(s.contains("0%"));
        assertTrue(s.endsWith("平静"));
    }

    // ==================== v1.136.0：点开详情 ====================

    @Test
    public void dims_matchBrainOrder() {
        // 详情面板的行顺序必须与 brain/EmotionState.DIMS 一致，否则同一条状态两处排序不同
        assertArrayEquals(new String[]{"happy", "sad", "angry", "surprised", "shy", "calm"},
                PetStatusText.DIMS);
    }

    @Test
    public void detailTitle_usesPetName() {
        assertEquals("小汐的状态", PetStatusText.detailTitle("小汐"));
        // 前后空格要清掉，否则标题里会多出空隙
        assertEquals("小汐的状态", PetStatusText.detailTitle("  小汐  "));
    }

    @Test
    public void detailTitle_fallsBackWhenNameMissing() {
        assertEquals("我的状态", PetStatusText.detailTitle(null));
        assertEquals("我的状态", PetStatusText.detailTitle(""));
        assertEquals("我的状态", PetStatusText.detailTitle("   "));
    }

    @Test
    public void detailTitle_truncatesLongName() {
        // 超长名字不能顶破卡片：截到 8 字
        String t = PetStatusText.detailTitle("一二三四五六七八九十");
        assertEquals("一二三四五六七八的状态", t);
    }

    @Test
    public void detailSubtitle_showsPctAndBond() {
        assertEquals("亲密度 62% · 亲密", PetStatusText.detailSubtitle(0.62f));
    }

    @Test
    public void bondLevel_steppsEvery20Percent() {
        assertEquals("陌生", PetStatusText.bondLevel(0f));
        assertEquals("陌生", PetStatusText.bondLevel(0.19f));
        assertEquals("眼熟", PetStatusText.bondLevel(0.2f));
        assertEquals("朋友", PetStatusText.bondLevel(0.4f));
        assertEquals("朋友", PetStatusText.bondLevel(0.59f));
        assertEquals("亲密", PetStatusText.bondLevel(0.6f));
        assertEquals("挚友", PetStatusText.bondLevel(0.8f));
        assertEquals("挚友", PetStatusText.bondLevel(1f));
    }

    @Test
    public void bondLevel_clampsGarbage() {
        // NaN / 越界不能渲染出空关系等级
        assertEquals("陌生", PetStatusText.bondLevel(Float.NaN));
        assertEquals("陌生", PetStatusText.bondLevel(-1f));
        assertEquals("挚友", PetStatusText.bondLevel(5f));
    }

    @Test
    public void barWidth_mapsFractionToPixels() {
        assertEquals(0, PetStatusText.barWidth(0f, 100));
        assertEquals(50, PetStatusText.barWidth(0.5f, 100));
        assertEquals(100, PetStatusText.barWidth(1f, 100));
        assertEquals(62, PetStatusText.barWidth(0.62f, 100));
    }

    @Test
    public void barWidth_neverExceedsMaxOrGoesNegative() {
        // 进度条画宽了顶出卡片；画成负数在 View 上直接抛异常，两者都必须挡住
        assertEquals(0, PetStatusText.barWidth(-1f, 100));
        assertEquals(100, PetStatusText.barWidth(2f, 100));
        assertEquals(0, PetStatusText.barWidth(Float.NaN, 100));
        assertEquals(0, PetStatusText.barWidth(0.5f, 0));
        assertEquals(0, PetStatusText.barWidth(0.5f, -10));
        for (int i = 0; i <= 100; i++) {
            int w = PetStatusText.barWidth(i / 100f, 120);
            assertTrue(w >= 0 && w <= 120);
        }
    }

    @Test
    public void rowValue_matchesPct() {
        assertEquals("62%", PetStatusText.rowValue(0.62f));
        assertEquals("0%", PetStatusText.rowValue(Float.NaN));
        assertEquals("100%", PetStatusText.rowValue(3f));
    }
}
