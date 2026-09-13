package com.digitallife.harness.plugin;

import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;
import com.digitallife.harness.ToolPipeline;

public final class ToolsPlugin implements Plugin {
    @Override
    public String id() {
        return "dsh-tools";
    }

    @Override
    public void activate(HarnessContext ctx) {
        ctx.provide("tools", new ToolPipeline(ctx.events()));
    }
}
