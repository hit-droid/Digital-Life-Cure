package com.digitallife.care;

/**
 * 预解析后的 cron 匹配器（v1.191.0）。
 *
 * <p>背景：{@link CareAutomation#cronMatches(String, int, int, int, int, int)} 每次调用都要
 * {@code cron.trim().split("\\s+")} 重新切字段，再对每个字段做一次 {@code Integer.parseInt}。
 * 而 {@link CronText#nextFireMillis} 为了找下一次触发，会以「分钟」为单位逐分钟试匹配，
 * 最多 366×24×60 ≈ 52.7 万次——也就是 52.7 万次切字符串 + 上百万次 parseInt。
 * 它偏偏又在 UI 线程被调用（工作台列表每行一次、新建任务表单实时预览）→ 明显卡顿。</p>
 *
 * <p>本类把字段**预解析**成布尔表（分 60 项、时 24 项、日 32 项、月 13 项、周 7 项），
 * 之后每次匹配只是一次数组取值。</p>
 *
 * <p>语义保证：布尔表里的每一项都是**用原判定函数逐值求出来的**
 * （{@link CareAutomation#fieldMatch} / {@link CareAutomation#dowFieldMatch}），
 * 所以与 {@code cronMatches} 的判定结果逐位一致，不存在「两套 cron 语义」的风险。
 * 原函数抛异常时（字段语法非法）该项记为 false，等价于原实现的「整次匹配返回 false」。</p>
 */
final class CronMatcher {

    /** 分钟位（0-59） */
    private final boolean[] minuteField = new boolean[60];
    /** 小时位（0-23） */
    private final boolean[] hourField = new boolean[24];
    /** 日位（1-31，下标 0 不用） */
    private final boolean[] dayField = new boolean[32];
    /** 月位（1-12，下标 0 不用） */
    private final boolean[] monthField = new boolean[13];
    /** 周位（0=周日 … 6=周六） */
    private final boolean[] dowField = new boolean[7];

    private CronMatcher() {
    }

    /**
     * 解析 cron 表达式。
     *
     * @return 匹配器；{@code cron} 为 null 或字段不足 5 段时返回 {@code null}（视为永不匹配）
     */
    static CronMatcher parse(String cron) {
        if (cron == null) return null;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length < 5) return null;
        CronMatcher m = new CronMatcher();
        for (int v = 0; v <= 59; v++) m.minuteField[v] = safeField(parts[0], v);
        for (int v = 0; v <= 23; v++) m.hourField[v] = safeField(parts[1], v);
        for (int v = 1; v <= 31; v++) m.dayField[v] = safeField(parts[2], v);
        for (int v = 1; v <= 12; v++) m.monthField[v] = safeField(parts[3], v);
        for (int v = 0; v <= 6; v++) m.dowField[v] = safeDow(parts[4], v);
        return m;
    }

    /** 单个字段求值（语法非法时该项记 false，与原实现的异常兜底一致） */
    private static boolean safeField(String field, int value) {
        try {
            return CareAutomation.fieldMatch(field, value);
        } catch (Exception e) {
            return false;
        }
    }

    /** 周字段求值 */
    private static boolean safeDow(String field, int dow) {
        try {
            return CareAutomation.dowFieldMatch(field, dow);
        } catch (Exception e) {
            return false;
        }
    }

    /** 完整匹配（分 / 时 / 日 / 月 / 周全中） */
    boolean matches(int minute, int hour, int day, int month, int dow) {
        return matchesDate(day, month, dow) && hourField[hour] && minuteField[minute];
    }

    /**
     * 日期部分匹配（日 / 月 / 周）。
     *
     * <p>注意：日与周之间是 **AND**（与 {@code cronMatches} 一致，与标准 cron 的 OR 不同）。
     * 这里刻意保持一致——改成 OR 会让已有定时任务的触发条件变化。</p>
     */
    boolean matchesDate(int day, int month, int dow) {
        return monthField[month] && dayField[day] && dowField[dow];
    }

    /** 小时是否命中 */
    boolean hourMatches(int hour) {
        return hourField[hour];
    }

    /** 分钟是否命中 */
    boolean minuteMatches(int minute) {
        return minuteField[minute];
    }
}
