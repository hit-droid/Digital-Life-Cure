package com.digitallife.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
                0xFF3366CC  // linkColor
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
}
