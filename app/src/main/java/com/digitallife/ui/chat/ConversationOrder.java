package com.digitallife.ui.chat;

import com.digitallife.util.ChatStore;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * v1.147.0（#82）：会话列表排序的纯逻辑，便于 JVM 单测。
 *
 * <p>排序优先级：</p>
 * <ol>
 *   <li>系统内置护理会话（{@code careId}）恒排第一，无论是否置顶；</li>
 *   <li>其余会话中，用户<b>置顶</b>的排在未置顶之前；</li>
 *   <li>同一组内按最近更新时间（{@code updatedAt}）降序；时间相同再按 id 稳定兜底。</li>
 * </ol>
 *
 * <p>本类不依赖任何 Android API，输入列表可为 null，返回同一列表（原地排序）以便调用方拿回引用。</p>
 */
public final class ConversationOrder {

    private ConversationOrder() {
    }

    /** 原地排序并返回同一列表；入参为 null 时返回 null。 */
    public static List<ChatStore.SessionInfo> sort(List<ChatStore.SessionInfo> list, String careId) {
        if (list == null) return null;
        Collections.sort(list, new Comparator<ChatStore.SessionInfo>() {
            @Override
            public int compare(ChatStore.SessionInfo a, ChatStore.SessionInfo b) {
                if (a == b) return 0;
                if (a == null) return 1;
                if (b == null) return -1;

                boolean careA = isCare(a, careId);
                boolean careB = isCare(b, careId);
                if (careA != careB) return careA ? -1 : 1;

                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;

                if (a.updatedAt != b.updatedAt) return a.updatedAt > b.updatedAt ? -1 : 1;

                String ia = a.id == null ? "" : a.id;
                String ib = b.id == null ? "" : b.id;
                return ia.compareTo(ib);
            }
        });
        return list;
    }

    /** 该会话是否为系统内置护理会话（恒置顶）。 */
    public static boolean isCare(ChatStore.SessionInfo s, String careId) {
        if (s == null) return false;
        if (careId == null) return false;
        return careId.equals(s.id);
    }
}
