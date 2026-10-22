package com.digitallife.render;

import java.util.ArrayList;
import java.util.List;

/**
 * 说话口型驱动（纯逻辑，JVM 可测，无 Android 依赖）。
 *
 * <h3>为什么要它</h3>
 * <p>系统 TTS 只给「开始说话 / 说完」两个回调，拿不到音频振幅。此前桌宠说话时嘴型是
 * 恒定张开 0.3，整段话纹丝不动，像一张定格图。开源 Live2D 挂件（如 {@code @chnak/l2d-widget}）
 * 的做法是<b>用文本节奏驱动嘴型参数</b>：每个音节张嘴、音节之间闭嘴、标点处停顿——
 * 不需要音频分析也能得到「在说话」的观感。</p>
 *
 * <h3>时间线怎么建</h3>
 * <ul>
 *   <li>中文 / 假名 / 谚文：一字一音节（{@link #SYLLABLE_MS}）；</li>
 *   <li>拉丁字母：按<b>元音组</b>计音节（{@code hello} → 2），单词时长随字母数增长；</li>
 *   <li>数字：一音节；</li>
 *   <li>句末标点 → {@link #PAUSE_MS} 停顿，句中标点 → {@link #COMMA_PAUSE_MS}，空白 → {@link #SPACE_PAUSE_MS}；</li>
 *   <li>每个音节内部是「快速张嘴 → 保持 → 收嘴」的梯形包络，相邻音节之间留一点闭合间隙；</li>
 *   <li>张幅由字符码确定性推导（{@link #MIN_OPEN}~{@link #MAX_OPEN}），同文本永远同一结果。</li>
 * </ul>
 *
 * <p>发音时按 {@link #valueAt(long)} 取当前嘴型即可；{@link #append(String)} 支持流式追加
 * （TTS 队列追加下一段文本时时间线顺延，不会打断当前口型）。</p>
 */
public final class LipSync {

    /** 单音节基准时长（ms） */
    public static final long SYLLABLE_MS = 150L;
    /** 句末标点停顿（ms） */
    public static final long PAUSE_MS = 180L;
    /** 句中标点停顿（ms） */
    public static final long COMMA_PAUSE_MS = 110L;
    /** 空白停顿（ms） */
    public static final long SPACE_PAUSE_MS = 60L;

    /** 最小 / 最大张口幅度（对齐 ParamMouthOpenY 的 0~1） */
    public static final float MIN_OPEN = 0.24f;
    public static final float MAX_OPEN = 0.88f;

    /** 包络：前 20% 张嘴、后 25% 收嘴、中间保持 */
    private static final float ATTACK = 0.2f;
    private static final float RELEASE = 0.25f;

    /** 一个音节：相对时间线起点的 [start, end)，该区间内按包络取 open */
    private static final class Frame {
        final long start;
        final long end;
        final float open;

        Frame(long start, long end, float open) {
            this.start = start;
            this.end = end;
            this.open = open;
        }
    }

    private final List<Frame> frames = new ArrayList<>();
    /** 时间线游标（ms）：下一段的起点，也是整条时间线的总时长 */
    private long cursor = 0L;
    /** 未开始为 Long.MIN_VALUE */
    private long startAt = Long.MIN_VALUE;

    private LipSync() {
    }

    /** 按文本构建时间线（未启动，需再调 {@link #start(long)}） */
    public static LipSync forText(String text) {
        return new LipSync().append(text);
    }

    /** 启动时间线：{@code nowMs} 用 {@code SystemClock.uptimeMillis()} 之类单调时钟 */
    public synchronized LipSync start(long nowMs) {
        this.startAt = nowMs;
        return this;
    }

    /** 追加文本（TTS 队列续播时用），时间线从当前游标继续，不打断已有口型 */
    public synchronized LipSync append(String text) {
        if (text == null || text.isEmpty()) return this;
        final int n = text.length();
        int i = 0;
        while (i < n) {
            int cp = text.codePointAt(i);
            int len = Character.charCount(cp);

            if (isSyllabic(cp)) {
                // 中文 / 假名 / 谚文 / 数字：一字一音节
                addPulse(cursor, SYLLABLE_MS, amplitudeOf(cp));
                cursor += SYLLABLE_MS;
                i += len;
                continue;
            }
            if (isLatinLetter(cp)) {
                int j = i;
                while (j < n) {
                    int c = text.codePointAt(j);
                    if (!isLatinLetter(c)) break;
                    j += Character.charCount(c);
                }
                String word = text.substring(i, j);
                int syllables = Math.max(1, countVowelGroups(word));
                // 长单词按字母数拉长，避免二十分钟才读完一个词
                long total = Math.max(SYLLABLE_MS, (long) word.length() * 60L);
                long each = Math.max(1L, total / syllables);
                for (int k = 0; k < syllables; k++) {
                    int idx = k * word.length() / syllables;
                    addPulse(cursor, each, amplitudeOf(word.charAt(idx)));
                    cursor += each;
                }
                i = j;
                continue;
            }
            if (isSentenceEnd(cp)) {
                addPause(PAUSE_MS);
            } else if (isClauseBreak(cp)) {
                addPause(COMMA_PAUSE_MS);
            } else if (Character.isWhitespace(cp)) {
                addPause(SPACE_PAUSE_MS);
            }
            i += len;
        }
        return this;
    }

