package com.digitallife.ui.chat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.digitallife.util.ChatStore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link SuggestionEngine} 的回归：历史指纹、冷启动、上下文拼装、模型返回解析。
 * 这四段原先散在 ChatActivity.requestSuggestions 里，含多处截断边界且无测试。
 */
public class SuggestionEngineTest {

    private static ChatStore.StoredMsg msg(String role, String content, long ts) {
        return new ChatStore.StoredMsg(role, content, null, null, ts);
    }

    // ==================== fingerprint ====================

    @Test
    public void fingerprint_nullAndEmptyAreEquivalent() {
        assertEquals("0_0", SuggestionEngine.fingerprint(null));
        assertEquals("0_0", SuggestionEngine.fingerprint(new ArrayList<>()));
    }

    @Test
    public void fingerprint_usesSizeAndLastTimestamp() {
        List<ChatStore.StoredMsg> hist = Arrays.asList(
                msg("user", "a", 100L), msg("assistant", "b", 200L));
        assertEquals("2_200", SuggestionEngine.fingerprint(hist));
    }

    @Test
    public void fingerprint_changesWhenHistoryGrows() {
        List<ChatStore.StoredMsg> before = Arrays.asList(msg("user", "a", 100L));
        List<ChatStore.StoredMsg> after = Arrays.asList(msg("user", "a", 100L),
                msg("assistant", "b", 200L));
        assertTrue(!SuggestionEngine.fingerprint(before)
                .equals(SuggestionEngine.fingerprint(after)));
    }

    // ==================== coldStart ====================

    @Test
    public void coldStart_embedsPetNameInAllThree() {
        String[] out = SuggestionEngine.coldStart("小汐");
        assertEquals(3, out.length);
        for (String s : out) {
            assertTrue("每条都应带桌宠名：" + s, s.contains("小汐"));
        }
    }

    // ==================== buildPrompt ====================

    @Test
    public void buildPrompt_includesPersonaAndRoles() {
        List<ChatStore.StoredMsg> hist = Arrays.asList(
                msg("user", "你好", 1L), msg("assistant", "在呢", 2L));
        String p = SuggestionEngine.buildPrompt("小汐", hist);
        assertTrue(p.contains("小汐"));
        assertTrue(p.contains("user: 你好"));
        assertTrue(p.contains("assistant: 在呢"));
        assertTrue("应要求只输出 JSON", p.contains("只输出 JSON"));
    }

    @Test
    public void buildPrompt_nullRoleFallsBackToUser() {
        List<ChatStore.StoredMsg> hist = Arrays.asList(msg(null, "内容", 1L));
        assertTrue(SuggestionEngine.buildPrompt("小汐", hist).contains("user: 内容"));
    }

    @Test
    public void buildPrompt_nullContentBecomesEmpty() {
        List<ChatStore.StoredMsg> hist = Arrays.asList(msg("user", null, 1L));
        assertTrue(SuggestionEngine.buildPrompt("小汐", hist).contains("user: \n"));
    }

    @Test
    public void buildPrompt_truncatesLongHistoryLine() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) longText.append('字');
        List<ChatStore.StoredMsg> hist = Arrays.asList(
                msg("user", longText.toString(), 1L));

        String p = SuggestionEngine.buildPrompt("小汐", hist);
        String expected = "user: " + longText.substring(0, SuggestionEngine.MAX_HISTORY_CHARS) + "…";
        assertTrue("超长历史应被截断并加省略号", p.contains(expected));
    }

    @Test
    public void buildPrompt_nullHistoryIsTolerated() {
        String p = SuggestionEngine.buildPrompt("小汐", null);
        assertTrue(p.contains("小汐"));
    }

    // ==================== parse ====================

    @Test
    public void parse_plainJsonArray() {
        assertArrayEquals(new String[]{"问题一", "问题二", "问题三"},
                SuggestionEngine.parse("[\"问题一\",\"问题二\",\"问题三\"]"));
    }

    @Test
    public void parse_stripsMarkdownCodeFence() {
        String fenced = "```json\n[\"甲\",\"乙\"]\n```";
        assertArrayEquals(new String[]{"甲", "乙"}, SuggestionEngine.parse(fenced));
    }

    @Test
    public void parse_capsAtMaxItems() {
        String[] out = SuggestionEngine.parse("[\"a\",\"b\",\"c\",\"d\",\"e\"]");
        assertEquals(SuggestionEngine.MAX_ITEMS, out.length);
    }

    @Test
    public void parse_truncatesLongItem() {
        StringBuilder longItem = new StringBuilder();
        for (int i = 0; i < 40; i++) longItem.append('长');
        String[] out = SuggestionEngine.parse("[\"" + longItem + "\"]");
        assertEquals(SuggestionEngine.MAX_ITEM_CHARS, out[0].length());
    }

    @Test
    public void parse_rejectsEmptyAndInvalid() {
        assertNull(SuggestionEngine.parse(null));
        assertNull(SuggestionEngine.parse("[]"));
        assertNull(SuggestionEngine.parse("不是 JSON"));
        assertNull("含空项应整体判为无效", SuggestionEngine.parse("[\"ok\",\"\"]"));
    }
}
