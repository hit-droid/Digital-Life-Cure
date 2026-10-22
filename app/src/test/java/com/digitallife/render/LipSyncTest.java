package com.digitallife.render;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * v1.183.0 口型节奏时间线的契约测试。
 *
 * <p>纯 JVM，不依赖 Android：{@link LipSync} 只做「文本 → 音节时间线」的数学。
 * 重点锁住四件事：能开合（不是恒定张嘴）、标点真的闭嘴、流式追加不会从头重来、
 * 结果确定（同一句话每次口型一致）。</p>
 */
public class LipSyncTest {

    private static final long T0 = 10_000L;

    @Test
    public void emptyTextKeepsMouthClosed() {
        LipSync ls = LipSync.forText("").start(T0);
        assertEquals(0L, ls.durationMs());
        assertEquals(0f, ls.valueAt(T0), 0.0001f);
        assertEquals("空文本不应让嘴张开", 0f, ls.valueAt(T0 + 500), 0.0001f);
    }

    @Test
    public void chineseOneCharOneSyllable() {
        LipSync ls = LipSync.forText("你好呀").start(T0);
        assertEquals(3 * LipSync.SYLLABLE_MS, ls.durationMs());
        // 音节中段应确实张嘴
        assertTrue("音节中段应张嘴", ls.valueAt(T0 + 75) > 0.2f);
        // 播完闭嘴
        assertEquals(0f, ls.valueAt(T0 + ls.durationMs()), 0.0001f);
        assertTrue(ls.isFinished(T0 + ls.durationMs()));
    }

    @Test
    public void punctuationInsertsClosedGap() {
        // 好 (0~150) → 「，」停顿 110 → 啊 (260~410)
        LipSync ls = LipSync.forText("好，啊").start(T0);
        assertEquals(2 * LipSync.SYLLABLE_MS + LipSync.COMMA_PAUSE_MS, ls.durationMs());
        assertEquals("标点停顿处必须闭嘴", 0f, ls.valueAt(T0 + 200), 0.0001f);
        assertTrue("标点之后应继续说话", ls.valueAt(T0 + 300) > 0.2f);
    }

    @Test
    public void latinWordSplitsByVowelGroups() {
        // hello → e / o 两组元音 → 2 音节
        LipSync ls = LipSync.forText("hello").start(T0);
        assertEquals(2 * LipSync.SYLLABLE_MS, ls.durationMs());
    }

    @Test
    public void appendExtendsTimelineWithoutRestart() {
        LipSync ls = LipSync.forText("你好").start(T0);
        long first = ls.durationMs();
        assertEquals(2 * LipSync.SYLLABLE_MS, first);

        ls.append("再说一句");
        assertEquals("追加后时间线应顺延", first + 4 * LipSync.SYLLABLE_MS, ls.durationMs());
        // 追加不应把起点重置：先前的音节仍在原位
        assertTrue(ls.valueAt(T0 + 75) > 0.2f);
        // 追加段落也有口型
        assertTrue(ls.valueAt(T0 + first + 75) > 0.2f);
    }

    @Test
    public void valuesStayInRangeAndAreDeterministic() {
        String text = "今天也要元气满满呀！hello world 123";
        LipSync a = LipSync.forText(text).start(T0);
        LipSync b = LipSync.forText(text).start(T0);
        for (long t = 0; t <= a.durationMs(); t += 10) {
            float va = a.valueAt(T0 + t);
            float vb = b.valueAt(T0 + t);
            assertTrue("口型必须落在 0~1，t=" + t + " v=" + va, va >= 0f && va <= 1f);
            assertEquals("同一文本口型必须确定", va, vb, 0.0001f);
        }
    }

    @Test
    public void notStartedStaysClosed() {
        LipSync ls = LipSync.forText("你好呀");
        assertFalse(ls.isStarted());
        assertEquals(0f, ls.valueAt(T0), 0.0001f);
        assertFalse("未启动不应判定为已播完", ls.isFinished(T0 + 10_000L));
    }

    @Test
    public void leadingPunctuationDoesNotDelayFirstSyllable() {
        LipSync ls = LipSync.forText("……你好").start(T0);
        assertEquals("开头标点不该让嘴闭着干等", 2 * LipSync.SYLLABLE_MS, ls.durationMs());
        assertTrue(ls.valueAt(T0 + 75) > 0.2f);
    }
}
