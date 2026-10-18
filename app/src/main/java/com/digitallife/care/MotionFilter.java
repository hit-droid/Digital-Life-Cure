package com.digitallife.care;

import java.util.Locale;

/**
 * 动作池搜索过滤（v1.171.0）：纯逻辑、无 Android 依赖，便于单测。
 *
 * <p>查询串统一「trim + 小写」归一化后做大小写不敏感的 {@code contains} 匹配；
 * <b>空查询视为全部命中</b>——调用方据此直接免过滤。动作池页把模型名 / 组名 / 动作名 /
 * 文件名 / 表情名分别喂给 {@link #matches}/{@link #anyMatches}，任一字段命中即算命中。</p>
 */
public final class MotionFilter {

    private MotionFilter() {
    }

    /** 归一化查询串：trim + 小写；null 或纯空白返回空串 */
    public static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    /** 是否为空查询（不过滤） */
    public static boolean isBlank(String raw) {
        return normalize(raw).isEmpty();
    }

    /**
     * 单字段是否命中：{@code haystack} 大小写不敏感包含 {@code normalizedQuery}。
     * 查询为空 → 命中；查询非空但 {@code haystack} 为 null → 不命中。
     */
    public static boolean matches(String haystack, String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) return true;
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    /** 任一字段命中即算命中；查询为空 → 命中 */
    public static boolean anyMatches(String normalizedQuery, String... haystacks) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) return true;
        for (String h : haystacks) {
            if (matches(h, normalizedQuery)) return true;
        }
        return false;
    }
}
