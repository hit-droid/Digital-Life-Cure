package com.digitallife.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.digitallife.util.ChatStore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * v1.147.0（#82）：{@link ConversationOrder#sort} 的回归。
 * 覆盖：无置顶 / 单条与多条置顶 / 护理会话恒第一 / 组内按更新时间降序 / 旧数据（pinned=false）兼容。
 */
public class ConversationOrderTest {

    private static final String CARE = "care";

    private static ChatStore.SessionInfo s(String id, String title, boolean pinned, long updatedAt) {
        return new ChatStore.SessionInfo(id, title, ChatStore.TYPE_CHAT, "chat", null,
                0L, updatedAt, pinned);
    }

    private static List<String> ids(List<ChatStore.SessionInfo> list) {
        List<String> out = new ArrayList<>();
        for (ChatStore.SessionInfo x : list) out.add(x.id);
        return out;
    }

    @Test
    public void sort_nullSafe() {
        assertNull(ConversationOrder.sort(null, CARE));
    }

    @Test
    public void sort_noPinned_ordersByUpdatedDesc() {
        List<ChatStore.SessionInfo> list = Arrays.asList(
                s("a", "A", false, 100L),
                s("b", "B", false, 300L),
                s("c", "C", false, 200L));
        ConversationOrder.sort(list, CARE);
        assertEquals(Arrays.asList("b", "c", "a"), ids(list));
    }

    @Test
    public void sort_pinnedComesBeforeUnpinned() {
        List<ChatStore.SessionInfo> list = Arrays.asList(
                s("a", "A", false, 900L),   // 最新但未置顶
                s("b", "B", true, 100L));   // 较旧但置顶
        ConversationOrder.sort(list, CARE);
        assertEquals(Arrays.asList("b", "a"), ids(list));
    }

    @Test
    public void sort_multiplePinned_orderedByUpdatedWithinGroup() {
        List<ChatStore.SessionInfo> list = Arrays.asList(
                s("a", "A", true, 100L),
                s("b", "B", true, 400L),
                s("c", "C", false, 500L),
                s("d", "D", false, 200L));
        ConversationOrder.sort(list, CARE);
        // 置顶组按更新时间降序：b(400) > a(100)；未置顶组：c(500) > d(200)
        assertEquals(Arrays.asList("b", "a", "c", "d"), ids(list));
    }

    @Test
    public void sort_careAlwaysFirst_evenWhenUnpinnedAndOld() {
        List<ChatStore.SessionInfo> list = Arrays.asList(
                s("a", "A", true, 900L),
                s(CARE, "护理大脑", false, 1L),
                s("b", "B", true, 800L));
        ConversationOrder.sort(list, CARE);
        assertEquals(CARE, list.get(0).id);
    }

    @Test
    public void sort_careFirst_evenWhenCareIsPinned() {
        List<ChatStore.SessionInfo> list = Arrays.asList(
                s("a", "A", true, 900L),
                s(CARE, "护理大脑", true, 1L));
        ConversationOrder.sort(list, CARE);
        assertEquals(CARE, list.get(0).id);
    }

    @Test
    public void sort_legacyData_allUnpinned_isStableOrderingByTime() {
        // 旧库升级后 pinned 全为 false，等价于「按更新时间降序」
        List<ChatStore.SessionInfo> list = Arrays.asList(
                s("a", "A", false, 10L),
                s("b", "B", false, 30L),
                s("c", "C", false, 20L));
        ConversationOrder.sort(list, CARE);
        assertEquals(Arrays.asList("b", "c", "a"), ids(list));
    }

    @Test
    public void sort_sameTimestamp_breaksTieById() {
        List<ChatStore.SessionInfo> list = Arrays.asList(
                s("b", "B", false, 100L),
                s("a", "A", false, 100L));
        ConversationOrder.sort(list, CARE);
        assertEquals(Arrays.asList("a", "b"), ids(list));
    }

    @Test
    public void legacyConstructor_defaultsToUnpinned() {
        // SessionInfo 旧构造器（无 pinned）默认未置顶，保证既有调用方行为不变
        ChatStore.SessionInfo old = new ChatStore.SessionInfo(
                "x", "X", ChatStore.TYPE_CHAT, "chat", null, 0L, 1L);
        assertTrue(!old.pinned);
    }
}
