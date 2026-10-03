package com.digitallife.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.digitallife.util.ChatStore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link HistoryBudget} 的边界回归：预算内不动、恰好越界、至少保留 keepRecent 条、
 * 截断说明条数与内容正确。原先内联在 ChatActivity 无法测试。
 */
public class HistoryBudgetTest {

    private static ChatStore.StoredMsg msg(String role, String content, long ts) {
        return new ChatStore.StoredMsg(role, content, null, null, ts);
    }

    private static ChatStore.StoredMsg fill(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) sb.append('x');
        return msg("user", sb.toString(), 0L);
    }

    @Test
    public void trim_nullOrEmptyReturnsAsIs() {
        assertNull(HistoryBudget.trim(null, 100, 6, 1L));
        List<ChatStore.StoredMsg> empty = new ArrayList<>();
        assertSame(empty, HistoryBudget.trim(empty, 100, 6, 1L));
    }

    @Test
    public void trim_withinBudgetReturnsSameInstance() {
        List<ChatStore.StoredMsg> full = Arrays.asList(
                msg("user", "短", 1L), msg("assistant", "也很短", 2L));
        // 总长 1 + 2 = 3，预算 100 → 原样返回（同一实例，不复制）
        assertSame(full, HistoryBudget.trim(full, 100, 6, 1L));
    }

    @Test
    public void trim_exactlyAtBudgetIsNotTrimmed() {
        List<ChatStore.StoredMsg> full = Arrays.asList(fill(30), fill(70));
        // 总长恰好 100 == 预算，不应触发裁剪（判断是 > 预算）
        assertSame(full, HistoryBudget.trim(full, 100, 6, 1L));
    }

    @Test
    public void trim_keepsRecentAndPrependsNoteWithCorrectCount() {
        List<ChatStore.StoredMsg> full = new ArrayList<>();
        for (int i = 1; i <= 5; i++) full.add(fill(100));   // 5 条 × 100 = 500

        List<ChatStore.StoredMsg> kept = HistoryBudget.trim(full, 10, 2, 42L);

        // 至少保留 2 条，再在最前面插 1 条说明 → 共 3 条
        assertEquals(3, kept.size());
        assertEquals("system", kept.get(0).role);
        assertTrue("说明应写明丢弃条数：" + kept.get(0).content,
                kept.get(0).content.contains("已省略更早的 3 条对话"));
        assertEquals(42L, kept.get(0).timestamp);
        // 保留的是最新的两条（m4、m5）
        assertSame(full.get(3), kept.get(1));
        assertSame(full.get(4), kept.get(2));
    }

    @Test
    public void trim_keepsAtLeastKeepRecentEvenIfOversized() {
        List<ChatStore.StoredMsg> full = new ArrayList<>();
        for (int i = 0; i < 4; i++) full.add(fill(1000));

        List<ChatStore.StoredMsg> kept = HistoryBudget.trim(full, 10, 3, 1L);

        // 单条就远超预算，但仍要保底 3 条 + 1 条说明
        assertEquals(4, kept.size());
        assertEquals("system", kept.get(0).role);
    }

    @Test
    public void trim_countsNullContentAsZero() {
        List<ChatStore.StoredMsg> full = Arrays.asList(
                msg("user", null, 1L), msg("user", null, 2L));
        // 全为 null → 总长 0 ≤ 预算 → 原样返回
        assertSame(full, HistoryBudget.trim(full, 10, 2, 1L));
    }

    @Test
    public void trim_defaultOverloadWorks() {
        List<ChatStore.StoredMsg> full = new ArrayList<>();
        for (int i = 0; i < 200; i++) full.add(fill(100));   // 远超默认 6000
        List<ChatStore.StoredMsg> kept = HistoryBudget.trim(full);
        assertTrue("默认预算下应当发生裁剪", kept.size() < full.size());
        assertEquals("system", kept.get(0).role);
    }
}
