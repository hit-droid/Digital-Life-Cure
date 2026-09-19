package com.digitallife.harness.plugin;

import android.content.Context;

import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;
import com.digitallife.harness.PromptAssembler;
import com.digitallife.memory.MemoryRetriever;

/**
 * 把混合检索到的长期记忆织进 system prompt 的 memory 段。
 */
public final class MemoryPlugin implements Plugin {

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
        PromptAssembler pa = ctx.get("systemPrompt");
        if (pa == null || app == null) return;
        pa.setSection("memory", render());
    }

    private String render() {
        try {
            String text = new MemoryRetriever(app).renderForPrompt("");
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
