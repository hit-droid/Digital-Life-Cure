package com.digitallife.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Markdown 行级渲染的回归（见 issue #36）。
 *
 * <p>这些都是纯字符串 / 正则逻辑，不需要 Robolectric，本地 JVM 直接能跑。
 * 注意：这里**不要**调 {@code render()} —— unit test 的 android.jar 里所有方法默认抛
 * "not mocked"，碰到 SpannableStringBuilder 就会炸；端到端断言放在跑 Robolectric 的
 * MarkdownRendererTest 里。</p>
 * 改动的是「一行文本属于哪种块」的判定，而这类判定一旦写错，用户看到的就是
 * 漏了项目符号的多级列表、或者顶着井号的字面标题，肉眼很难归因。</p>
 */
public class MarkdownBlockTest {

    private static MarkdownRenderer.BlockLine p(String line) {
        return MarkdownRenderer.parseBlockLine(line);
    }

    // ==================== 标题 ====================

    @Test
    public void heading_withSpaceIsHeading() {
        MarkdownRenderer.BlockLine b = p("# 标题");
        assertEquals(MarkdownRenderer.BlockKind.HEADING, b.kind);
        assertEquals(1, b.level);
        assertEquals("标题", b.text);
    }

    @Test
    public void heading_withoutSpaceIsHeading() {
        // 模型写中文标题时常写成 #标题；以前整行会被当成普通文本、连井号一起吐出去
        MarkdownRenderer.BlockLine b = p("#标题");
        assertEquals(MarkdownRenderer.BlockKind.HEADING, b.kind);
        assertEquals(1, b.level);
        assertEquals("标题", b.text);
    }

    @Test
    public void heading_supportsUpToThreeLevels() {
        assertEquals(2, p("## 二级").level);
        assertEquals(3, p("### 三级").level);
    }

    @Test
    public void heading_fourHashesStaysPlain() {
        // 放宽「#」后空格后，#### 的前三个井号容易被吃掉、第四个漏进正文，必须挡住
        assertEquals(MarkdownRenderer.BlockKind.PLAIN, p("#### 四级").kind);
        assertEquals("#### 四级", p("#### 四级").text);
    }

    @Test
    public void heading_loneHashIsPlain() {
        assertEquals(MarkdownRenderer.BlockKind.PLAIN, p("#").kind);
        assertEquals(MarkdownRenderer.BlockKind.PLAIN, p("###").kind);
    }

    @Test
    public void heading_keepsInlineMarkersForLaterStyling() {
        // 行内标记的渲染在 parseBlockLine 之后，这里不能提前吞掉
        assertEquals("**粗**标题", p("# **粗**标题").text);
    }

    // ==================== 无序列表 ====================

    @Test
    public void ul_topLevelHasNoIndent() {
        MarkdownRenderer.BlockLine b = p("- 一级");
        assertEquals(MarkdownRenderer.BlockKind.UL, b.kind);
        assertEquals(0, b.level);
        assertEquals("", b.indent);
        assertEquals("一级", b.text);
    }

    @Test
    public void ul_indentedSubItemKeepsLevel() {
        MarkdownRenderer.BlockLine b = p("  - 二级");
        assertEquals(MarkdownRenderer.BlockKind.UL, b.kind);
        assertEquals(1, b.level);
        assertEquals("二级", b.text);
    }

    @Test
    public void ul_indentUsesNonBreakingSpace() {
        // 普通空格在行首会被 TextView 吃掉，层级照样看不见
        assertEquals("\u00A0\u00A0", p("  - 二级").indent);
    }

    @Test
    public void ul_starMarkerAlsoWorks() {
        MarkdownRenderer.BlockLine b = p("  * 二级");
        assertEquals(MarkdownRenderer.BlockKind.UL, b.kind);
        assertEquals("二级", b.text);
    }

    @Test
    public void ul_deepIndentIsCapped() {
        // 不限幅的话深层嵌套会把正文一路推到屏外
        assertEquals(MarkdownRenderer.MAX_LIST_LEVEL, p("          - 很深的层级").level);
    }

    @Test
    public void indentLevel_tabCountsAsOneLevel() {
        assertEquals(1, MarkdownRenderer.indentLevel("\t"));
        assertEquals(0, MarkdownRenderer.indentLevel(""));
        assertEquals(0, MarkdownRenderer.indentLevel(null));
    }

