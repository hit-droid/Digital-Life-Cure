package com.digitallife.harness.plugin;

import com.digitallife.harness.AgentLoop;
import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;

public final class AgentLoopPlugin implements Plugin {
    @Override
    public String id() {
        return "dsh-agent-loop";
    }

    @Override
    public void activate(HarnessContext ctx) {
        ctx.provide("agentLoop", new AgentLoop(ctx));
    }
}
