package com.digitallife.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.Spanned;
import android.text.style.URLSpan;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 表格单元格行内 Markdown 的回归（AGENTS.md 5.4 第 5 条）。
 *
 * 背景：表格是等宽对齐的纯文本渲染，单元格里的 {@code **加粗**} 之类标记
 * 既渲染不出效果，又会按字面占列宽把对齐撑歪。因此在算列宽之前先剥掉标记。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MarkdownRendererTest {

    /** 颜色参数对纯文本断言无影响，给一组固定的占位值即可 */
    private static MarkdownRenderer renderer() {
        return new MarkdownRenderer(
                0xFF222222, // codeBgColor
                0xFFDDDDDD, // codeTextColor
                0xFF888888, // quoteTextColor
                0xFF000000, // baseTextColor
                0xFF3366CC, // linkColor
                0xFF3A3A3A  // dividerColor
        );
    }

    // ==================== stripInline ====================

    @Test
    public void stripInline_removesBoldMarker() {
        assertEquals("加粗", MarkdownRenderer.stripInline("**加粗**"));
    }

    @Test
    public void stripInline_removesItalicMarker() {
        assertEquals("斜体", MarkdownRenderer.stripInline("*斜体*"));
    }

    @Test
    public void stripInline_removesInlineCodeMarker() {
        assertEquals("code", MarkdownRenderer.stripInline("`code`"));
    }

    @Test
    public void stripInline_removesStrikethrough() {
        assertEquals("删除", MarkdownRenderer.stripInline("~~删除~~"));
    }

    @Test
    public void stripInline_linkKeepsTextOnly() {
        assertEquals("点我", MarkdownRenderer.stripInline("[点我](https://example.com)"));
    }

    @Test
    public void stripInline_nullBecomesEmpty() {
        assertEquals("", MarkdownRenderer.stripInline(null));
    }

    @Test
    public void stripInline_plainTextUnchanged() {
        assertEquals("普通文本", MarkdownRenderer.stripInline("普通文本"));
    }

    @Test
    public void stripInline_mixedMarkers() {
        assertEquals("A 和 B", MarkdownRenderer.stripInline("**A** 和 `B`"));
    }

    /** 单星号必须成对识别，不能把 "2 * 3 * 4" 这类文本吃掉 */
    @Test
    public void stripInline_doesNotEatUnpairedAsterisk() {
        assertEquals("2 * 3", MarkdownRenderer.stripInline("2 * 3"));
    }

    // ==================== 端到端：表格渲染 ====================

    @Test
    public void render_tableCellHasNoBoldMarker() {
        String md = "| 名称 | 说明 |\n|---|---|\n| **小汐** | 数字生命助手 |\n";
        String out = renderer().render(md).toString();
        assertFalse("单元格不应残留 ** 标记，实际输出：" + out, out.contains("**"));
        assertTrue("应保留标记内的文字，实际输出：" + out, out.contains("小汐"));
    }

    @Test
    public void render_tableCellHasNoCodeMarker() {
        String md = "| 命令 | 作用 |\n|---|---|\n| `ls` | 列目录 |\n";
        String out = renderer().render(md).toString();
        assertFalse("单元格不应残留反引号，实际输出：" + out, out.contains("`"));
        assertTrue(out.contains("ls"));
    }

    /** 标记被剥掉后再算列宽，两行才能对齐（列起始偏移一致） */
    @Test
    public void render_tableColumnsAlignAfterStripping() {
        String md = "| a | b |\n|---|---|\n| **x** | y |\n| `z` | w |\n";
        String out = renderer().render(md).toString();
        String[] lines = out.split("\n");
        assertTrue("应有表头与两行数据，实际：" + out, lines.length >= 3);
        assertFalse(out.contains("**"));
        assertFalse(out.contains("`"));
        // 第 2、3 行数据列起始位置应一致（列宽由剥掉标记后的内容决定）
        int c0 = lines[1].indexOf('x');
        int c1 = lines[2].indexOf('z');
        assertTrue("两行首列起始位置应一致：" + c0 + " vs " + c1, c0 >= 0 && c0 == c1);
    }

    // ==================== v1.130.0：整表宽度受控 + 不折行 ====================

    private static int sum(int[] a) {
        int s = 0;
        for (int v : a) s += v;
        return s;
    }

    @Test
    public void fitColumns_withinBudgetIsUnchanged() {
        int[] in = {5, 5};
        int[] out = MarkdownRenderer.fitColumns(in, 20);
        assertEquals(5, out[0]);
        assertEquals(5, out[1]);
    }

    @Test
    public void fitColumns_scalesProportionally() {
        int[] out = MarkdownRenderer.fitColumns(new int[]{10, 30}, 20);
        assertEquals(2, out.length);
        // 10:30 等比缩到总宽 20 → 5:15
        assertEquals(5, out[0]);
        assertEquals(15, out[1]);
    }

    @Test
    public void fitColumns_totalStaysWithinBudget() {
        int[] out = MarkdownRenderer.fitColumns(new int[]{24, 24, 24}, 30);
        assertTrue("压缩后总宽应 <= 预算，实际 " + sum(out), sum(out) <= 30);
    }

    @Test
    public void fitColumns_neverGoesBelowMinWidth() {
        // 预算小到连下限都塞不下时，宁可溢出也不把列压成 0（内容会整列消失）
        int[] out = MarkdownRenderer.fitColumns(new int[]{24, 24, 24, 24, 24}, 4);
        for (int w : out) {
            assertTrue("单列不应低于下限，实际 " + w, w >= MarkdownRenderer.MIN_COL_WIDTH);
        }
    }

    @Test
    public void fitColumns_nullGivesEmpty() {
        assertEquals(0, MarkdownRenderer.fitColumns(null, 10).length);
    }

    @Test
    public void noBreakSpaces_replacesOrdinarySpaces() {
        assertEquals("a\u00A0b", MarkdownRenderer.noBreakSpaces("a b"));
        assertEquals("", MarkdownRenderer.noBreakSpaces(""));
        assertEquals("", MarkdownRenderer.noBreakSpaces(null));
    }

    @Test
    public void render_tableRowsFitWithinMaxWidth() {
        // 三列各 24 宽：不压缩的话一行 72+4=76 个字符，手机上必然被折行
        String md = "| aaaaaaaaaaaaaaaaaaaaaaaa | bbbbbbbbbbbbbbbbbbbbbbbb | cccccccccccccccccccccccc |\n"
                + "|---|---|---|\n| 1 | 2 | 3 |\n";
        String out = renderer().render(md).toString();
        for (String line : out.split("\n")) {
            assertTrue("表格行宽应受控（<= " + MarkdownRenderer.MAX_TABLE_WIDTH + "），实际 "
                    + line.length() + "：" + line, line.length() <= MarkdownRenderer.MAX_TABLE_WIDTH);
        }
    }

    @Test
    public void render_tableHasNoBreakableSpace() {
        // 列间空隙必须是不换行空格：普通空格是 TextView 的合法折行点，
        // 宽表格会从这里被折断，对齐全乱。
        String out = renderer().render("| a | b |\n|---|---|\n| 1 | 2 |\n").toString();
        assertFalse("表格里不应残留可折行的普通空格：" + out, out.contains(" "));
        assertTrue(out.contains("\u00A0"));
    }

    // ==================== v1.136.0：行级判定的端到端（需要 Robolectric） ====================

    /**
     * 这三条同一个断言在 MarkdownBlockTest 里跑不了：那里刻意不带 Robolectric，
     * 而拼 Spannable 会撞上 unit test 的默认 "not mocked" 异常。
     */
    @Test
    public void render_nestedListKeepsBulletAndIndent() {
        String out = renderer().render("- 一级\n  - 二级\n").toString();
        assertTrue("一级应有项目符号：" + out, out.contains("\u2022 一级"));
        assertTrue("子项应有项目符号且带缩进：" + out, out.contains("\u00A0\u00A0\u2022 二级"));
    }

    @Test
    public void render_headingWithoutSpaceDropsHash() {
        String out = renderer().render("#标题\n").toString();
        assertFalse("不应残留井号：" + out, out.contains("#"));
        assertTrue(out.contains("标题"));
    }

    @Test
    public void render_decimalNumberUnaffected() {
        String out = renderer().render("3.14 是圆周率\n").toString();
        assertTrue("小数不能被拆成列表项：" + out, out.contains("3.14 是圆周率"));
    }

    // ============ v1.141.0（#44）：行内链接 / 任务列表 / 水平线 ============

    /** 取第一个 URLSpan 的 url；没有则返回 null */
    private static String urlSpanUrl(CharSequence cs) {
        if (!(cs instanceof Spanned)) return null;
        URLSpan[] spans = ((Spanned) cs).getSpans(0, cs.length(), URLSpan.class);
        return spans.length == 0 ? null : spans[0].getURL();
    }

    @Test
    public void render_inlineLink_showsLabelOnlyAndCarriesUrl() {
        CharSequence cs = renderer().render("见 [官网](https://example.com) 了解\n");
        String out = cs.toString();
        assertFalse("不应再吐出链接语法：" + out, out.contains("]("));
        assertTrue("只应显示文字：" + out, out.contains("官网"));
        assertEquals("https://example.com", urlSpanUrl(cs));
    }

    @Test
    public void render_bareUrl_stillLinked() {
        CharSequence cs = renderer().render("裸链接 https://a.example.com/x 结束\n");
        assertTrue(cs.toString().contains("https://a.example.com/x"));
        assertEquals("https://a.example.com/x", urlSpanUrl(cs));
    }

    @Test
    public void render_boldWrappingLink_stillLinked() {
        CharSequence cs = renderer().render("**[官网](https://example.com)**\n");
        assertFalse("加粗里套链接也不能残留语法：" + cs, cs.toString().contains("]("));
        assertEquals("https://example.com", urlSpanUrl(cs));
    }

    @Test
    public void render_taskList_usesCheckboxGlyphs() {
        String out = renderer().render("- [ ] 未做\n- [x] 已做\n").toString();
        assertTrue("未勾选应显示 ☐：" + out, out.contains("\u2610"));
        assertTrue("已勾选应显示 ☑：" + out, out.contains("\u2611"));
        assertFalse("不应残留勾选语法：" + out, out.contains("[ ]"));
    }

    @Test
    public void render_horizontalRule_drawsDivider() {
        String out = renderer().render("上\n\n---\n\n下\n").toString();
        assertFalse("不应再原样显示 ---：" + out, out.contains("---"));
        assertTrue(out.contains(MarkdownRenderer.DIVIDER_CHAR));
    }
}
