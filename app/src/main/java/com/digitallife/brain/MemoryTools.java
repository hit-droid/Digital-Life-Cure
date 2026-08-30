package com.digitallife.brain;

import android.content.Context;
import android.content.SharedPreferences;

import com.digitallife.util.MemoryStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 内置工具"记忆类"的执行实现。
 * 委托给 MemoryStore 完成实际工作（基于 facts 表）。
 */
public class MemoryTools {

    public static String search(Context ctx, String query) {
        if (query == null || query.trim().isEmpty()) return "查询关键词不能为空";
        MemoryStore store = new MemoryStore(ctx);
        List<MemoryStore.Fact> facts = store.getAllFacts();
        String q = query.toLowerCase();
        List<MemoryStore.Fact> matched = new ArrayList<>();
        for (MemoryStore.Fact f : facts) {
            if (f.content != null && f.content.toLowerCase().contains(q)) {
                matched.add(f);
                if (matched.size() >= 5) break;
            }
        }
        if (matched.isEmpty()) return "没有找到与「" + query + "」相关的记忆";
        StringBuilder sb = new StringBuilder("找到 " + matched.size() + " 条相关记忆：\n");
        for (MemoryStore.Fact f : matched) {
            sb.append("- [").append(f.category).append("] ").append(f.content)
                    .append("（置信度 ").append(String.format("%.1f", f.confidence)).append("）\n");
        }
        return sb.toString();
    }

    public static String recall(Context ctx, String topic) {
        if (topic == null || topic.trim().isEmpty()) return "回忆主题不能为空";
        MemoryStore store = new MemoryStore(ctx);
        List<MemoryStore.Fact> facts = store.getAllFacts();
        String t = topic.toLowerCase();
        List<MemoryStore.Fact> matched = new ArrayList<>();
        for (MemoryStore.Fact f : facts) {
            if (f.content != null && f.content.toLowerCase().contains(t)) {
                matched.add(f);
                if (matched.size() >= 10) break;
            }
        }
        if (matched.isEmpty()) return "我不记得关于「" + topic + "」的事情";
        StringBuilder sb = new StringBuilder("我回忆起一些关于「" + topic + "」的事情：\n");
        for (MemoryStore.Fact f : matched) {
            sb.append("- ").append(f.content).append("\n");
        }
        return sb.toString();
    }

    public static String save(Context ctx, String content, String tags) {
        if (content == null || content.trim().isEmpty()) return "记忆内容不能为空";
        MemoryStore store = new MemoryStore(ctx);
        String category = "auto";
        if (tags != null && !tags.isEmpty()) {
            String[] arr = tags.split(",");
            if (arr.length > 0) category = arr[0].trim();
        }
        store.saveFact(category, content, 0.8, System.currentTimeMillis());
        return "已记住：「" + content + "」";
    }

    public static String forget(Context ctx, String content) {
        if (content == null || content.trim().isEmpty()) return "遗忘内容不能为空";
        MemoryStore store = new MemoryStore(ctx);
        List<MemoryStore.Fact> facts = store.getAllFacts();
        String c = content.toLowerCase();
        List<Long> toDelete = new ArrayList<>();
        for (MemoryStore.Fact f : facts) {
            if (f.content != null && f.content.toLowerCase().contains(c)) {
                toDelete.add(f.id);
            }
        }
        for (Long id : toDelete) store.deleteFact(id);
        if (toDelete.isEmpty()) return "没有找到需要遗忘的内容";
        return "已遗忘 " + toDelete.size() + " 条记忆";
    }

    public static void addReminder(Context ctx, String text, int minutes) {
        SharedPreferences sp = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE);
        String key = "reminder_" + System.currentTimeMillis();
        long triggerAt = System.currentTimeMillis() + minutes * 60_000L;
        sp.edit().putString(key, text + "|" + triggerAt).apply();
    }

    public static String getTodayEvents(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE);
        StringBuilder sb = new StringBuilder("今日提醒：\n");
        int count = 0;
        long now = System.currentTimeMillis();
        long endOfDay = now + 24 * 3600_000L;
        for (String key : sp.getAll().keySet()) {
            if (!key.startsWith("reminder_")) continue;
            String val = sp.getString(key, "");
            int sep = val.indexOf('|');
            if (sep < 0) continue;
            String text = val.substring(0, sep);
            long trigger;
            try {
                trigger = Long.parseLong(val.substring(sep + 1));
            } catch (Exception e) {
                continue;
            }
            if (trigger >= now && trigger <= endOfDay) {
                sb.append("- ").append(text)
                        .append("（").append(formatTime(trigger)).append("）\n");
                count++;
            }
        }
        if (count == 0) sb.append("（暂无）\n");
        return sb.toString();
    }

    private static String formatTime(long ts) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
    }
}