    /**
     * 当前嘴型（0~1）。未启动、已结束、停顿区间、以及「还没到下一个音节」都返回 0。
     *
     * @param nowMs 与 {@link #start(long)} 同一时钟
     */
    public synchronized float valueAt(long nowMs) {
        if (startAt == Long.MIN_VALUE) return 0f;
        final long t = nowMs - startAt;
        if (t < 0L || t >= cursor) return 0f;

        int lo = 0;
        int hi = frames.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            Frame f = frames.get(mid);
            if (t < f.start) {
                hi = mid - 1;
            } else if (t >= f.end) {
                lo = mid + 1;
            } else {
                return envelope(f, t);
            }
        }
        return 0f; // 落在音节之间的闭合间隙 / 标点停顿
    }

    /** 整条时间线时长（ms），支持追加后增长 */
    public synchronized long durationMs() {
        return cursor;
    }

    /** 是否已播完（含「还没启动」视为未播完，避免误判） */
    public synchronized boolean isFinished(long nowMs) {
        if (startAt == Long.MIN_VALUE) return false;
        return nowMs - startAt >= cursor;
    }

    public synchronized boolean isStarted() {
        return startAt != Long.MIN_VALUE;
    }

    // ================= 内部 =================

    private static float envelope(Frame f, long t) {
        long len = Math.max(1L, f.end - f.start);
        float u = (t - f.start) / (float) len;
        if (u < ATTACK) return f.open * (u / ATTACK);
        if (u > 1f - RELEASE) return f.open * ((1f - u) / RELEASE);
        return f.open;
    }

    /** 加一个音节；末尾留一点闭合间隙，避免相邻音节粘连 */
    private void addPulse(long start, long duration, float open) {
        if (duration <= 0) return;
        long gap = Math.min(20L, duration / 6L);
        long end = start + duration - gap;
        if (end <= start) end = start + duration;
        frames.add(new Frame(start, end, clamp(open)));
    }

    /** 标点 / 空白停顿；句首的停顿没有意义，忽略以免开头闭着嘴干等 */
    private void addPause(long ms) {
        if (cursor > 0L) cursor += ms;
    }

    /** 字符码 → 张幅：确定性、无需随机数，同文本永远同一口型 */
    private static float amplitudeOf(int cp) {
        int h = cp * 1103515245 + 12345;
        float r = (h & 0x1F) / 31f;
        return MIN_OPEN + r * (MAX_OPEN - MIN_OPEN);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static boolean isSyllabic(int cp) {
        return isCjk(cp) || (cp >= '0' && cp <= '9') || (cp >= 0xFF10 && cp <= 0xFF19);
    }

    /** 汉字 / 假名 / 谚文：这些语言基本「一字一音节」 */
    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)     // 汉字基本区
                || (cp >= 0x3400 && cp <= 0x4DBF) // 汉字扩展 A
                || (cp >= 0x3040 && cp <= 0x30FF) // 平假名 / 片假名
                || (cp >= 0xAC00 && cp <= 0xD7AF); // 谚文
    }

    private static boolean isLatinLetter(int cp) {
        return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z')
                || (cp >= 0xC0 && cp <= 0x24F); // 带变音符的拉丁字母
    }

    /** 元音组数量 ≈ 音节数（hello → e / o = 2） */
    private static int countVowelGroups(String word) {
        int groups = 0;
        boolean prevVowel = false;
        for (int i = 0; i < word.length(); i++) {
            boolean vowel = isVowel(word.charAt(i));
            if (vowel && !prevVowel) groups++;
            prevVowel = vowel;
        }
        return groups;
    }

    private static boolean isVowel(char c) {
        switch (Character.toLowerCase(c)) {
            case 'a': case 'e': case 'i': case 'o': case 'u': case 'y':
                return true;
            default:
                return false;
        }
    }

    private static boolean isSentenceEnd(int cp) {
        return cp == '。' || cp == '！' || cp == '？' || cp == '.' || cp == '!'
                || cp == '?' || cp == '…' || cp == '\n' || cp == '；' || cp == ';';
    }

    private static boolean isClauseBreak(int cp) {
        return cp == '，' || cp == '、' || cp == ',' || cp == '：' || cp == ':'
                || cp == '—' || cp == '~' || cp == '～';
    }
}
