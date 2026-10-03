package com.digitallife.ui.pet;

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
}
