package com.digitallife.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;

/**
 * {@link ChatTextOps} 的回归：这些都是从 ChatActivity 里抽出来的纯逻辑，
 * 之前完全没有测试覆盖。
 */
public class ChatTextOpsTest {

    // ==================== stripCollapseHint ====================

    @Test
    public void stripCollapseHint_nullBecomesEmpty() {
        assertEquals("", ChatTextOps.stripCollapseHint(null));
    }

    @Test
    public void stripCollapseHint_removesExpandHint() {
        assertEquals("正文", ChatTextOps.stripCollapseHint("正文\n\n▸ 展开全文"));
    }

    @Test
    public void stripCollapseHint_removesCollapseHint() {
        assertEquals("正文", ChatTextOps.stripCollapseHint("正文\n\n▾ 收起"));
    }

    @Test
    public void stripCollapseHint_removesInterruptMark() {
        assertEquals("正文", ChatTextOps.stripCollapseHint("正文" + ChatTextOps.INTERRUPT_MARK));
    }

    @Test
    public void stripCollapseHint_stripsBothHintsAndMark() {
        String raw = "正文\n\n▸ 展开全文" + ChatTextOps.INTERRUPT_MARK;
        assertEquals("正文", ChatTextOps.stripCollapseHint(raw));
    }

    @Test
    public void stripCollapseHint_keepsPlainTextUntouched() {
        assertEquals("普通文本，没有提示尾巴", ChatTextOps.stripCollapseHint("普通文本，没有提示尾巴"));
    }

    // ==================== prettyJson ====================

    @Test
    public void prettyJson_nullOrEmptyYieldsEmpty() {
        assertEquals("", ChatTextOps.prettyJson(null));
        assertEquals("", ChatTextOps.prettyJson(""));
    }

    @Test
    public void prettyJson_objectIsIndented() {
        // 注意：org.json 对"单键平坦对象"不换行（只补空格），多键/嵌套才缩进
        String out = ChatTextOps.prettyJson("{\"a\":1,\"b\":2}");
        assertTrue("应当缩进换行：" + out, out.contains("\n"));
        assertTrue(out.contains("\"a\""));
        assertTrue(out.contains("\"b\""));
    }

    @Test
    public void prettyJson_arrayIsIndented() {
        String out = ChatTextOps.prettyJson("[1,2]");
        assertTrue(out.contains("\n"));
    }

    @Test
    public void prettyJson_invalidKeepsOriginal() {
        assertEquals("not json", ChatTextOps.prettyJson("not json"));
    }

    // ==================== parseToolName / parseToolArgs ====================

    @Test
    public void parseToolName_extractsFirstName() {
        String json = "[{\"function\":{\"name\":\"web_search\",\"arguments\":\"{}\"}}]";
        assertEquals("web_search", ChatTextOps.parseToolName(json));
    }

    @Test
    public void parseToolName_fallsBackOnGarbage() {
        assertEquals("tool", ChatTextOps.parseToolName("不是 JSON"));
        assertEquals("tool", ChatTextOps.parseToolName("[]"));
        assertEquals("tool", ChatTextOps.parseToolName(null));
    }

    @Test
    public void parseToolArgs_extractsArguments() {
        String json = "[{\"function\":{\"name\":\"t\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}}]";
        assertEquals("{\"q\":\"x\"}", ChatTextOps.parseToolArgs(json));
    }

    @Test
    public void parseToolArgs_returnsNullOnGarbage() {
        assertNull(ChatTextOps.parseToolArgs("不是 JSON"));
        assertNull(ChatTextOps.parseToolArgs(null));
    }

    // ==================== formatDividerTime ====================

    private static long at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    @Test
    public void formatDividerTime_todayShowsHourMinute() {
        long now = at(2026, 6, 15, 12, 0);
        assertEquals("09:30",
                ChatTextOps.formatDividerTime(at(2026, 6, 15, 9, 30), now, Locale.CHINA));
    }

    @Test
    public void formatDividerTime_yesterdayIsPrefixed() {
        long now = at(2026, 6, 15, 12, 0);
        assertEquals("昨天 22:05",
                ChatTextOps.formatDividerTime(at(2026, 6, 14, 22, 5), now, Locale.CHINA));
    }

    @Test
    public void formatDividerTime_sameYearEarlierShowsMonthDay() {
        long now = at(2026, 6, 15, 12, 0);
        assertEquals("3月2日 08:07",
                ChatTextOps.formatDividerTime(at(2026, 3, 2, 8, 7), now, Locale.CHINA));
    }

    @Test
    public void formatDividerTime_previousYearShowsFullDate() {
        long now = at(2026, 6, 15, 12, 0);
        assertEquals("2025/12/31 23:59",
                ChatTextOps.formatDividerTime(at(2025, 12, 31, 23, 59), now, Locale.CHINA));
    }

    // ==================== toMarkdownForCopy（#83）====================

    @Test
    public void toMarkdownForCopy_nullBecomesEmpty() {
        assertEquals("", ChatTextOps.toMarkdownForCopy(null));
    }

    @Test
    public void toMarkdownForCopy_keepsCodeFence() {
        String md = "看这段：\n\n```java\nint x = 1;\n```\n";
        assertEquals(md, ChatTextOps.toMarkdownForCopy(md));
    }

    @Test
    public void toMarkdownForCopy_keepsTableAndLink() {
        String md = "| a | b |\n|---|---|\n| 1 | 2 |\n\n[链接](https://x.com)";
        assertEquals(md, ChatTextOps.toMarkdownForCopy(md));
    }

    @Test
    public void toMarkdownForCopy_stripsCollapseHintAndInterruptMark() {
        String raw = "正文\n\n▸ 展开全文" + ChatTextOps.INTERRUPT_MARK;
        assertEquals("正文", ChatTextOps.toMarkdownForCopy(raw));
    }

    @Test
    public void toMarkdownForCopy_normalizesCrlf() {
        assertEquals("a\nb\nc", ChatTextOps.toMarkdownForCopy("a\r\nb\rc"));
    }

    @Test
    public void toMarkdownForCopy_menuLabelStable() {
        assertEquals("复制为 Markdown", ChatTextOps.MENU_COPY_MARKDOWN);
    }
}
