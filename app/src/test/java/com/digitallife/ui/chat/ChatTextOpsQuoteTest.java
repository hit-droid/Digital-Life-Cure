package com.digitallife.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ChatTextOps#buildQuote} 的回归——引用回复的纯文本拼装。
 *
 * 背景（AGENTS.md 5.4 第 2 条）：消息长按菜单原先只有「复制 / 重新生成 / 删除」，
 * 没有引用回复。引用块的生成是纯逻辑，单独抽出来测，
 * Activity 一侧只负责把结果塞进输入框。
 */
public class ChatTextOpsQuoteTest {

    // ==================== 空输入 ====================

    @Test
    public void buildQuote_nullBecomesEmpty() {
        assertEquals("", ChatTextOps.buildQuote(null));
    }

    @Test
    public void buildQuote_blankBecomesEmpty() {
        assertEquals("", ChatTextOps.buildQuote("   "));
    }

    // ==================== 基本形态 ====================

    @Test
    public void buildQuote_singleLineHasPrefix() {
        assertEquals("> 你好\n", ChatTextOps.buildQuote("你好"));
    }

    @Test
    public void buildQuote_multiLineEachPrefixed() {
        assertEquals("> 第一行\n> 第二行\n", ChatTextOps.buildQuote("第一行\n第二行"));
    }

    @Test
    public void buildQuote_alwaysEndsWithNewline() {
        // 结尾必须换行，否则光标停在引用行上，用户接着输入会粘在同一行
        assertTrue(ChatTextOps.buildQuote("abc").endsWith("\n"));
    }

    // ==================== 截断 ====================

    @Test
    public void buildQuote_truncatesBeyondMaxLines() {
        String src = "l1\nl2\nl3\nl4\nl5\nl6";
        assertEquals("> l1\n> l2\n> …\n", ChatTextOps.buildQuote(src, 2));
    }

    @Test
    public void buildQuote_noEllipsisWhenWithinLimit() {
        String src = "l1\nl2";
        assertEquals("> l1\n> l2\n", ChatTextOps.buildQuote(src, 4));
    }

    @Test
    public void buildQuote_nonPositiveMaxFallsBackToDefault() {
        String src = "l1\nl2\nl3\nl4\nl5\nl6";
        // maxLines=0 视作默认 4 行
        assertEquals(ChatTextOps.buildQuote(src, ChatTextOps.QUOTE_MAX_LINES),
                ChatTextOps.buildQuote(src, 0));
    }

    @Test
    public void buildQuote_defaultLimitIsFourLines() {
        String src = "l1\nl2\nl3\nl4\nl5";
        assertEquals("> l1\n> l2\n> l3\n> l4\n> …\n", ChatTextOps.buildQuote(src));
    }

    // ==================== 折叠提示不应被引用进去 ====================

    @Test
    public void buildQuote_stripsCollapseHint() {
        String src = "正文内容\n\n▸ 展开全文";
        String q = ChatTextOps.buildQuote(src);
        assertTrue("引用里不应带折叠提示：" + q, !q.contains("展开全文"));
        assertTrue(q.contains("正文内容"));
    }
}
