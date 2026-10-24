package com.digitallife.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.Layout;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.ImageSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.graphics.Typeface;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 Markdown 渲染：标题/加粗/斜体/行内代码/代码块/无序与有序列表/表格。
 * 纯 Spannable 实现，无第三方依赖，输出可直接 setText。
 */
public final class MarkdownRenderer {

    private static final Pattern CODE_BLOCK = Pattern.compile("```[^\\n]*\\n?([\\s\\S]*?)(?:```|$)");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    /**
     * v1.136.0：四个行级正则都放开了前导空白（{@code ^(\s*)}）。
     * 之前锚死在行首，LLM 输出的多级列表里被缩进的子项一行都匹配不上。
     * OL_ITEM 另外把「顿号后必须带空格」放宽了：中文列举习惯写成 {@code 1、第一}；
     * 但**英文句号仍要求空格**，否则 {@code 3.14 是圆周率} 会被当成第 3 条列表项。
     * HEADING 的 #{1,3} 后面也从「至少一个空白」放宽成「可以没有空格」
     * （模型写中文标题时常写成 {@code #标题}）。
     * 末尾的 {@code (?!#)} 是护栏：放宽之后 {@code #### 四级} 里的前三个井号会
     * 被当成三级标题、剩下那个井号漏进正文，必须显式挡掉四级及以上。
     */
    private static final Pattern HEADING = Pattern.compile("^(\\s*)(#{1,3})(?!#)\\s*(.+)$");
    private static final Pattern UL_ITEM = Pattern.compile("^(\\s*)[-*]\\s+(.+)$");
    private static final Pattern OL_ITEM = Pattern.compile("^(\\s*)(\\d+)(?:\\.\\s+|、\\s*)(.+)$");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^(\\s*)>\\s?(.+)$");
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]<>]+");
    /** 行内链接：[文字](url)。正文渲染与表格 stripInline 共用同一份，避免两套正则漂移 */
    private static final Pattern INLINE_LINK = Pattern.compile("\\[([^\\]]*)\\]\\(([^)]*)\\)");
    /** 任务列表项：- [ ] / - [x] / - [X]（后必须跟空格，否则不是勾选框） */
    private static final Pattern TASK_ITEM = Pattern.compile("^\\[([ xX])\\]\\s+(.+)$");
    /** 水平线：--- / *** / ___（同一字符连续 3+，整行仅此） */
    private static final Pattern HR = Pattern.compile("^\\s*([-*_])\\1{2,}\\s*$");
    /** 水平线渲染用的淡色横线（box-drawing，固定长度，足够铺满一般气泡） */
    private static final String HR_LINE =
            "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500"
            + "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500";

    private final int codeBgColor;
    private final int codeTextColor;
    private final int quoteTextColor;
    private final int baseTextColor;
    private final int linkColor;
    private final int dividerColor;

    public MarkdownRenderer(int codeBgColor, int codeTextColor,
                            int quoteTextColor, int baseTextColor, int linkColor,
                            int dividerColor) {
        this.codeBgColor = codeBgColor;
        this.codeTextColor = codeTextColor;
        this.quoteTextColor = quoteTextColor;
        this.baseTextColor = baseTextColor;
        this.linkColor = linkColor;
        this.dividerColor = dividerColor;
    }

    public CharSequence render(String raw) {
        if (raw == null) return "";
        String text = raw.replace("\r\n", "\n");
        SpannableStringBuilder out = new SpannableStringBuilder();
        int pos = 0;
        Matcher m = CODE_BLOCK.matcher(text);
        while (m.find()) {
            appendInline(out, text.substring(pos, m.start()));
            appendCodeBlock(out, m.group(1));
            pos = m.end();
        }
        appendInline(out, text.substring(pos));
        return out;
    }

    /**
     * v1.187.0：把表格画成真正的网格位图。
     *
     * <p>{@link #render} 里的表格是等宽字符对齐的纯文本，列被压缩、内容被截断，
     * 读起来仍然像代码。这里保留文本渲染作为兜底，新增一条「表格 → 位图」的出口：
     * 单元格按可用宽度自动换行、表头加粗、有网格线。</p>
     *
     * <p>位图用 {@link ImageSpan} 铺在表格原文上——被替换的文字仍然留在
     * {@code CharSequence} 里，所以复制、搜索、折叠提示都能照常工作，
     * 只是屏幕上显示的是图片。</p>
     *
     * @param maxWidthPx  表格可用宽度（px），超出会等比压缩列宽
     * @param textSizePx  气泡字号（px），用来决定表格内文字大小
     */
    public CharSequence renderRich(String raw, int maxWidthPx, float textSizePx) {
        if (raw == null) return "";
        String text = raw.replace("\r\n", "\n");
        SpannableStringBuilder out = new SpannableStringBuilder();
        BitmapBudget budget = new BitmapBudget();
        // 本次渲染可用额度 = 全页剩余额度（单条消息再额外限 4 张表）
        budget.remainingPixels = (int) java.lang.Math.min(1_500_000L,
                java.lang.Math.max(0L, MAX_TABLE_BITMAP_PIXELS - tableBitmapPixelsUsed));
        int pos = 0;
        Matcher m = CODE_BLOCK.matcher(text);
        while (m.find()) {
            appendInline(out, text.substring(pos, m.start()), maxWidthPx, textSizePx, budget);
            appendCodeBlock(out, m.group(1));
            pos = m.end();
        }
        appendInline(out, text.substring(pos), maxWidthPx, textSizePx, budget);
        return out;
    }


    private void appendCodeBlock(SpannableStringBuilder out, String code) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        String block = code == null ? "" : code;
        if (block.endsWith("\n")) block = block.substring(0, block.length() - 1);
        // v1.43.0：block 上面被重新赋值过，不是 effectively final，
        // 匿名内部类无法直接捕获，这里取一份 final 副本供复制使用。
        final String codeToCopy = block;
        int start = out.length();
        out.append(block);
        int end = out.length();
        if (start < end) {
            out.setSpan(new BackgroundColorSpan(codeBgColor), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(codeTextColor), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new TypefaceSpan("monospace"), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // v1.43.0：点击代码块即复制该块内容
            out.setSpan(new android.text.style.ClickableSpan() {
                @Override
                public void onClick(android.view.View widget) {
                    copyCodeToClipboard(widget, codeToCopy);
                }
            }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // v1.43.0：块尾追加复制提示（linkColor 小字，同样可点）
            int tipStart = out.length();
            out.append("  [复制代码]");
            out.setSpan(new android.text.style.RelativeSizeSpan(0.8f),
                    tipStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(linkColor),
                    tipStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new android.text.style.ClickableSpan() {
                @Override
                public void onClick(android.view.View widget) {
                    copyCodeToClipboard(widget, codeToCopy);
                }
            }, tipStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        out.append('\n');
    }

    /** v1.43.0：把代码块内容写入剪贴板并提示，异常静默忽略 */
    private static void copyCodeToClipboard(android.view.View anchor, String code) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) anchor.getContext()
                            .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("代码", code));
                android.widget.Toast.makeText(anchor.getContext(),
                        "代码已复制", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.46.0：行内渲染入口。表格跨多行，必须在块级别识别，
     * 不能塞进 appendLine（那里只逐行处理）。这里先按行扫出表格块，
     * 其余文本仍走原有的逐行逻辑，非表格内容行为完全不变。
     */
    private void appendInline(SpannableStringBuilder out, String text) {
        appendInline(out, text, 0, 0f, null);
    }

    /**
     * @param maxTableWidthPx   >0 时表格走位图渲染；否则走等宽纯文本
     * @param tableTextSizePx   位图表格的字号（px）
     * @param budget           单条消息的位图预算，为 null 时不做位图
     */
    private void appendInline(SpannableStringBuilder out, String text,
                              int maxTableWidthPx, float tableTextSizePx, BitmapBudget budget) {
        if (text == null || text.isEmpty()) return;
        String[] lines = text.split("\n", -1);
        int i = 0;
        boolean first = true;
        while (i < lines.length) {
            boolean isTable = isTableStart(lines, i);
            int end;
            if (isTable) {
                end = i;
                while (end < lines.length && isTableRow(lines[end])) end++;
            } else {
                end = i + 1;
            }
            if (!first) out.append('\n');
            first = false;
            if (isTable) {
                if (maxTableWidthPx > 0 && tableTextSizePx > 0f && budget != null
                        && budget.canRenderTable()) {
                    appendTableBitmap(out, lines, i, end, maxTableWidthPx, tableTextSizePx, budget);
                } else {
                    appendTable(out, lines, i, end);
                }
            } else {
                appendLine(out, lines[i]);
            }
            i = end;
        }
    }


    /** v1.46.0：表格首行判定——本行是表格行，且下一行是 |---| 分隔行 */
    private static boolean isTableStart(String[] lines, int i) {
        return isTableRow(lines[i]) && i + 1 < lines.length && isSeparatorRow(lines[i + 1]);
    }

    private static boolean isTableRow(String line) {
        String t = line.trim();
        return t.startsWith("|") && t.indexOf('|', 1) > 0;
    }

    /** 分隔行只由 - | : 空格 组成，且必须含 |，避免把 --- 水平线误判成表格 */
    private static boolean isSeparatorRow(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.indexOf('-') < 0 || t.indexOf('|') < 0) return false;
        for (int k = 0; k < t.length(); k++) {
            char c = t.charAt(k);
            if (c != '-' && c != '|' && c != ':' && c != ' ') return false;
        }
        return true;
    }

    /** 单列自然宽度上限 */
    private static final int MAX_COL_WIDTH = 24;
    /** 列间距（半角字符数） */
    private static final int COL_GAP = 2;

    /**
     * 整表总宽上限（半角字符数）。v1.131.0：表格是等宽文本渲染，
     * 总宽一旦超过气泡可用宽度，TextView 就会在列间空隙处折行、整张表散掉。
     * 按 15sp 等宽字体估算，手机上大约放得下 32 个半角字符。
     */
    static final int MAX_TABLE_WIDTH = 32;

    /** 压缩时单列的下限，再窄内容就没法读了 */
    static final int MIN_COL_WIDTH = 3;

    /**
     * v1.131.0：把各列自然宽度等比压缩进总宽预算（单列不低于 {@link #MIN_COL_WIDTH}）。
     * <p>宁可每列窄一点，也要保证一行放得下——折行会让表格结构彻底乱掉，
     * 比压缩难看得多。列数多到连下限都塞不下时放弃压缩（接受溢出），
     * 至少不会把某列压成 0 导致内容整列消失。</p>
     * <p>包内可见，便于单测。</p>
     */
    static int[] fitColumns(int[] natural, int budget) {
        if (natural == null) return new int[0];
        int[] out = natural.clone();
        long total = 0;
        for (int w : out) total += w;
        if (budget <= 0 || total <= budget) return out;
        long scaled = 0;
        for (int i = 0; i < out.length; i++) {
            out[i] = java.lang.Math.max(MIN_COL_WIDTH,
                    (int) java.lang.Math.floor(out[i] * (long) budget / total));
            scaled += out[i];
        }
        // 下限保护可能让总宽又超了：从最宽的列开始让，直到塞进预算
        while (scaled > budget) {
            int idx = -1;
            int max = 0;
            for (int i = 0; i < out.length; i++) {
                if (out[i] > MIN_COL_WIDTH && out[i] > max) {
                    max = out[i];
                    idx = i;
                }
            }
            if (idx < 0) break;   // 全都到下限了，接受溢出
            out[idx]--;
            scaled--;
        }
        return out;
    }

    /**
     * v1.131.0：空格换成不换行空格。
     * 表格靠空格对齐，但普通空格是 TextView 的合法折行点——宽表格会被从这里
     * 折断成两截，对齐全乱。换成 NBSP 后整行不会断。
     */
    static String noBreakSpaces(String s) {
        return s == null ? "" : s.replace(' ', '\u00A0');
    }

    /** v1.46.0：把表格块渲染成等宽对齐文本，表头加粗 */
    private void appendTable(SpannableStringBuilder out, String[] lines, int from, int to) {
        java.util.List<String[]> rows = parseTableRows(lines, from, to);
        if (rows.isEmpty()) return;
        appendTableRows(out, rows);
    }

    /** 表格块 → 结构化行数据（已剥掉行内标记、跳过分隔行） */
    private static java.util.List<String[]> parseTableRows(String[] lines, int from, int to) {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (int r = from; r < to; r++) {
            if (isSeparatorRow(lines[r])) continue;   // 分隔行不展示，靠对齐自然成表
            // v1.125.0：先剥掉行内标记再算列宽，否则 ** 之类标记会白占宽度、把对齐撑歪
            rows.add(stripInlineCells(splitRow(lines[r])));
        }
        return rows;
    }

    /** 等宽纯文本表格（位图预算不足或宽度不够时的兜底） */
    private void appendTableRows(SpannableStringBuilder out, java.util.List<String[]> rows) {
        if (rows.isEmpty()) return;
        int cols = 0;
        for (String[] c : rows) cols = java.lang.Math.max(cols, c.length);
        if (cols == 0) return;

        int[] widths = new int[cols];
        for (String[] c : rows) {
            for (int i = 0; i < c.length; i++) {
                widths[i] = java.lang.Math.max(widths[i], displayWidth(c[i]));
            }
        }
        // 单列限宽，避免超宽表格横向撑爆气泡
        for (int i = 0; i < cols; i++) widths[i] = java.lang.Math.min(widths[i], MAX_COL_WIDTH);
        // v1.131.0：整表压进总宽预算，宁可列窄一点也不要换行
        widths = fitColumns(widths, MAX_TABLE_WIDTH - COL_GAP * (cols - 1));

        int start = out.length();
        for (int r = 0; r < rows.size(); r++) {
            String[] c = rows.get(r);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < cols; i++) {
                String cell = i < c.length ? c[i] : "";
                if (displayWidth(cell) > widths[i]) cell = truncateCell(cell, widths[i]);
                line.append(padCell(cell, widths[i]));
                if (i < cols - 1) line.append("  ");
            }
            // v1.131.0：整行转 NBSP，列间空隙不再是折行点
            out.append(noBreakSpaces(line.toString()));
            if (r < rows.size() - 1) out.append('\n');
        }
        int end = out.length();
        if (start >= end) return;
        out.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        // 表头加粗：首行长度 = 各列宽之和 + 列间距
        int headEnd = start;
        for (int i = 0; i < cols; i++) headEnd += widths[i] + (i < cols - 1 ? 2 : 0);
        out.setSpan(new StyleSpan(Typeface.BOLD), start, java.lang.Math.min(headEnd, end),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    // ==================== v1.187.0：真表格（位图） ====================

    /**
     * 单条消息的位图预算。
     *
     * <p>一条回复里可能有十几张表，每张都画一张 ARGB_8888 位图的话，历史消息一多
     * 就会占掉几十 MB。超过预算的表格退回等宽文本，宁可难看一点也不能 OOM。</p>
     */
    private static final class BitmapBudget {
        /** 本次渲染剩余可用像素（宽 × 高） */
        int remainingPixels;
        /** 单条消息最多画几张表 */
        int remainingTables = 4;

        boolean canRenderTable() {
            return remainingTables > 0 && remainingPixels > 0;
        }
    }

    /**
     * 整个页面所有消息的表格位图总预算（像素数，约 24 MB）。
     * <p>历史里有几十条带表格的消息时，逐条画位图会吃掉上百 MB；超出预算的
     * 表格自动退回等宽文本。</p>
     */
    private static final long MAX_TABLE_BITMAP_PIXELS = 6_000_000L;
    /** 本渲染器已经画掉的表格位图像素 */
    private long tableBitmapPixelsUsed;

    /**
     * 把表格画成一张位图，再用 {@link ImageSpan} 替换掉表格原文。
     *
     * <p>被替换的文字仍然留在 CharSequence 里（复制 / 搜索能取到），但屏幕上
     * 不绘制文字，只绘制位图。不能把换行符一起包进 span：ReplacementSpan 跨段
     * 会被 StaticLayout 拆成多段重复绘制，所以行与行之间用「；」连接。</p>
     */
    private void appendTableBitmap(SpannableStringBuilder out, String[] lines, int from, int to,
                                   int maxWidthPx, float textSizePx, BitmapBudget budget) {
        java.util.List<String[]> rows = parseTableRows(lines, from, to);
        if (rows.isEmpty()) return;
        Bitmap bitmap = drawTableBitmap(rows, maxWidthPx, textSizePx);
        if (bitmap == null) {
            appendTableRows(out, rows);
            return;
        }
        String plain = plainTableText(rows);
        if (plain.isEmpty() || plain.indexOf('\n') >= 0) {
            bitmap.recycle();
            appendTableRows(out, rows);
            return;
        }
        budget.remainingTables--;
        int pixels = bitmap.getWidth() * bitmap.getHeight();
        budget.remainingPixels -= pixels;
        tableBitmapPixelsUsed += pixels;
        SpannableString spanText = new SpannableString(plain);
        spanText.setSpan(new ImageSpan(bitmap), 0, spanText.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append(spanText);
    }

    /** 表格位图对应的纯文本：单元格用 | 分隔，行与行用「；」（见上面的换行说明） */
    private static String plainTableText(java.util.List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            String[] cells = rows.get(r);
            if (r > 0) sb.append('；');
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) sb.append(" | ");
                sb.append(cells[i] == null ? "" : cells[i]);
            }
        }
        return sb.toString().trim();
    }

    private static String cellAt(String[] row, int col) {
        if (row == null || col < 0 || col >= row.length) return "";
        return row[col] == null ? "" : row[col];
    }

    private static float luminance(int color) {
        return (0.299f * Color.red(color) + 0.587f * Color.green(color)
                + 0.114f * Color.blue(color)) / 255f;
    }

    private static StaticLayout makeCellLayout(String text, TextPaint paint, int width) {
        String t = text == null || text.isEmpty() ? " " : text;
        // 旧构造器（API 1+）没有 Builder 的 API 23 限制，minSdk 21 也能用
        return new StaticLayout(t, paint, java.lang.Math.max(1, width),
                Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
    }

    /**
     * 画表格位图；宽度塞不下（列数太多，连最小列宽都不够）时返回 null。
     * <p>包内可见仅供单测间接验证（通过 renderRich 的 span）。</p>
     */
    private Bitmap drawTableBitmap(java.util.List<String[]> rows, int maxWidthPx, float textSizePx) {
        if (rows.isEmpty() || maxWidthPx <= 0 || textSizePx <= 0f) return null;
        int cols = 0;
        for (String[] r : rows) cols = java.lang.Math.max(cols, r.length);
        if (cols == 0) return null;

        float density = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        if (density <= 0f) density = 1f;
        int padH = java.lang.Math.max(4, java.lang.Math.round(6 * density));
        int padV = java.lang.Math.max(3, java.lang.Math.round(5 * density));
        int corner = java.lang.Math.round(8 * density);
        // 最小列宽：留出两个字的位置，否则中文表头会被拆成一字一行
        int minCol = java.lang.Math.max(padH * 2 + java.lang.Math.round(textSizePx * 2f),
                java.lang.Math.round(36 * density));

        TextPaint body = new TextPaint(TextPaint.ANTI_ALIAS_FLAG);
        body.setTextSize(textSizePx);
        body.setColor(baseTextColor);
        TextPaint head = new TextPaint(body);
        head.setFakeBoldText(true);

        // 1) 每列按内容算自然宽度
        int[] widths = new int[cols];
        for (int c = 0; c < cols; c++) {
            int w = minCol;
            for (int r = 0; r < rows.size(); r++) {
                Paint p = r == 0 ? head : body;
                float textW = p.measureText(cellAt(rows.get(r), c));
                w = java.lang.Math.max(w, (int) java.lang.Math.ceil(textW) + padH * 2);
            }
            widths[c] = w;
        }
        // 2) 超出可用宽度：从最宽的列开始削。
        //    等比压缩会把本来就只有两三个字的窄列压成「一字一行」，
        //    优先削宽列既能保住窄列，又让宽列靠换行消化内容。
        int total = 0;
        for (int w : widths) total += w;
        if ((long) minCol * cols > maxWidthPx) return null;
        while (total > maxWidthPx) {
            int idx = -1;
            int max = -1;
            for (int c = 0; c < cols; c++) {
                if (widths[c] > minCol && widths[c] > max) {
                    max = widths[c];
                    idx = c;
                }
            }
            if (idx < 0) return null;   // 全都到下限了还放不下
            widths[idx]--;
            total--;
        }
        int tableW = 0;
        for (int w : widths) tableW += w;

        // 3) 量每一行的高度（单元格内自动换行）
        int rowCount = rows.size();
        int[] rowH = new int[rowCount];
        StaticLayout[][] cells = new StaticLayout[rowCount][cols];
        for (int r = 0; r < rowCount; r++) {
            TextPaint p = r == 0 ? head : body;
            for (int c = 0; c < cols; c++) {
                StaticLayout sl = makeCellLayout(cellAt(rows.get(r), c), p, widths[c] - padH * 2);
                cells[r][c] = sl;
                rowH[r] = java.lang.Math.max(rowH[r], sl.getHeight() + padV * 2);
            }
        }
        int tableH = 0;
        for (int h : rowH) tableH += h;
        if (tableW <= 0 || tableH <= 0) return null;
        // 单表最多 4 MB（ARGB_8888）
        if ((long) tableW * tableH * 4 > 4L * 1024 * 1024) return null;

        Bitmap bitmap = Bitmap.createBitmap(tableW, tableH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // 底：圆角面板
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(codeBgColor);
        RectF bounds = new RectF(0, 0, tableW, tableH);
        canvas.drawRoundRect(bounds, corner, corner, bg);
        // 表头底色：按文字明暗取一层半透明覆盖（深色气泡提亮，浅色气泡压暗）
        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(bounds, corner, corner, Path.Direction.CW);
        canvas.clipPath(clip);
        Paint headBg = new Paint();
        headBg.setColor(luminance(baseTextColor) > 0.5f ? 0x1FFFFFFF : 0x0F000000);
        canvas.drawRect(0, 0, tableW, rowH[0], headBg);
        canvas.restore();

        // 网格线
        Paint line = new Paint();
        line.setColor(dividerColor);
        line.setStrokeWidth(java.lang.Math.max(1f, density * 0.75f));
        int y = 0;
        for (int r = 0; r < rowCount; r++) {
            if (r > 0) canvas.drawLine(0, y, tableW, y, line);
            int x = 0;
            for (int c = 0; c < cols; c++) {
                if (c > 0) canvas.drawLine(x, y, x, y + rowH[r], line);
                canvas.save();
                canvas.translate(x + padH, y + padV);
                cells[r][c].draw(canvas);
                canvas.restore();
                x += widths[c];
            }
            y += rowH[r];
        }
        return bitmap;
    }


    private static String[] splitRow(String line) {
        String t = line.trim();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|") && t.length() > 1) t = t.substring(0, t.length() - 1);
        String[] parts = t.split("\\|", -1);
        for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
        return parts;
    }

    /** 整行单元格逐个剥掉行内 Markdown 标记 */
    private static String[] stripInlineCells(String[] cells) {
        if (cells == null) return cells;
        for (int i = 0; i < cells.length; i++) cells[i] = stripInline(cells[i]);
        return cells;
    }

    /**
     * 剥掉行内 Markdown 标记，只留纯文本。
     * 表格是等宽对齐的纯文本渲染，标记既显示不出来又会占列宽，
     * 所以这里直接去掉：{@code **加粗**} → {@code 加粗}。
     * <p>包内可见，便于单测（MarkdownRendererTest）。</p>
     */
    static String stripInline(String s) {
        if (s == null) return "";
        String t = s.trim();
        // [文字](链接) -> 文字（与正文渲染共用 INLINE_LINK 同一份正则）
        t = INLINE_LINK.matcher(t).replaceAll("$1");
        // `代码` -> 代码
        t = t.replaceAll("`([^`]*)`", "$1");
        // **加粗** -> 加粗（必须在单星号之前处理，否则会被当成斜体拆开）
        t = t.replaceAll("\\*\\*([^*]*)\\*\\*", "$1");
        // ~~删除线~~ -> 删除线
        t = t.replaceAll("~~([^~]*)~~", "$1");
        // *斜体* -> 斜体（此时 ** 已处理完，剩下的单星号成对出现）
        t = t.replaceAll("\\*([^*]+)\\*", "$1");
        return t.trim();
    }

    /** 显示宽度：全角字符按 2 计，等宽字体下对齐才准 */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) w += isWide(s.charAt(i)) ? 2 : 1;
        return w;
    }

    private static boolean isWide(char c) {
        return (c >= '\u2E80' && c <= '\u9FFF') || (c >= '\uFF00' && c <= '\uFFEF')
                || (c >= '\uAC00' && c <= '\uD7AF');
    }

    private static String padCell(String s, int width) {
        StringBuilder sb = new StringBuilder(s);
        int w = displayWidth(s);
        while (w < width) { sb.append(' '); w++; }
        return sb.toString();
    }

    /**
     * 截断到指定显示宽度，末尾补省略号。
     *
     * <p>v1.136.0：改成按**码点**推进。emoji 是两个 char 的高低代理对，
     * 逐 char 累加时，(width-1) 的边界正好落在代理之间就会留下半个 emoji，
     * 渲染出来是豆腐块；现在整体判定，放不下就整个不要。</p>
     * <p>包内可见，便于单测。</p>
     */
    static String truncateCell(String s, int width) {
        if (displayWidth(s) <= width) return s;
        if (width <= 1) return "\u2026";
        StringBuilder sb = new StringBuilder();
        int w = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            int len = 1;
            int cw = isWide(c) ? 2 : 1;
            if (Character.isHighSurrogate(c) && i + 1 < s.length()
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                // 一个代理对整体按 2 个半角宽计，与 displayWidth 逐 char 计数的结果一致
                len = 2;
                cw = 2;
            }
            if (w + cw > width - 1) break;
            sb.append(s, i, i + len);
            w += cw;
            i += len;
        }
        sb.append('\u2026');
        return sb.toString();
    }

    // ==================== 行级块识别（纯逻辑，便于 JVM 单测） ====================

    /** 一行的块类型 */
    enum BlockKind { PLAIN, HEADING, QUOTE, UL, OL, TASK, HR }

    /**
     * 列表 / 引用的缩进层级上限。深嵌套如果一路缩进下去，正文会被推到屏外，
     * 超出这个层级后一律按本级渲染。
     */
    static final int MAX_LIST_LEVEL = 3;

    /**
     * 一级缩进的单位：两个**不换行**空格。
     * 普通空格在行首会被 TextView 的折行策略吃掉，层级照样看不见，所以用 NBSP。
     */
    static final String LIST_INDENT_UNIT = "\u00A0\u00A0";

    /**
     * 一行的块级解析结果。
     *
     * <p>原先这些判定散在 appendLine 里、边匹配边拼 Span，没法在 JVM 上验证。
     * 这里只负责「识别」，不碰任何 Span，好把各种写法钉死在单测里。</p>
     */
    static final class BlockLine {
        final BlockKind kind;
        /** HEADING 时为 1~3 级；其余是缩进层级 0~{@link #MAX_LIST_LEVEL} */
        final int level;
        /** 行首缩进（已按层级限幅）；PLAIN / HEADING 为空串 */
        final String indent;
        /** 去掉块标记后的正文；PLAIN 时是原始行 */
        final String text;
        /** 有序列表的原始序号（如 "3"），其余为 null */
        final String marker;
        /** 仅 TASK 有意义：是否已完成（[x]/[X] 为 true） */
        final boolean checked;

        BlockLine(BlockKind kind, int level, String indent, String text, String marker, boolean checked) {
            this.kind = kind;
            this.level = level;
            this.indent = indent;
            this.text = text;
            this.marker = marker;
            this.checked = checked;
        }
    }

    /**
     * 识别一行属于哪种块。
     *
     * <p>放宽是纯增量的：原先能匹配上的行照样匹配，只是多了「带缩进的子项」
     * 和「# 后无空格」这两种原先漏掉的写法。</p>
     */
    static BlockLine parseBlockLine(String line) {
        if (line == null) return new BlockLine(BlockKind.PLAIN, 0, "", "", null, false);

        Matcher h = HEADING.matcher(line);
        if (h.matches()) {
            return new BlockLine(BlockKind.HEADING, h.group(2).length(), "", h.group(3), null, false);
        }
        Matcher bq = BLOCKQUOTE.matcher(line);
        if (bq.matches()) {
            int lv = indentLevel(bq.group(1));
            return new BlockLine(BlockKind.QUOTE, lv, indentOf(lv), bq.group(2), null, false);
        }
        Matcher ul = UL_ITEM.matcher(line);
        if (ul.matches()) {
            int lv = indentLevel(ul.group(1));
            String body = ul.group(2);
            Matcher tk = TASK_ITEM.matcher(body);
            if (tk.matches()) {
                boolean done = !" ".equals(tk.group(1));
                return new BlockLine(BlockKind.TASK, lv, indentOf(lv), tk.group(2), null, done);
            }
            return new BlockLine(BlockKind.UL, lv, indentOf(lv), body, null, false);
        }
        Matcher ol = OL_ITEM.matcher(line);
        if (ol.matches()) {
            int lv = indentLevel(ol.group(1));
            return new BlockLine(BlockKind.OL, lv, indentOf(lv), ol.group(3), ol.group(2), false);
        }
        Matcher hr = HR.matcher(line);
        if (hr.matches()) {
            return new BlockLine(BlockKind.HR, 0, "", "", null, false);
        }
        return new BlockLine(BlockKind.PLAIN, 0, "", line, null, false);
    }

    /** 前导空白换算层级：每 2 个空格（或 1 个 tab）算一级，上限 {@link #MAX_LIST_LEVEL} */
    static int indentLevel(String leading) {
        if (leading == null || leading.isEmpty()) return 0;
        int w = 0;
        for (int i = 0; i < leading.length(); i++) {
            w += (leading.charAt(i) == '\t') ? 2 : 1;
        }
        int lv = w / 2;
        return lv > MAX_LIST_LEVEL ? MAX_LIST_LEVEL : lv;
    }

    /** 把层级还原成缩进字符串；层级已在 {@link #indentLevel} 限幅，这里再兜一次 */
    static String indentOf(int level) {
        if (level <= 0) return "";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(level, MAX_LIST_LEVEL);
        for (int i = 0; i < n; i++) sb.append(LIST_INDENT_UNIT);
        return sb.toString();
    }

    private void appendLine(SpannableStringBuilder out, String line) {
        BlockLine b = parseBlockLine(line);
        switch (b.kind) {
            case HEADING: {
                int start = out.length();
                out.append(b.text);
                int end = out.length();
                float size = b.level == 1 ? 1.3f : (b.level == 2 ? 1.2f : 1.1f);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new RelativeSizeSpan(size), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return;
            }
            case QUOTE: {
                int start = out.length();
                out.append(b.indent).append("\u258D").append(b.text);
                int end = out.length();
                out.setSpan(new ForegroundColorSpan(quoteTextColor), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return;
            }
            case UL:
                appendStyledText(out, b.indent + "\u2022 " + b.text);
                return;
            case OL:
                appendStyledText(out, b.indent + b.marker + ". " + b.text);
                return;
            case TASK:
                // [ ] -> ☐（未勾选），[x]/[X] -> ☑（已勾选）；勾选态语义词不再丢失
                appendStyledText(out, b.indent + (b.checked ? "\u2611 " : "\u2610 ") + b.text);
                return;
            case HR:
                appendHr(out);
                return;
            default:
                appendStyledText(out, b.text);
        }
    }

    /** 水平线：一行淡色 box-drawing 横线，作为轻量分隔 */
    private void appendHr(SpannableStringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        int start = out.length();
        out.append(HR_LINE);
        int end = out.length();
        out.setSpan(new ForegroundColorSpan(dividerColor), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    private void appendStyledText(SpannableStringBuilder out, String text) {
        int base = out.length();
        int pos = 0;
        Matcher bm = BOLD.matcher(text);
        while (bm.find()) {
            appendPlain(out, text.substring(pos, bm.start()));
            int s = out.length();
            out.append(bm.group(1));
            out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            pos = bm.end();
        }
        appendPlain(out, text.substring(pos));
        styleInner(out, base, out.length());
    }

    /** 对已经拼入的片段再做行内代码与斜体处理（避免外层嵌套顺序问题） */
    private void styleInner(SpannableStringBuilder out, int from, int to) {
        // 行内链接会改变文本长度，必须先处理，再对处理后的片段做其余行内样式
        applyInlineLinks(out, from);
        CharSequence seg = out.subSequence(from, out.length());
        String s = seg.toString();
        Matcher ic = INLINE_CODE.matcher(s);
        while (ic.find()) {
            int start = from + ic.start();
            int end = from + ic.end();
            out.setSpan(new BackgroundColorSpan(codeBgColor), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(codeTextColor), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new TypefaceSpan("monospace"), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Matcher it = ITALIC.matcher(s);
        while (it.find()) {
            int start = from + it.start(1);
            int end = from + it.end(1);
            out.setSpan(new StyleSpan(Typeface.ITALIC), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        Matcher ur = URL.matcher(s);
        while (ur.find()) {
            int start = from + ur.start();
            int end = from + ur.end();
            out.setSpan(new URLSpan(s.substring(ur.start(), ur.end())), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(linkColor), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /**
     * 行内链接：[文字](url) → 只保留文字并挂 URLSpan + linkColor。
     * 多个链接时从右往左替换，避免左侧偏移错位。
     */
    private void applyInlineLinks(SpannableStringBuilder out, int from) {
        String s = out.subSequence(from, out.length()).toString();
        Matcher il = INLINE_LINK.matcher(s);
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        java.util.List<String[]> parts = new java.util.ArrayList<>();
        while (il.find()) {
            spans.add(new int[]{il.start(), il.end()});
            parts.add(new String[]{il.group(1), il.group(2)});
        }
        for (int k = spans.size() - 1; k >= 0; k--) {
            int ms = from + spans.get(k)[0];
            int me = from + spans.get(k)[1];
            String txt = parts.get(k)[0];
            String url = parts.get(k)[1];
            out.replace(ms, me, txt);
            int ne = ms + txt.length();
            out.setSpan(new URLSpan(url), ms, ne, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(linkColor), ms, ne,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void appendPlain(SpannableStringBuilder out, String s) {
        if (s == null || s.isEmpty()) return;
        out.append(s);
    }
}
