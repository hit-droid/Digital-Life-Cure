package com.digitallife.ui.contacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v1.147.0（#88）：模型联系人列表的本地搜索过滤（纯逻辑，便于 JVM 单测）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>查询去首尾空白；为空（或 null）时返回全部；</li>
 *   <li>大小写不敏感（{@link Locale#ROOT} 小写比较，避免土耳其 i 问题）；</li>
 *   <li>按名字<b>子串</b>包含匹配；</li>
 *   <li>保持原有顺序（不做重排），入参/元素为 null 时安全跳过。</li>
 * </ul>
 */
public final class ContactFilter {

    private ContactFilter() {
    }

    /**
     * @param names 原始名字列表（可为 null）
     * @param query 用户输入（可为 null / 空白）
     * @return 命中的名字列表（新列表，可能为空但不为 null）
     */
    public static List<String> filter(List<String> names, String query) {
        List<String> out = new ArrayList<>();
        if (names == null) return out;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (String n : names) {
            if (n == null) continue;
            if (q.isEmpty() || n.toLowerCase(Locale.ROOT).contains(q)) {
                out.add(n);
            }
        }
        return out;
    }

    /** 便捷判定：是否有查询词（去空白后非空） */
    public static boolean hasQuery(String query) {
        return query != null && !query.trim().isEmpty();
    }
}
