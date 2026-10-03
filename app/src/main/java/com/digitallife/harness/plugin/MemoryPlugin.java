package com.digitallife.harness.plugin;

import android.content.Context;

import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;
import com.digitallife.harness.PromptAssembler;
import com.digitallife.memory.MemoryRetriever;

/**
 * 把混合检索到的长期记忆织进 system prompt 的 memory 段。
 *
 * v1.120.0：支持按当前用户消息做「查询相关召回」。调用方在 {@code startTurn}
 * 前通过 {@code HarnessContext.provide(KEY_QUERY, 当前消息)} 注入；未注入或为空时
 * 退化为「最近 + 高权重」召回（保持旧行为）。记忆插件在 {@code bind()} 里每轮会
 * 被重新 activate，因此每轮都能拿到当轮的问题。
 */
public final class MemoryPlugin implements Plugin {

    /** HarnessContext 服务键：当前用户消息，用于记忆相关性检索 */
    public static final String KEY_QUERY = "memoryQuery";

    private static final int MAX_CHARS = 1200;

    private final Context app;

    public MemoryPlugin(Context app) {
        this.app = app != null ? app.getApplicationContext() : null;
    }

    @Override
    public String id() {
        return "dsh-memory";
    }

    @Override
    public void activate(HarnessContext ctx) {
        if (app == null || ctx == null) return;
        PromptAssembler pa = ctx.get("systemPrompt");
        if (pa == null) return;
        String query = ctx.get(KEY_QUERY);
        pa.setSection("memory", render(query));
    }

    private String render(String query) {
        try {
            String text = new MemoryRetriever(app).renderForPrompt(query == null ? "" : query);
            if (text == null || text.isEmpty() || text.contains("暂无相关记忆")) return "";
            if (text.length() > MAX_CHARS) {
                text = text.substring(0, MAX_CHARS) + "\n…";
            }
            return text;
        } catch (Exception e) {
            return "";
        }
    }
}
