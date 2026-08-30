package com.digitallife.memory;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 记忆图 View（v1.24.0）。
 * 简单实现：词云式展示。
 * - 词频越高的关键词字号越大
 * - 不同分类用不同颜色
 * - 限制渲染 50 个关键词
 */
public class MemoryGraphView extends View {

    private List<MemoryEntry> entries = new ArrayList<>();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();

    private static final int[] CATEGORY_COLORS = {
            0xFFC4B5FD, // 偏好 preference
            0xFFFFB4D8, // 性格 personality
            0xFF7B9ACC, // 事件 event
            0xFFA8C9A8, // 事实 fact
            0xFFE0E0E0  // 其他 other
    };

    public MemoryGraphView(Context ctx) { super(ctx); init(); }
    public MemoryGraphView(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        bgPaint.setColor(0xFF1A1A1A);
    }

    public void setEntries(List<MemoryEntry> list) {
        this.entries = list != null ? list : new ArrayList<>();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);
        if (entries.isEmpty()) {
            textPaint.setColor(0xFF888888);
            textPaint.setTextSize(28f);
            textPaint.setTypeface(Typeface.DEFAULT);
            canvas.drawText("暂无记忆", getWidth() / 2f - 60, getHeight() / 2f, textPaint);
            return;
        }
        // 提取关键词 + 统计频次
        Map<String, Integer> freq = new HashMap<>();
        Map<String, String> categoryMap = new HashMap<>();
        for (MemoryEntry e : entries) {
            String[] words = extractWords(e.content);
            for (String w : words) {
                if (w.length() < 2) continue;
                freq.put(w, freq.getOrDefault(w, 0) + 1);
                categoryMap.put(w, e.category);
            }
        }
        if (freq.isEmpty()) return;
        // 排序
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(freq.entrySet());
        Collections.sort(sorted, new Comparator<Map.Entry<String, Integer>>() {
            @Override
            public int compare(Map.Entry<String, Integer> a, Map.Entry<String, Integer> b) {
                return Integer.compare(b.getValue(), a.getValue());
            }
        });
        if (sorted.size() > 50) sorted = sorted.subList(0, 50);

        // 渲染
        int maxFreq = sorted.get(0).getValue();
        int padding = 16;
        int x = padding;
        int y = padding + 24;
        int lineHeight = 0;
        for (Map.Entry<String, Integer> e : sorted) {
            String word = e.getKey();
            int count = e.getValue();
            float size = 14f + (count / (float) maxFreq) * 24f;
            textPaint.setTextSize(size);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setColor(colorForCategory(categoryMap.get(word)));
            float w = textPaint.measureText(word);
            if (x + w + 16 > getWidth()) {
                x = padding;
                y += lineHeight + 16;
                lineHeight = 0;
            }
            canvas.drawText(word, x, y, textPaint);
            x += w + 16;
            lineHeight = Math.max(lineHeight, (int) size);
        }
    }

    private int colorForCategory(String cat) {
        if (cat == null) return CATEGORY_COLORS[4];
        switch (cat) {
            case "preference": return CATEGORY_COLORS[0];
            case "personality": return CATEGORY_COLORS[1];
            case "event": return CATEGORY_COLORS[2];
            case "fact": return CATEGORY_COLORS[3];
            default: return CATEGORY_COLORS[4];
        }
    }

    private String[] extractWords(String content) {
        if (content == null) return new String[0];
        // 简单分词：按标点和空格切
        String cleaned = content.replaceAll("[，。！？、,.!?;:；:\\s]+", " ")
                .toLowerCase(Locale.getDefault());
        return cleaned.split("\\s+");
    }
}
