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
    /**
     * v1.141.0（#44）：行内链接 {@code [文字](url)}。
     * 正文渲染与 {@link #stripInline} 共用这一份正则，避免两处各写一套走样。
     * label 要求非空；url 里不许出现空白，避免把后续正文一起吞进链接。
     */
    static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)");
    /**
     * v1.141.0（#44）：勾选框 {@code [ ]} / {@code [x]} / {@code [X]}。
     * 必须**后接空格**（或有内容前至少一个空格），否则 {@code [文字]} 会被误判成任务列表。
     */
    private static final Pattern TASK_ITEM = Pattern.compile("^\\[([ xX])\\](?:\\s+(.*))?$");
    /**
     * v1.141.0（#44）：水平线，独立成行的 3 个及以上 {@code -} / {@code *} / {@code _}。
     * 表格分隔行要求含 {@code |}，与此不冲突。
     */
    private static final Pattern HR = Pattern.compile("^\\s*([-*_])\\1{2,}\\s*$");

    /** v1.141.0（#44）：水平线渲染串（制表符，等宽下连成一条淡色分隔线） */
    static final String DIVIDER_CHAR = "\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500";

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
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        for (int r = from; r < to; r++) {
            if (isSeparatorRow(lines[r])) continue;   // 分隔行不展示，靠对齐自然成表
            // v1.125.0：先剥掉行内标记再算列宽，否则 ** 之类标记会白占宽度、把对齐撑歪
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
        // [文字](链接) -> 文字（与正文渲染共用 LINK，避免两处正则走样）
        t = LINK.matcher(t).replaceAll("$1");
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
        /** v1.141.0（#44）：TASK 的勾选态；其余类型恒为 false */
        final boolean checked;

        BlockLine(BlockKind kind, int level, String indent, String text, String marker) {
            this(kind, level, indent, text, marker, false);
        }

        BlockLine(BlockKind kind, int level, String indent, String text, String marker,
                  boolean checked) {
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
        if (line == null) return new BlockLine(BlockKind.PLAIN, 0, "", "", null);

        Matcher h = HEADING.matcher(line);
        if (h.matches()) {
            return new BlockLine(BlockKind.HEADING, h.group(2).length(), "", h.group(3), null);
        }
        Matcher bq = BLOCKQUOTE.matcher(line);
        if (bq.matches()) {
            int lv = indentLevel(bq.group(1));
            return new BlockLine(BlockKind.QUOTE, lv, indentOf(lv), bq.group(2), null);
        }
        // 水平线要在 UL 之前判：`---` 不含空格，本就不会命中 UL，
        // 但 `***` / `___` 之类先挡掉更省心。
        if (HR.matcher(line).matches()) {
            return new BlockLine(BlockKind.HR, 0, "", "", null);
        }
        Matcher ul = UL_ITEM.matcher(line);
        if (ul.matches()) {
            int lv = indentLevel(ul.group(1));
            // v1.141.0（#44）：`- [ ] 待办` / `- [x] 已完成` → TASK，
            // 只有 `[ ]`/`[x]` 且后接空格才算勾选框，普通 `[文字]` 仍是 UL 正文。
            Matcher task = TASK_ITEM.matcher(ul.group(2));
            if (task.matches()) {
                boolean checked = task.group(1).charAt(0) != ' ';
                String body = task.group(2) == null ? "" : task.group(2);
                return new BlockLine(BlockKind.TASK, lv, indentOf(lv), body, null, checked);
            }
            return new BlockLine(BlockKind.UL, lv, indentOf(lv), ul.group(2), null);
        }
        Matcher ol = OL_ITEM.matcher(line);
        if (ol.matches()) {
            int lv = indentLevel(ol.group(1));
            return new BlockLine(BlockKind.OL, lv, indentOf(lv), ol.group(3), ol.group(2));
        }
        return new BlockLine(BlockKind.PLAIN, 0, "", line, null);
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
            case TASK:
                // v1.141.0（#44）：勾选框语义词不能丢，用 ☐ / ☑ 体现勾选态
                appendStyledText(out, b.indent + (b.checked ? "\u2611 " : "\u2610 ") + b.text);
                return;
            case OL:
                appendStyledText(out, b.indent + b.marker + ". " + b.text);
                return;
            case HR: {
                // v1.141.0（#44）：独立成行的 --- 渲染成一条淡色分隔线
                int start = out.length();
                out.append(DIVIDER_CHAR);
                int end = out.length();
                out.setSpan(new ForegroundColorSpan(dividerColor), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return;
            }
            default:
                appendStyledText(out, b.text);
        }
    }

    private void appendStyledText(SpannableStringBuilder out, String text) {
        int base = out.length();
        int pos = 0;
        Matcher bm = BOLD.matcher(text);
        while (bm.find()) {
            appendPlain(out, text.substring(pos, bm.start()));
            int s = out.length();
            // v1.141.0（#44）：加粗内容也走 appendPlain，否则 **[文字](url)** 里的
            // 链接语法会被原样拼进来（这里原先直接 out.append(group(1))）。
            appendPlain(out, bm.group(1));
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

    /**
     * v1.141.0（#44）：拼入纯文本，同时把 {@code [文字](url)} 渲染成「只显示文字」的可点链接。
     *
     * <p>在这里做而不是 {@link #styleInner}：链接语法本身要**从显示文本里去掉**
     * （只留文字），属于「改文本」而不是「加 Span」，塞进 styleInner 会打乱已有 offset。
     * 放在最内层拼入处，加粗里套链接（{@code **[文字](url)**}）也一并覆盖。</p>
     */
    private void appendPlain(SpannableStringBuilder out, String s) {
        if (s == null || s.isEmpty()) return;
        int pos = 0;
        Matcher m = LINK.matcher(s);
        while (m.find()) {
            out.append(s, pos, m.start());
            int start = out.length();
            String label = m.group(1);
            out.append(label);
            int end = out.length();
            if (end > start) {
                out.setSpan(new URLSpan(m.group(2)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(linkColor), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            pos = m.end();
        }
        out.append(s, pos, s.length());
    }
}
