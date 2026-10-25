package com.digitallife.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.Spannable;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
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
                0xFFCCCCCC  // dividerColor
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

    // ==================== issue #44：行内链接 / 任务列表 / 水平线 ====================

    @Test
    public void render_inlineLinkShowsTextOnly() {
        // [文字](url) 只显示文字，url 与方括号都不该出现在可见文本里
        String out = renderer().render("[点我](https://example.com)").toString();
        assertTrue(out.contains("点我"));
        assertFalse("可见文本不应残留 url：" + out, out.contains("https://example.com"));
        assertFalse("可见文本不应残留方括号：" + out, out.contains("[点我]"));
    }

    @Test
    public void render_inlineLinkHasUrlSpan() {
        // 链接文字上必须挂 URLSpan，且指向原 url
        Spannable ss = (Spannable) renderer().render("[点我](https://example.com)");
        URLSpan[] spans = ss.getSpans(0, ss.length(), URLSpan.class);
        assertEquals("应恰好一个 URLSpan：" + spans.length, 1, spans.length);
        assertEquals("https://example.com", spans[0].getURL());
    }

    @Test
    public void render_bareUrlStillHasLink() {
        // 裸 URL 行为不变：仍带链接 span
        Spannable ss = (Spannable) renderer().render("详见 https://example.com 的说明");
        URLSpan[] spans = ss.getSpans(0, ss.length(), URLSpan.class);
        assertTrue("裸 URL 应仍带链接 span，实际：" + spans.length, spans.length >= 1);
        assertEquals("https://example.com", spans[0].getURL());
    }

    @Test
    public void render_tableLinkKeepsTextOnly() {
        // 表格单元格里的 [文字](url) 仍只留文字（stripInline），不渲染成可点链接
        String out = renderer().render("| 链接 |\n|---|\n| [点我](https://example.com) |\n").toString();
        assertTrue(out.contains("点我"));
        assertFalse("表内链接不应残留 url：" + out, out.contains("https://example.com"));
    }

    @Test
    public void render_taskUncheckedRendersEmptyBox() {
        String out = renderer().render("- [ ] 待办\n").toString();
        assertTrue("未勾选应渲染 ☐：" + out, out.contains("\u2610 待办"));
    }

    @Test
    public void render_taskCheckedRendersCheckedBox() {
        String out = renderer().render("- [x] 已完成\n").toString();
        assertTrue("已勾选应渲染 ☑：" + out, out.contains("\u2611 已完成"));
    }

    @Test
    public void render_taskCheckedAndUncheckedDiffer() {
        String a = renderer().render("- [ ] 待办\n").toString();
        String b = renderer().render("- [x] 待办\n").toString();
        assertTrue("勾选态不同应渲染出不同的符号：" + a + " | " + b, !a.equals(b));
    }

    @Test
    public void render_horizontalRuleNotLiteral() {
        // 独立成行的 --- 不应原样出现，应被替换成淡色分隔线
        String out = renderer().render("上方\n---\n下方").toString();
        assertFalse("水平线不应原样显示 ---：" + out, out.contains("---"));
        assertTrue("水平线应渲染成横线字符：" + out, out.contains("\u2500"));
    }

    // ==================== v1.187.0：真表格（位图 + ImageSpan） ====================

    private static ImageSpan[] images(Spannable ss) {
        return ss.getSpans(0, ss.length(), ImageSpan.class);
    }

    @Test
    public void renderRich_tableBecomesImageSpan() {
        String md = "| 名称 | 说明 |\n|---|---|\n| 小汐 | 数字生命助手 |\n";
        Spannable ss = (Spannable) renderer().renderRich(md, 600, 42f);
        ImageSpan[] spans = images(ss);
        assertEquals("表格应渲染成一张位图：" + spans.length, 1, spans.length);
    }

    @Test
    public void renderRich_tableTextStaysSearchable() {
        // 位图只是显示层，被替换的文字仍在 CharSequence 里：复制 / 搜索都靠它
        String md = "| 名称 | 说明 |\n|---|---|\n| 小汐 | 数字生命助手 |\n";
        String out = renderer().renderRich(md, 600, 42f).toString();
        assertTrue("表头文字应保留：" + out, out.contains("名称"));
        assertTrue("单元格文字应保留：" + out, out.contains("小汐"));
        assertFalse("行内标记仍应剥掉：" + out, out.contains("**"));
    }

    /** ReplacementSpan 跨换行会被 StaticLayout 拆成多段重复绘制，所以 span 内不能有换行 */
    @Test
    public void renderRich_tableSpanDoesNotContainNewline() {
        String md = "| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |\n";
        Spannable ss = (Spannable) renderer().renderRich(md, 600, 42f);
        ImageSpan[] spans = images(ss);
        assertEquals(1, spans.length);
        String spanned = ss.subSequence(ss.getSpanStart(spans[0]), ss.getSpanEnd(spans[0])).toString();
        assertFalse("span 内不应有换行：" + spanned, spanned.contains("\n"));
    }

    @Test
    public void renderRich_tableBitmapFitsAvailableWidth() {
        String md = "| 名称 | 说明 |\n|---|---|\n| 小汐 | 数字生命助手 |\n";
        Spannable ss = (Spannable) renderer().renderRich(md, 420, 42f);
        ImageSpan[] spans = images(ss);
        assertEquals(1, spans.length);
        int w = spans[0].getDrawable().getBounds().width();
        assertTrue("位图宽度应在可用宽度内，实际 " + w, w > 0 && w <= 420);
    }

    @Test
    public void renderRich_narrowWidthFallsBackToTextTable() {
        // 连最小列宽都塞不下时不能画出超宽位图，退回等宽文本
        String md = "| a | b | c |\n|---|---|---|\n| 1 | 2 | 3 |\n";
        Spannable ss = (Spannable) renderer().renderRich(md, 40, 42f);
        assertEquals("放不下时不应产生位图", 0, images(ss).length);
        assertTrue("仍应保留单元格文字：" + ss, ss.toString().contains("a"));
    }

    @Test
    public void renderRich_plainTextHasNoImageSpan() {
        Spannable ss = (Spannable) renderer().renderRich("普通文本\n\n- 列表项\n", 600, 42f);
        assertEquals("非表格内容不应产生位图", 0, images(ss).length);
        assertTrue(ss.toString().contains("普通文本"));
    }

    @Test
    public void renderRich_textAroundTableIsKept() {
        String md = "前文\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n后文";
        String out = renderer().renderRich(md, 600, 42f).toString();
        assertTrue("表格前的文字应保留：" + out, out.contains("前文"));
        assertTrue("表格后的文字应保留：" + out, out.contains("后文"));
    }

    // ==================== v1.188.0：代码块语法高亮 ====================

    @Test
    public void render_codeBlockAppliesSyntaxColors() {
        String md = "```java\npublic class Demo { String s = \"hi\"; }\n```";
        Spannable ss = (Spannable) renderer().render(md);
        ForegroundColorSpan[] spans =
                ss.getSpans(0, ss.length(), ForegroundColorSpan.class);
        boolean hasKeyword = false;
        boolean hasString = false;
        for (ForegroundColorSpan span : spans) {
            if (span.getForegroundColor() == 0xFFFF7B72) hasKeyword = true;
            if (span.getForegroundColor() == 0xFFA5D6FF) hasString = true;
        }
        assertTrue("关键字应着色：" + spans.length, hasKeyword);
        assertTrue("字符串应着色：" + spans.length, hasString);
    }

    @Test
    public void render_codeBlockKeepsTextAndCopyTip() {
        String md = "```java\nint a = 1;\n```";
        String out = renderer().render(md).toString();
        assertTrue("代码正文应保留：" + out, out.contains("int a = 1;"));
        assertTrue("点击复制提示应保留：" + out, out.contains("[复制代码]"));
    }

    @Test
    public void renderRich_codeBlockAlsoHighlighted() {
        // 表格位图走的是 renderRich，代码块必须同样上色
        String md = "```python\ndef f():\n    return True\n```\n";
        Spannable ss = (Spannable) renderer().renderRich(md, 600, 42f);
        ForegroundColorSpan[] spans =
                ss.getSpans(0, ss.length(), ForegroundColorSpan.class);
        boolean hasKeyword = false;
        for (ForegroundColorSpan span : spans) {
            if (span.getForegroundColor() == 0xFFFF7B72) hasKeyword = true;
        }
        assertTrue("代码块在 renderRich 里也应高亮", hasKeyword);
    }

    /** 同类型 span 重叠时谁生效取决于平台优先级，所以前景色区间必须互不重叠 */
    @Test
    public void render_codeBlockForegroundSpansDoNotOverlap() {
        String md = "```java\npublic class Demo { String s = \"hi\"; } // 注释\n```";
        Spannable ss = (Spannable) renderer().render(md);
        ForegroundColorSpan[] spans =
                ss.getSpans(0, ss.length(), ForegroundColorSpan.class);
        assertTrue("应有多段前景色 span，实际 " + spans.length, spans.length > 3);
        int[] starts = new int[spans.length];
        int[] ends = new int[spans.length];
        for (int i = 0; i < spans.length; i++) {
            starts[i] = ss.getSpanStart(spans[i]);
            ends[i] = ss.getSpanEnd(spans[i]);
        }
        // 按起点排序（插入排序，数组很小）
        for (int i = 1; i < spans.length; i++) {
            for (int j = i; j > 0 && starts[j - 1] > starts[j]; j--) {
                int tmp = starts[j]; starts[j] = starts[j - 1]; starts[j - 1] = tmp;
                tmp = ends[j]; ends[j] = ends[j - 1]; ends[j - 1] = tmp;
            }
        }
        for (int i = 1; i < spans.length; i++) {
            assertTrue("前景色 span 不应重叠：[" + starts[i - 1] + "," + ends[i - 1]
                    + ") vs [" + starts[i] + "," + ends[i] + ")",
                    ends[i - 1] <= starts[i]);
        }
    }
}
