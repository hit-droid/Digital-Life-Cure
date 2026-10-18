package com.digitallife.care;

import java.util.Calendar;

/**
 * cron 表达式的「人话化」与「下次触发」推算。
 *
 * <p>背景（v1.168.0）：工作台能看 / 跑 / 暂停 / 删定时任务，但行里只有一条生 cron
 * （{@code 0 9 * * *}）——用户看不出它到底什么时候会触发；新建定时任务也需要一个
 * 「这么填会发生什么」的即时预览。本类把 cron 翻成人话（「每天 09:00」），并按
 * {@link CareAutomation#cronMatches} **同一套语义**推算下一次触发时间。</p>
 *
 * <p>字段语法与匹配引擎保持一致：通配、步进（星号斜杠 n）、具体值、{@code a-b} 范围、
 * 逗号列表；五个字段为「分 时 日 月 周」（周 0/7=周日、1=周一）。纯函数，无副作用。</p>
 */
public final class CronText {

    /** 推算上限：约一年（超出视为「近一年内不触发」） */
    private static final int MAX_LOOKAHEAD_MINUTES = 366 * 24 * 60;

    private CronText() {
    }

    /** 五字段 cron 是否合法（字段数 + 语法 + 取值范围） */
    public static boolean isValid(String cron) {
        if (cron == null) return false;
        String[] p = cron.trim().split("\\s+");
        if (p.length != 5) return false;
        try {
            validateField(p[0], 0, 59);
            validateField(p[1], 0, 23);
            validateField(p[2], 1, 31);
            validateField(p[3], 1, 12);
            validateField(p[4], 0, 7);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 人话描述；非法 cron 返回可读的「无法识别」提示，不会抛异常 */
    public static String describe(String cron) {
        if (!isValid(cron)) return "无法识别的 cron：" + (cron == null ? "" : cron.trim());
        String[] p = cron.trim().split("\\s+");
        String min = p[0], hour = p[1], day = p[2], mon = p[3], dow = p[4];
        String monthPrefix = mon.equals("*") ? "" : "每年 " + monText(mon) + " 月 ";

        // 「时:分」句式：带上日 / 周限定词（默认「每天」）
        if (isNum(min) && isNum(hour)) {
            String time = pad(hour) + ":" + pad(min);
            return monthPrefix + dayQualifier(day, dow) + " " + time;
        }

        // 频次句式（每分钟 / 每小时 / 每 N 分钟 / 每 N 小时）：日 / 周无约束时不重复「每天」
        String base;
        if (min.equals("*") && hour.equals("*")) {
            base = "每分钟";
        } else if (min.startsWith("*/") && hour.equals("*")) {
            base = "每 " + min.substring(2) + " 分钟";
        } else if (isNum(min) && hour.equals("*")) {
            base = min.equals("0") ? "每小时整点" : "每小时第 " + Integer.parseInt(min) + " 分";
        } else if (min.equals("0") && hour.startsWith("*/")) {
            base = "每 " + hour.substring(2) + " 小时整点";
        } else if (isNum(min) && hour.startsWith("*/")) {
            base = "每 " + hour.substring(2) + " 小时第 " + Integer.parseInt(min) + " 分";
        } else {
            return "自定义计划（" + cron.trim() + "）";
        }
        boolean constrained = !day.equals("*") || !dow.equals("*");
        String q = constrained ? dayQualifier(day, dow) + " " : "";
        return monthPrefix + q + base;
    }

    /**
     * 从 {@code fromMillis} 之后的第一次触发（毫秒，取整到分钟）。
     * 一年内无匹配返回 {@code -1}；cron 非法同样返回 {@code -1}。
     */
    public static long nextFireMillis(String cron, long fromMillis) {
        if (!isValid(cron)) return -1;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(fromMillis);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        c.add(Calendar.MINUTE, 1); // 下一次触发严格晚于 from
        for (int i = 0; i < MAX_LOOKAHEAD_MINUTES; i++) {
            if (matches(c, cron)) return c.getTimeInMillis();
            c.add(Calendar.MINUTE, 1);
        }
        return -1;
    }

    // ==================== 内部 ====================

    private static boolean matches(Calendar c, String cron) {
        int minute = c.get(Calendar.MINUTE);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        int day = c.get(Calendar.DAY_OF_MONTH);
        int month = c.get(Calendar.MONTH) + 1;
        int dow = c.get(Calendar.DAY_OF_WEEK);
        int cronDow = dow == Calendar.SUNDAY ? 0 : dow - 1;
        return CareAutomation.cronMatches(cron, minute, hour, day, month, cronDow);
    }

    private static void validateField(String field, int min, int max) {
        if (field.equals("*")) return;
        for (String raw : field.split(",")) {
            String part = raw.trim();
            if (part.isEmpty()) throw new IllegalArgumentException("empty");
            if (part.startsWith("*/")) {
                int step = intOf(part.substring(2));
                if (step <= 0 || step > max) throw new IllegalArgumentException("step");
            } else if (part.contains("-")) {
                String[] r = part.split("-");
                if (r.length != 2) throw new IllegalArgumentException("range");
                int lo = intOf(r[0]);
                int hi = intOf(r[1]);
                range(lo, min, max);
                range(hi, min, max);
                if (lo > hi) throw new IllegalArgumentException("range-order");
            } else {
                range(intOf(part), min, max);
            }
        }
    }

    private static int intOf(String s) {
        return Integer.parseInt(s.trim());
    }

    private static void range(int v, int min, int max) {
        if (v < min || v > max) throw new IllegalArgumentException("out of range");
    }

    private static String dayQualifier(String day, String dow) {
        if (!dow.equals("*")) return "每" + dowText(dow);
        if (!day.equals("*")) {
            if (isNum(day)) return "每月 " + Integer.parseInt(day) + " 日";
            return "每月 " + day + " 日";
        }
        return "每天";
    }

    private static String dowText(String dow) {
        if (dow.contains(",")) {
            StringBuilder sb = new StringBuilder();
            for (String s : dow.split(",")) {
                if (sb.length() > 0) sb.append("、");
                sb.append(dowName(s.trim()));
            }
            return "周" + sb;
        }
        if (dow.contains("-")) {
            String[] r = dow.split("-");
            return "周" + dowName(r[0].trim()) + "至周" + dowName(r[1].trim());
        }
        if (dow.startsWith("*/")) return "周（每 " + dow.substring(2) + " 天）";
        return "周" + dowName(dow);
    }

    private static String dowName(String v) {
        switch (v) {
            case "0":
            case "7":
                return "日";
            case "1":
                return "一";
            case "2":
                return "二";
            case "3":
                return "三";
            case "4":
                return "四";
            case "5":
                return "五";
            case "6":
                return "六";
            default:
                return v;
        }
    }

    private static String monText(String mon) {
        return isNum(mon) ? String.valueOf(Integer.parseInt(mon)) : mon;
    }

    private static boolean isNum(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static String pad(String v) {
        int n = Integer.parseInt(v);
        return n < 10 ? "0" + n : String.valueOf(n);
    }
}
