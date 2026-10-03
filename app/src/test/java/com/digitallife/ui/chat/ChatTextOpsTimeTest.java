package com.digitallife.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;

/**
 * {@link ChatTextOps#formatBubbleTime} 的回归（AGENTS.md 5.4 第 1 条）。
 *
 * 背景：气泡上原先没有时间，只有间隔 5 分钟才出现一次的时间分隔线，
 * 想知道某条消息几点发的只能长按。这里给每条气泡补一个 {@code HH:mm}。
 *
 * 期望值用 {@link Calendar} 现算而不是写死字符串，免得被单测机的时区带偏。
 */
public class ChatTextOpsTimeTest {

    private static final long TS = 1730000000000L;

    private static String expected(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ts);
        return String.format(Locale.ROOT, "%02d:%02d",
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    @Test
    public void formatBubbleTime_hourAndMinute() {
        assertEquals(expected(TS), ChatTextOps.formatBubbleTime(TS));
    }

    @Test
    public void formatBubbleTime_zeroPadsHourAndMinute() {
        // 09:05 这类早上时间必须补零，不能渲染成 "9:5"
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 5);
        long ts = c.getTimeInMillis();
        assertEquals(expected(ts), ChatTextOps.formatBubbleTime(ts));
        assertEquals("09:05", ChatTextOps.formatBubbleTime(ts));
    }

    @Test
    public void formatBubbleTime_noDateAndNoSecond() {
        String s = ChatTextOps.formatBubbleTime(TS);
        assertEquals(5, s.length());
        assertTrue("气泡时间只该是 HH:mm，实际是 " + s, s.matches("\\d{2}:\\d{2}"));
    }

    @Test
    public void formatBubbleTime_nonPositiveFallsBackToNow() {
        // ts <= 0 视作「当前时间」：老数据可能没有时间戳，不能抛异常也不能返回空
        assertTrue(ChatTextOps.formatBubbleTime(0L).matches("\\d{2}:\\d{2}"));
        assertTrue(ChatTextOps.formatBubbleTime(-1L).matches("\\d{2}:\\d{2}"));
    }

    @Test
    public void formatBubbleTime_explicitLocaleMatchesDefault() {
        assertEquals(ChatTextOps.formatBubbleTime(TS),
                ChatTextOps.formatBubbleTime(TS, Locale.getDefault()));
    }
}
