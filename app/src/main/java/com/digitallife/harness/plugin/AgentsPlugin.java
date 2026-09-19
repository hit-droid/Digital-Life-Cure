package com.digitallife.harness.plugin;

import com.digitallife.harness.AgentRegistry;
import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;

public final class AgentsPlugin implements Plugin {
    @Override
    public String id() {
        return "dsh-agents";
    }

    @Override
    public void activate(HarnessContext ctx) {
        ctx.provide("agents", new AgentRegistry());
    }
}
