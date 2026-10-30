package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

/**
 * {@link CronMatcher} 单测（v1.191.0）。
 *
 * <p>{@link CronMatcher} 是 {@code nextFireMillis} 的性能优化（预解析 cron 成布尔表，
 * 把「逐分钟试 52.7 万次」变成「按天跳跃」）。优化的前提是**语义与原判定逐位一致**，
 * 否则「预览的下次触发时间」会骗用户。这里用暴力枚举的方式做差分测试：
 * 对一批 cron × 一批 (分/时/日/月/周) 取值，逐个断言
 * {@code CronMatcher.matches} 与 {@code CareAutomation.cronMatches} 结果相同。</p>
 */
public class CronMatcherTest {

    /** 一批覆盖各种语法的 cron */
    private static final String[] CRONS = {
            "* * * * *",
            "0 9 * * *",
            "*/15 * * * *",
            "*/30 * * * *",
            "0 0,12 * * *",
            "30 8 1 * *",
            "0 9 * * 1",
            "0 9 * * 1-5",
            "0 9 * * 1,3,5",
            "0 9 * * 0",
            "0 9 * * 7",
            "0 9 * * */2",
            "15 10-18 * * *",
            "0 9 15 * *",
            "0 9 1 1 *",
            "*/5 8-18 1,15 * 1-5",
    };

    @Test
    public void parse_nullOrTooFewFieldsReturnsNull() {
        assertNull(CronMatcher.parse(null));
        assertNull(CronMatcher.parse(""));
        assertNull(CronMatcher.parse("0 9 * *"));      // 4 段
        assertNull(CronMatcher.parse("0 9"));           // 2 段
    }

    @Test
    public void parse_validCronIsNotNull() {
        assertNotNull(CronMatcher.parse("0 9 * * *"));
        assertNotNull(CronMatcher.parse("  0   9  *  *  *  "));
    }

    @Test
    public void matches_isBitIdenticalToCronMatches() {
        for (String cron : CRONS) {
            CronMatcher m = CronMatcher.parse(cron);
            assertNotNull("解析失败：" + cron, m);
            for (int minute = 0; minute < 60; minute++) {
                for (int hour = 0; hour < 24; hour += 3) {
                    for (int day = 1; day <= 31; day += 5) {
                        for (int month = 1; month <= 12; month += 3) {
                            for (int dow = 0; dow < 7; dow++) {
                                boolean expected =
                                        CareAutomation.cronMatches(cron, minute, hour, day, month, dow);
                                boolean actual = m.matches(minute, hour, day, month, dow);
                                assertEquals("cron=" + cron + " m=" + minute + " h=" + hour
                                                + " d=" + day + " mo=" + month + " dow=" + dow,
                                        expected, actual);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    public void matchesDate_usesAndSemanticsLikeCronMatches() {
        // 日与周是 AND（不是标准 cron 的 OR）：「每月 1 日 且 周一」才命中
        CronMatcher m = CronMatcher.parse("0 9 1 * 1");
        assertNotNull(m);
        // 2026-06-01 是周一 → 命中；2026-06-02 是周二 → 日不命中
        assertTrue(m.matchesDate(1, 6, 1));
        assertFalse(m.matchesDate(2, 6, 2));
    }

    @Test
    public void illegalFieldMarksNothingAsMatched() {
        // 非法语法 → 整列 false，等价于 cronMatches 抛异常后返回 false
        CronMatcher m = CronMatcher.parse("abc 9 * * *");
        assertNotNull(m);
        assertFalse(m.minuteMatches(0));
        assertFalse(m.matches(0, 9, 1, 1, 0));
    }

    @Test
    public void hourAndMinuteTablesAreIndependent() {
        CronMatcher m = CronMatcher.parse("30 8 * * *");
        assertNotNull(m);
        assertTrue(m.hourMatches(8));
        assertFalse(m.hourMatches(9));
        assertTrue(m.minuteMatches(30));
        assertFalse(m.minuteMatches(31));
    }

    @Test
    public void sundayIsBothZeroAndSeven() {
        CronMatcher zero = CronMatcher.parse("0 9 * * 0");
        CronMatcher seven = CronMatcher.parse("0 9 * * 7");
        assertNotNull(zero);
        assertNotNull(seven);
        // 0 = 周日
        assertTrue(zero.matchesDate(1, 6, 0));
        assertFalse(zero.matchesDate(1, 6, 1));
        // 7 归一化成周日
        assertTrue(seven.matchesDate(1, 6, 0));
        assertFalse(seven.matchesDate(1, 6, 1));
    }

    // ==================== nextFireMillis 新实现 ====================

    private static long at(int year, int month0, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month0, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static Calendar cal(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c;
    }

    @Test
    public void nextFire_monthlyOnFirstDay() {
        long base = at(2026, Calendar.JUNE, 10, 8, 0);
        Calendar got = cal(CronText.nextFireMillis("0 9 1 * *", base));
        assertEquals(Calendar.JULY, got.get(Calendar.MONTH));
        assertEquals(1, got.get(Calendar.DAY_OF_MONTH));
        assertEquals(9, got.get(Calendar.HOUR_OF_DAY));
    }

    @Test
    public void nextFire_every15MinutesFindsNearestSlot() {
        long base = at(2026, Calendar.JUNE, 10, 9, 7);
        Calendar got = cal(CronText.nextFireMillis("*/15 * * * *", base));
        assertEquals(9, got.get(Calendar.HOUR_OF_DAY));
        assertEquals(15, got.get(Calendar.MINUTE));
    }

    @Test
    public void nextFire_hourRangePicksFirstHitAfterStart() {
        // 10:00-18:00 每小时整点：10:30 起 → 11:00
        long base = at(2026, Calendar.JUNE, 10, 10, 30);
        Calendar got = cal(CronText.nextFireMillis("0 10-18 * * *", base));
        assertEquals(11, got.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, got.get(Calendar.MINUTE));
    }

    @Test
    public void nextFire_afterLastHourOfTodayRollsToTomorrow() {
        // 只有 09:00 一个时刻，起点已在 10:00 → 明天
        long base = at(2026, Calendar.JUNE, 10, 10, 0);
        Calendar got = cal(CronText.nextFireMillis("0 9 * * *", base));
        assertEquals(11, got.get(Calendar.DAY_OF_MONTH));
        assertEquals(9, got.get(Calendar.HOUR_OF_DAY));
    }

    @Test
    public void nextFire_unreachableCronReturnsMinusOne() {
        // 2 月 30 日不存在 → 一年内永不触发
        long base = at(2026, Calendar.JANUARY, 1, 0, 0);
        assertEquals(-1, CronText.nextFireMillis("0 9 30 2 *", base));
    }

    @Test
    public void nextFire_isFasterThanMinuteByMinuteScan() {
        // 旧实现逐分钟扫描（最多 52.7 万次切字符串 + parseInt）；新实现按天跳跃。
        // 这里只做「量级」断言：单次推算应在数十毫秒内完成。
        long base = at(2026, Calendar.JUNE, 10, 8, 0);
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) {
            CronText.nextFireMillis("0 9 * * *", base);
        }
        long cost = System.currentTimeMillis() - t0;
        assertTrue("20 次推算耗时 " + cost + "ms，过慢", cost < 2000);
    }
}
