package com.digitallife.ui;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
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
    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.+)$");
    private static final Pattern UL_ITEM = Pattern.compile("^[-*]\\s+(.+)$");
    private static final Pattern OL_ITEM = Pattern.compile("^(\\d+)[.、]\\s+(.+)$");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^>\\s?(.+)$");
    private static final Pattern URL = Pattern.compile("https?://[^\\s)\\]<>]+");

    private final int codeBgColor;
    private final int codeTextColor;
    private final int quoteTextColor;
    private final int baseTextColor;
    private final int linkColor;

    public MarkdownRenderer(int codeBgColor, int codeTextColor,
                            int quoteTextColor, int baseTextColor, int linkColor) {
        this.codeBgColor = codeBgColor;
        this.codeTextColor = codeTextColor;
        this.quoteTextColor = quoteTextColor;
        this.baseTextColor = baseTextColor;
        this.linkColor = linkColor;
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
                appendTable(out, lines, i, end);
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

    /** v1.46.0：把表格块渲染成等宽对齐文本，表头加粗 */
    private void appendTable(SpannableStringBuilder out, String[] lines, int from, int to) {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (int r = from; r < to; r++) {
            if (isSeparatorRow(lines[r])) continue;   // 分隔行不展示，靠对齐自然成表
            // v1.124.0：先剥掉行内标记再算列宽，否则 ** 之类标记会白占宽度、把对齐撑歪
            rows.add(stripInlineCells(splitRow(lines[r])));
        }
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
        for (int i = 0; i < cols; i++) widths[i] = java.lang.Math.min(widths[i], 24);

        int start = out.length();
        for (int r = 0; r < rows.size(); r++) {
            String[] c = rows.get(r);
            for (int i = 0; i < cols; i++) {
                String cell = i < c.length ? c[i] : "";
                if (displayWidth(cell) > widths[i]) cell = truncateCell(cell, widths[i]);
                out.append(padCell(cell, widths[i]));
                if (i < cols - 1) out.append("  ");
            }
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
        // [文字](链接) -> 文字
        t = t.replaceAll("\\[([^\\]]*)\\]\\(([^)]*)\\)", "$1");
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

    private static String truncateCell(String s, int width) {
        if (displayWidth(s) <= width) return s;
        if (width <= 1) return "\u2026";
        StringBuilder sb = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int cw = isWide(c) ? 2 : 1;
            if (w + cw > width - 1) break;
            sb.append(c);
            w += cw;
        }
        sb.append('\u2026');
        return sb.toString();
    }

    private void appendLine(SpannableStringBuilder out, String line) {
        Matcher h = HEADING.matcher(line);
        if (h.matches()) {
            int level = h.group(1).length();
            int start = out.length();
            out.append(h.group(2));
            int end = out.length();
            out.setSpan(new StyleSpan(Typeface.BOLD), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            float size = level == 1 ? 1.3f : (level == 2 ? 1.2f : 1.1f);
            out.setSpan(new RelativeSizeSpan(size), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }
        Matcher bq = BLOCKQUOTE.matcher(line);
        if (bq.matches()) {
            int start = out.length();
            out.append("▍").append(bq.group(1));
            int end = out.length();
            out.setSpan(new ForegroundColorSpan(quoteTextColor), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }
        Matcher ul = UL_ITEM.matcher(line);
        if (ul.matches()) {
            appendStyledText(out, "• " + ul.group(1));
            return;
        }
        Matcher ol = OL_ITEM.matcher(line);
        if (ol.matches()) {
            appendStyledText(out, ol.group(1) + ". " + ol.group(2));
            return;
        }
        appendStyledText(out, line);
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
        CharSequence seg = out.subSequence(from, to);
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

    private void appendPlain(SpannableStringBuilder out, String s) {
        if (s == null || s.isEmpty()) return;
        out.append(s);
    }
}
