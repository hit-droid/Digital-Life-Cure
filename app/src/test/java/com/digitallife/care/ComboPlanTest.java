package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 连招纯逻辑单测（v1.172.0）。
 *
 * <p>锁三件事：①停顿时长的推导（表情固定、循环封顶、单次按时长+间隙并夹在区间内、未知兜底）；
 * ②按「组+文件 / 表情名」解析步骤，解析不到返回 null（用户删动作 / 改名后步骤应失效而非错播）；
 * ③命名：清洗、忽略大小写查重、默认名递推。</p>
 */
public class ComboPlanTest {

    private static ComboPlan.Candidate motion(String group, String file, int idx, double dur, boolean loop) {
        return new ComboPlan.Candidate(true, group, file, null, idx, dur, loop);
    }

    private static ComboPlan.Candidate expr(String file, String name) {
        return new ComboPlan.Candidate(false, null, file, name, -1, 0, false);
    }

    @Test
    public void dwellMs_expressionIsFixed() {
        assertEquals(ComboPlan.EXPR_STEP_MS, ComboPlan.dwellMs(false, 0, false));
        assertEquals(ComboPlan.EXPR_STEP_MS, ComboPlan.dwellMs(false, 9.9, true));
    }

    @Test
    public void dwellMs_loopIsCappedByDuration() {
        assertEquals(2000, ComboPlan.dwellMs(true, 2.0, true));
        // 超过上限则封顶
        assertEquals(ComboPlan.LOOP_CAP_MS, ComboPlan.dwellMs(true, 8.0, true));
        // 时长未知的循环动作也走上限
        assertEquals(ComboPlan.LOOP_CAP_MS, ComboPlan.dwellMs(true, 0, true));
    }

    @Test
    public void dwellMs_singleUsesDurationPlusGap() {
        // 2.0s + 150ms 间隙
        assertEquals(2150, ComboPlan.dwellMs(true, 2.0, false));
    }

    @Test
    public void dwellMs_singleIsClampedToRange() {
        // 极短：夹到最小
        assertEquals(ComboPlan.MIN_STEP_MS, ComboPlan.dwellMs(true, 0.1, false));
        // 超长：夹到最大
        assertEquals(ComboPlan.MAX_STEP_MS, ComboPlan.dwellMs(true, 10.0, false));
        // 未知：兜底
        assertEquals(ComboPlan.DEFAULT_UNKNOWN_MS, ComboPlan.dwellMs(true, 0, false));
    }

    @Test
    public void resolve_motionMatchesByGroupAndFile() {
        List<ComboPlan.Candidate> cands = new ArrayList<>();
        cands.add(motion("Idle", "idle.motion3.json", 0, 2.0, false));
        cands.add(motion("TapBody", "tap.motion3.json", 3, 1.0, false));

        ComboPlan.Step s = new ComboPlan.Step(
                ComboPlan.KIND_MOTION, "TapBody", "tap.motion3.json", null);
        ComboPlan.Resolved r = ComboPlan.resolve(s, cands);
        assertNotNull(r);
        assertTrue(r.motion);
        assertEquals("TapBody", r.group);
        assertEquals(3, r.index);
    }

    @Test
    public void resolve_motionMissesWhenGroupOrFileChanged() {
        List<ComboPlan.Candidate> cands = new ArrayList<>();
        cands.add(motion("TapBody", "tap.motion3.json", 0, 1.0, false));

        // 组名变了 → 解析不到
        assertNull(ComboPlan.resolve(new ComboPlan.Step(
                ComboPlan.KIND_MOTION, "Other", "tap.motion3.json", null), cands));
        // 文件没了 → 解析不到
        assertNull(ComboPlan.resolve(new ComboPlan.Step(
                ComboPlan.KIND_MOTION, "TapBody", "gone.motion3.json", null), cands));
    }

    @Test
    public void resolve_exprMatchesByName() {
        List<ComboPlan.Candidate> cands = new ArrayList<>();
        cands.add(expr("f01.exp3.json", "F01"));

        ComboPlan.Resolved r = ComboPlan.resolve(
                new ComboPlan.Step(ComboPlan.KIND_EXPR, null, "f01.exp3.json", "F01"), cands);
        assertNotNull(r);
        assertFalse(r.motion);
        assertEquals("F01", r.exprName);
        assertEquals(ComboPlan.EXPR_STEP_MS, r.dwellMs);
    }

    @Test
    public void resolve_exprFallsBackToFileWhenNameBlank() {
        List<ComboPlan.Candidate> cands = new ArrayList<>();
        cands.add(expr("f01.exp3.json", "F01"));

        ComboPlan.Resolved r = ComboPlan.resolve(
                new ComboPlan.Step(ComboPlan.KIND_EXPR, null, "f01.exp3.json", null), cands);
        assertNotNull(r);
        assertEquals("F01", r.exprName);
    }

    @Test
    public void resolveAll_dropsUnresolvableSteps() {
        List<ComboPlan.Candidate> cands = new ArrayList<>();
        cands.add(motion("Idle", "idle.motion3.json", 0, 2.0, false));

        List<ComboPlan.Step> steps = new ArrayList<>();
        steps.add(new ComboPlan.Step(ComboPlan.KIND_MOTION, "Idle", "idle.motion3.json", null));
        steps.add(new ComboPlan.Step(ComboPlan.KIND_MOTION, "Gone", "gone.motion3.json", null));
        steps.add(new ComboPlan.Step(ComboPlan.KIND_EXPR, null, "x.exp3.json", "F99"));

        List<ComboPlan.Resolved> out = ComboPlan.resolveAll(steps, cands);
        assertEquals(1, out.size());
        assertTrue(out.get(0).motion);
    }

    @Test
    public void sanitizeAndNaming() {
        assertEquals("连招A", ComboPlan.sanitizeName("  连招A  "));
        assertEquals("", ComboPlan.sanitizeName(null));

        List<ComboPlan.Combo> combos = new ArrayList<>();
        combos.add(new ComboPlan.Combo("连招1"));
        assertTrue(ComboPlan.hasName(combos, "连招1"));
        assertTrue(ComboPlan.hasName(combos, "  连招1 "));
        assertFalse(ComboPlan.hasName(combos, "连招2"));
        // 空名不算命中
        assertFalse(ComboPlan.hasName(combos, "  "));
        // 默认名递推跳过已存在
        assertEquals("连招2", ComboPlan.suggestName(combos));
    }

    @Test
    public void copyNameAvoidsCollisions() {
        List<ComboPlan.Combo> combos = new ArrayList<>();
        assertEquals("A-副本", ComboPlan.copyName(combos, "A-副本"));
        combos.add(new ComboPlan.Combo("A-副本"));
        assertEquals("A-副本2", ComboPlan.copyName(combos, "A-副本"));
        combos.add(new ComboPlan.Combo("A-副本2"));
        assertEquals("A-副本3", ComboPlan.copyName(combos, "A-副本"));
        // base 为空回落为「连招」
        assertEquals("连招", ComboPlan.copyName(new ArrayList<>(), "  "));
    }
}