    @Test
    public void indentOf_capsAtMaxListLevel() {
        int capped = MarkdownRenderer.indentOf(MarkdownRenderer.MAX_LIST_LEVEL).length();
        assertEquals(capped, MarkdownRenderer.indentOf(MarkdownRenderer.MAX_LIST_LEVEL + 5).length());
    }

    // ==================== 有序列表 ====================

    @Test
    public void ol_keepsOriginalNumber() {
        MarkdownRenderer.BlockLine b = p("  3. 第三步");
        assertEquals(MarkdownRenderer.BlockKind.OL, b.kind);
        assertEquals("3", b.marker);
        assertEquals("第三步", b.text);
        assertEquals(1, b.level);
    }

    @Test
    public void ol_decimalNumberStaysPlain() {
        // 放宽 OL 判定最危险的副作用：小数不能被当成有序列表渲染成「3. 14 是圆周率」
        assertEquals(MarkdownRenderer.BlockKind.PLAIN, p("3.14 是圆周率").kind);
    }

    @Test
    public void ol_chineseDot() {
        MarkdownRenderer.BlockLine b = p("1、第一");
        assertEquals(MarkdownRenderer.BlockKind.OL, b.kind);
        assertEquals("1", b.marker);
        assertEquals("第一", b.text);
    }

    // ==================== 引用 ====================

    @Test
    public void quote_withIndent() {
        MarkdownRenderer.BlockLine b = p("  > 引用一句");
        assertEquals(MarkdownRenderer.BlockKind.QUOTE, b.kind);
        assertEquals("引用一句", b.text);
        assertEquals(1, b.level);
    }

    // ==================== 不该被误判的 ====================

    @Test
    public void plain_textStaysPlain() {
        MarkdownRenderer.BlockLine b = p("普通一句话");
        assertEquals(MarkdownRenderer.BlockKind.PLAIN, b.kind);
        assertEquals("普通一句话", b.text);
    }

    @Test
    public void plain_horizontalRuleIsNotListItem() {
        assertEquals(MarkdownRenderer.BlockKind.PLAIN, p("---").kind);
    }

    @Test
    public void plain_singleDashIsNotListItem() {
        assertEquals(MarkdownRenderer.BlockKind.PLAIN, p("-").kind);
    }

    @Test
    public void plain_nullBecomesEmpty() {
        MarkdownRenderer.BlockLine b = p(null);
        assertEquals(MarkdownRenderer.BlockKind.PLAIN, b.kind);
        assertEquals("", b.text);
    }

    // ==================== truncateCell：不能劈开 emoji ====================

    @Test
    public void truncate_shortCellUnchanged() {
        assertEquals("abc", MarkdownRenderer.truncateCell("abc", 10));
    }

    @Test
    public void truncate_appendsEllipsis() {
        assertTrue(MarkdownRenderer.truncateCell("abcdefghij", 4).endsWith("\u2026"));
    }

    @Test
    public void truncate_countsCjkAsTwoColumns() {
        assertEquals("中文\u2026", MarkdownRenderer.truncateCell("中文测试", 5));
    }

    @Test
    public void truncate_neverSplitsEmojiPair() {
        String src = "ab\uD83D\uDE00cd";
        for (int w = 2; w <= 6; w++) {
            String out = MarkdownRenderer.truncateCell(src, w);
            assertFalse("width=" + w + " 时留下了半个 emoji：" + escape(out),
                    hasUnpairedSurrogate(out));
        }
    }

    @Test
    public void truncate_keepsWholeEmojiWhenItFits() {
        String out = MarkdownRenderer.truncateCell("ab\uD83D\uDE00cd", 6);
        assertTrue("放得下就必须完整保留：" + escape(out), out.contains("\uD83D\uDE00"));
    }

    @Test
    public void truncate_tinyWidthGivesEllipsisOnly() {
        assertEquals("\u2026", MarkdownRenderer.truncateCell("任意内容", 1));
    }

    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return true;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            sb.append("\\u").append(String.format("%04X", (int) s.charAt(i)));
        }
        return sb.toString();
    }
}
