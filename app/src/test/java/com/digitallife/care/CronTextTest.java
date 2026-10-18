package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Calendar;

/**
 * {@link CronText} 单测（v1.168.0）。
 *
 * <p>工作台把「生 cron」翻成人话并预告下次触发，新建定时任务表单也用它做实时预览——
 * 翻错 / 算错会让用户对「这条任务什么时候会跑」产生错误预期。这里锁住 {@code describe}
 * 的常见句式与 {@code nextFireMillis} 的边界（严格晚于起点、跨天、按周、每 N 分钟、
 * 非法 cron 不误触发）。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CronTextTest {

    private static long at(int y, int month0, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, month0, day, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static Calendar cal(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c;
    }

    // ============ describe ============

    @Test
    public void describe_dailyAtNine() {
        assertEquals("每天 09:00", CronText.describe("0 9 * * *"));
    }

    @Test
    public void describe_weekly() {
        assertEquals("每周一 09:00", CronText.describe("0 9 * * 1"));
        assertEquals("每周一至周五 09:00", CronText.describe("0 9 * * 1-5"));
    }

    @Test
    public void describe_everyNHoursAndMinutes() {
        assertEquals("每小时整点", CronText.describe("0 * * * *"));
        assertEquals("每小时第 30 分", CronText.describe("30 * * * *"));
        assertEquals("每 30 分钟", CronText.describe("*/30 * * * *"));
        assertEquals("每分钟", CronText.describe("* * * * *"));
    }

    @Test
    public void describe_monthlyOnDay() {
        assertEquals("每月 1 日 08:30", CronText.describe("30 8 1 * *"));
    }

    @Test
    public void describe_invalidIsReadable() {
        assertTrue(CronText.describe("not a cron").startsWith("无法识别"));
        assertTrue(CronText.describe("0 9 * *").startsWith("无法识别")); // 只有 4 段
    }

    // ============ isValid ============

    @Test
    public void isValid_rejectsOutOfRangeAndBadGrammar() {
        assertTrue(CronText.isValid("0 9 * * *"));
        assertTrue(CronText.isValid("*/15 8-18 * * 1,3,5"));
        assertFalse(CronText.isValid(null));
        assertFalse(CronText.isValid("60 9 * * *"));   // 分越界
        assertFalse(CronText.isValid("0 24 * * *"));   // 时越界
        assertFalse(CronText.isValid("0 9 0 * *"));    // 日从 1 起
        assertFalse(CronText.isValid("0 9 * 13 *"));   // 月越界
        assertFalse(CronText.isValid("a b c d e"));
    }

    // ============ nextFireMillis ============

    @Test
    public void nextFire_strictlyAfterStart() {
        // 正好在 09:00 这一分钟：下一次应是次日 09:00，不能是「现在」
        long base = at(2026, Calendar.JUNE, 10, 9, 0);
        long next = CronText.nextFireMillis("0 9 * * *", base);
        Calendar got = cal(next);
        assertEquals(Calendar.JUNE, got.get(Calendar.MONTH));
        assertEquals(11, got.get(Calendar.DAY_OF_MONTH));
        assertEquals(9, got.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, got.get(Calendar.MINUTE));
    }

    @Test
    public void nextFire_sameDayWhenBeforeTrigger() {
        long base = at(2026, Calendar.JUNE, 10, 7, 15);
        Calendar got = cal(CronText.nextFireMillis("0 9 * * *", base));
        assertEquals(10, got.get(Calendar.DAY_OF_MONTH));
        assertEquals(9, got.get(Calendar.HOUR_OF_DAY));
        assertEquals(0, got.get(Calendar.MINUTE));
    }

    @Test
    public void nextFire_everyNMinutes() {
        long base = at(2026, Calendar.JUNE, 10, 9, 0);
        Calendar got = cal(CronText.nextFireMillis("*/30 * * * *", base));
        assertEquals(9, got.get(Calendar.HOUR_OF_DAY));
        assertEquals(30, got.get(Calendar.MINUTE));
    }

    @Test
    public void nextFire_weekly() {
        // 2026-06-10 是周三 → 下一个周一
        long base = at(2026, Calendar.JUNE, 10, 8, 0);
        Calendar got = cal(CronText.nextFireMillis("0 9 * * 1", base));
        assertEquals(Calendar.MONDAY, got.get(Calendar.DAY_OF_WEEK));
        assertEquals(9, got.get(Calendar.HOUR_OF_DAY));
        assertEquals(15, got.get(Calendar.DAY_OF_MONTH)); // 6/15
    }

    @Test
    public void nextFire_invalidCronReturnsMinusOne() {
        assertEquals(-1, CronText.nextFireMillis("0 9 * *", at(2026, Calendar.JUNE, 10, 8, 0)));
    }
}
