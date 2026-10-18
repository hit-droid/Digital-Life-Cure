package com.digitallife.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * MotionCompat 纯逻辑单测（v1.166.0）：跨模型复用兼容性判定的边界。
 *
 * <p>判定是启发式的，这里只锁「命中率 → 结论档位」这条映射，以及空集 / 无法判定两条退路，
 * 防止有人把阈值改乱导致界面文案与实际命中率对不上。</p>
 */
public class MotionCompatTest {

    private static Set<String> set(String... xs) {
        return new HashSet<>(Arrays.asList(xs));
    }

    @Test
    public void noMotionParams_unjudgeable() {
        MotionCompat.Verdict v = MotionCompat.judge(Collections.emptySet(), set("ParamAngleX"));
        assertEquals(0, v.total);
        assertEquals(0, v.matched);
        assertTrue(v.label.contains("没有参数曲线"));
    }

    @Test
    public void nullMotionParams_unjudgeable() {
        MotionCompat.Verdict v = MotionCompat.judge(null, set("ParamAngleX"));
        assertEquals(0, v.total);
        assertTrue(v.label.contains("无从判定"));
    }

    @Test
    public void nullModelParams_cannotJudge() {
        MotionCompat.Verdict v = MotionCompat.judge(set("ParamAngleX"), null);
        assertEquals(1, v.total);
        assertEquals(0, v.matched);
        assertTrue(v.label.contains("无法判定"));
    }

    @Test
    public void fullMatch_directReuse() {
        MotionCompat.Verdict v = MotionCompat.judge(
                set("ParamAngleX", "ParamAngleY", "ParamBodyAngleX"),
                set("ParamAngleX", "ParamAngleY", "ParamBodyAngleX", "ParamAngleZ"));
        assertEquals(3, v.matched);
        assertEquals(3, v.total);
        assertTrue(v.label.contains("可直接复用"));
        assertFalse(v.missing.contains("ParamAngleX"));
    }

    @Test
    public void majorityMatch_slightOffset() {
        // 3/4 = 0.75 → 「轻微错位」档（>=0.5 且 <0.8）
        MotionCompat.Verdict v = MotionCompat.judge(
                set("a", "b", "c", "d"), set("a", "b", "c"));
        assertEquals(3, v.matched);
        assertEquals("可能轻微错位", true, v.label.contains("轻微错位"));
        assertTrue(v.missing.contains("d"));
    }

    @Test
    public void minorityMatch_obviousOffset() {
        // 1/3 ≈ 0.33 → 「明显错位」档（>0 且 <0.5）
        MotionCompat.Verdict v = MotionCompat.judge(set("a", "b", "c"), set("a", "x"));
        assertEquals(1, v.matched);
        assertTrue(v.label.contains("明显错位"));
    }

    @Test
    public void noMatch_likelyIneffective() {
        MotionCompat.Verdict v = MotionCompat.judge(set("a", "b"), set("x", "y"));
        assertEquals(0, v.matched);
        assertTrue(v.label.contains("大概率无效果"));
    }

    @Test
    public void missingListIsCapped() {
        // 缺失参数只给前若干个做示例，避免界面里塞一长串
        MotionCompat.Verdict v = MotionCompat.judge(
                set("a", "b", "c", "d", "e", "f", "g", "h"),
                set("a"));
        assertEquals(1, v.matched);
        assertTrue(v.missing.size() <= 5);
    }
}
