package com.digitallife.ui.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * 聊天气泡相关的纯文本处理：折叠提示/中断角标的剥离、工具调用 JSON 解析、
 * 参数 JSON 美化、时间分隔符格式化。
 *
 * <p>这些逻辑原先压在 ChatActivity（2600+ 行）里没法单测，抽出来后可直接回归。
 * 本类不依赖任何 Android API，可在普通 JVM 单测中运行。
 */
public final class ChatTextOps {

    /** 中断角标；复制/朗读时不应带出 */
    public static final String INTERRUPT_MARK = "  ⏹ 已中断";

    private static final String COLLAPSE_EXPAND = "\n\n▸ 展开全文";
    private static final String COLLAPSE_COLLAPSE = "\n\n▾ 收起";

    private ChatTextOps() {
    }

    /** 移除折叠提示后缀（▸ 展开全文 / ▾ 收起）与末尾的中断角标 */
    public static String stripCollapseHint(String text) {
        if (text == null) return "";
        String t = text;
        int i = t.lastIndexOf(COLLAPSE_EXPAND);
        if (i >= 0) t = t.substring(0, i);
        i = t.lastIndexOf(COLLAPSE_COLLAPSE);
        if (i >= 0) t = t.substring(0, i);
        if (t.endsWith(INTERRUPT_MARK)) {
            t = t.substring(0, t.length() - INTERRUPT_MARK.length());
        }
        return t;
    }

    /** 把工具参数 JSON 缩进美化后展示；非 JSON 原样返回 */
    public static String prettyJson(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = s.trim();
        try {
            if (t.startsWith("{")) return new JSONObject(t).toString(2);
            if (t.startsWith("[")) return new JSONArray(t).toString(2);
        } catch (Exception ignored) {
        }
        return s;
    }

    /** 从 tool_calls JSON 中取首个函数名；解析失败回退 {@code "tool"} */
    public static String parseToolName(String toolCallsJson) {
        try {
            JSONArray arr = new JSONArray(toolCallsJson);
            if (arr.length() > 0) {
                JSONObject call = arr.optJSONObject(0);
                if (call != null && call.optJSONObject("function") != null) {
                    String n = call.optJSONObject("function").optString("name", "");
                    if (!n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {
        }
        return "tool";
    }

    /** 从 tool_calls JSON 中取首个函数参数原文；解析失败返回 null */
    public static String parseToolArgs(String toolCallsJson) {
        try {
            JSONArray arr = new JSONArray(toolCallsJson);
            if (arr.length() > 0) {
                JSONObject call = arr.optJSONObject(0);
                if (call != null && call.optJSONObject("function") != null) {
                    return call.optJSONObject("function").optString("arguments", "");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 时间分隔符文案：当天只显示 {@code HH:mm}，昨天加"昨天"前缀，
     * 同年显示 {@code M月d日 HH:mm}，跨年显示完整日期。
     *
     * @param now 基准"当前时间"，由调用方传入以便测试固定时间
     */
    public static String formatDividerTime(long ts, long now, Locale locale) {
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(ts);
        Calendar ref = Calendar.getInstance();
        ref.setTimeInMillis(now);

        boolean sameYear = target.get(Calendar.YEAR) == ref.get(Calendar.YEAR);
        int dayDiff = sameYear
                ? target.get(Calendar.DAY_OF_YEAR) - ref.get(Calendar.DAY_OF_YEAR)
                : 999;

        String hm = new SimpleDateFormat("HH:mm", locale).format(new Date(ts));
        if (dayDiff == 0) return hm;
        if (dayDiff == -1) return "昨天 " + hm;
        if (sameYear) {
            return new SimpleDateFormat("M月d日 HH:mm", locale).format(new Date(ts));
        }
        return new SimpleDateFormat("yyyy/M/d HH:mm", locale).format(new Date(ts));
    }
}
