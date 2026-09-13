package com.digitallife.harness.plugin;

import android.content.Context;

import com.digitallife.harness.AgentHandle;
import com.digitallife.harness.AgentLoop;
import com.digitallife.harness.EventBus;
import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;
import com.digitallife.harness.ToolPipeline;
import com.digitallife.ui.ToolMarketActivity;

/**
 * 循环卫生与工具把关：注册在 agent/pre-step、agent/request、tools/pre-execute
 * 三条 waterfall 上，插件卸载时随 effect 一起撤销。
 */
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
        EventBus events = ctx.events();

        ctx.effect(events.addWaterfall(AgentLoop.EVENT_PRE_STEP,
                new EventBus.Waterfall<Boolean>() {
                    @Override
                    public Boolean handle(Boolean enter, EventBus.Next<Boolean> next) {
                        if (enter != null && !enter) return enter;
                        return next.apply(enter);
                    }
                }));

        ctx.effect(events.addWaterfall("tools/pre-execute",
                new EventBus.Waterfall<ToolPipeline.Call>() {
                    @Override
                    public ToolPipeline.Call handle(ToolPipeline.Call call,
                                                    EventBus.Next<ToolPipeline.Call> next) {
                        if (call == null) return null;
                        if (app != null && ToolMarketActivity.isDisabled(app, call.name)) {
                            call.rejected = true;
                            call.error = "工具已被用户禁用：" + call.name;
                            return call;
                        }
                        return next.apply(call);
                    }
                }));

        ctx.effect(events.addWaterfall(AgentLoop.EVENT_REQUEST,
                new EventBus.Waterfall<AgentHandle>() {
                    @Override
                    public AgentHandle handle(AgentHandle agent, EventBus.Next<AgentHandle> next) {
                        if (agent != null && agent.cancelled) return agent;
                        return next.apply(agent);
                    }
                }));
    }
}
