package com.digitallife.harness.plugin;

import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;
import com.digitallife.harness.PromptAssembler;

public final class SystemPromptPlugin implements Plugin {
    @Override
    public String id() {
        return "dsh-system-prompt";
    }

    @Override
    public void activate(HarnessContext ctx) {
        ctx.provide("systemPrompt", new PromptAssembler());
    }
}
