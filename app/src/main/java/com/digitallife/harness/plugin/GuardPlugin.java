package com.digitallife.harness.plugin;

import android.content.Context;

import com.digitallife.harness.AgentHandle;
import com.digitallife.harness.AgentLoop;
import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;
import com.digitallife.harness.ToolPipeline;
import com.digitallife.ui.ToolMarketActivity;

public final class GuardPlugin implements Plugin {

    private final Context app;

    public GuardPlugin(Context app) {
        this.app = app != null ? app.getApplicationContext() : null;
    }

    @Override
    public String id() {
        return "dsh-guard";
    }

    @Override
    public void activate(HarnessContext ctx) {
        ctx.effect(ctx.events().waterfall(AgentLoop.EVENT_PRE_STEP, (Boolean enter, next) -> {
            if (enter != null && !enter) return enter;
            return next.apply(enter);
        }));
        ctx.effect(ctx.events().waterfall("tools/pre-execute", (ToolPipeline.Call call, next) -> {
            if (call == null) return next.apply(call);
            if (app != null && ToolMarketActivity.isDisabled(app, call.name)) {
                call.rejected = true;
                call.error = "工具已被用户禁用：" + call.name;
                return call;
            }
            return next.apply(call);
        }));
        ctx.effect(ctx.events().waterfall(AgentLoop.EVENT_REQUEST, (AgentHandle agent, next) -> {
            if (agent != null && agent.cancelled) return agent;
            return next.apply(agent);
        }));
    }
}
