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
