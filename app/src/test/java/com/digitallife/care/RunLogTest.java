package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 执行记录纯逻辑单测（v1.173.0）：锁成败判定、结果摘要、人话时间三个契约。
 * 纯 JVM，不依赖 Android（{@link RunLog} 的存储部分另有 {@code RunLogStoreTest} 走 Robolectric）。
 */
public class RunLogTest {

    @Test
    public void looksOk_successResultIsOk() {
        String ok = "▶ 执行工作流「晨报」:\n  步骤 1: get_weather → ✅ 成功\n天气晴\n";
        assertTrue(RunLog.looksOk(ok));
    }

    @Test
    public void looksOk_failureMarkersAreNotOk() {
        assertFalse(RunLog.looksOk("▶ 执行工作流「x」:\n  步骤 1: a → ❌ 失败: boom\n"));
        assertFalse(RunLog.looksOk("执行失败: null"));
        assertFalse(RunLog.looksOk("❌ 工具执行异常: oops"));
        assertFalse(RunLog.looksOk("未找到工作流: x"));
        assertFalse(RunLog.looksOk("工作流「x」没有步骤。"));
        assertFalse(RunLog.looksOk(null));
    }

    /**
     * v1.191.0：步骤**正文**里出现「失败 / 异常 / 未找到」等字样时，
     * 整条运行不应被误判为失败（旧实现全文扫关键词 → 假阴性）。
     */
    @Test
    public void looksOk_stepBodyWithFailureWordsStillOk() {
        String ok = "▶ 执行工作流「晨报」:\n"
                + "  步骤 1: read_file → ✅ 成功\n"
                + "上次失败记录：0 条，异常项：无，未找到文件：0\n";
        assertTrue("步骤正文含失败字样不应误判为失败", RunLog.looksOk(ok));
    }

    @Test
    public void looksOk_anyStepFailedMeansNotOk() {
        String mixed = "▶ 执行工作流「x」:\n"
                + "  步骤 1: a → ✅ 成功\n"
                + "  步骤 2: b → ❌ 失败: boom\n";
        assertFalse(RunLog.looksOk(mixed));
    }

    @Test
    public void looksOk_noStepLinesFallsBackToKeywordScan() {
        // 没有步骤行 → 退回关键词判定（整体失败提示）
        assertFalse(RunLog.looksOk("未找到工作流「x」"));
        assertFalse(RunLog.looksOk("请指定工作流名称"));
        assertTrue(RunLog.looksOk("已执行完成"));
    }

    // ==================== merge ====================

    @Test
    public void merge_sortedByTimeDesc() {
        RunLog.Record a = new RunLog.Record(1000, true, "a");
        RunLog.Record b = new RunLog.Record(3000, false, "b");
        RunLog.Record c = new RunLog.Record(2000, true, "c");
        java.util.List<RunLog.Record> out =
                RunLog.merge(java.util.Arrays.asList(a, b), java.util.Collections.singletonList(c));
        assertEquals(3, out.size());
        assertEquals("b", out.get(0).summary);
        assertEquals("c", out.get(1).summary);
        assertEquals("a", out.get(2).summary);
    }

    @Test
    public void merge_capsToMaxKept() {
        java.util.List<RunLog.Record> first = new java.util.ArrayList<>();
        for (int i = 0; i < RunLog.MAX_KEPT + 5; i++) {
            first.add(new RunLog.Record(i, true, "s" + i));
        }
        java.util.List<RunLog.Record> out =
                RunLog.merge(first, java.util.Collections.<RunLog.Record>emptyList());
        assertEquals(RunLog.MAX_KEPT, out.size());
        assertEquals("s" + (RunLog.MAX_KEPT + 4), out.get(0).summary);
    }

    @Test
    public void merge_nullsAreTolerated() {
        assertEquals(0, RunLog.merge(null, null).size());
        assertEquals(1, RunLog.merge(null,
                java.util.Collections.singletonList(new RunLog.Record(1, true, "x"))).size());
    }

    @Test
    public void summarize_foldsWhitespaceAndTruncates() {
        assertEquals("a b c", RunLog.summarize("a\n  b\tc "));
        assertEquals("", RunLog.summarize(null));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append('x');
        String s = RunLog.summarize(sb.toString());
        assertEquals(141, s.length()); // 140 + 省略号
        assertTrue(s.endsWith("…"));
    }

    @Test
    public void whenText_buckets() {
        long now = 1_700_000_000_000L;
        assertEquals("刚刚", RunLog.whenText(now, now));
        assertEquals("刚刚", RunLog.whenText(now - 59_000, now));
        assertEquals("1 分钟前", RunLog.whenText(now - 60_000, now));
        assertEquals("1 小时前", RunLog.whenText(now - 3_600_000, now));
        assertEquals("1 天前", RunLog.whenText(now - 86_400_000L, now));
        // 超过一周回落到绝对时间
        assertTrue(RunLog.whenText(now - 10 * 86_400_000L, now).matches("\\d+月\\d+日 \\d{2}:\\d{2}"));
        // 未来时间收敛为「刚刚」，不出现负数
        assertEquals("刚刚", RunLog.whenText(now + 5_000, now));
    }
}
