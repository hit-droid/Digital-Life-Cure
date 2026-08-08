package com.digitallife.ui;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.graphics.Typeface;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 Markdown 渲染：标题/加粗/斜体/行内代码/代码块/无序与有序列表。
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
        }
        out.append('\n');
    }

    private void appendInline(SpannableStringBuilder out, String text) {
        if (text == null || text.isEmpty()) return;
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            appendLine(out, lines[i]);
            if (i < lines.length - 1) out.append('\n');
        }
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
    }

    private void appendPlain(SpannableStringBuilder out, String s) {
        if (s == null || s.isEmpty()) return;
        out.append(s);
    }
}
