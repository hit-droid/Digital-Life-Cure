package com.digitallife.harness.plugin;

import com.digitallife.harness.AgentHandle;
import com.digitallife.harness.Disposable;
import com.digitallife.harness.EventBus;
import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;
import com.digitallife.harness.PromptAssembler;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 每步请求前刷新 clock 段，保证模型看到的是当前时间。
 */
public final class ClockPlugin implements Plugin {

    private Disposable request;

    @Override
    public String id() {
        return "dsh-clock";
    }

    @Override
    public void activate(HarnessContext ctx) {
        PromptAssembler pa = ctx.get("systemPrompt");
        if (pa != null) pa.setSection("clock", now());
        request = ctx.events().addWaterfall("agent/request",
                new EventBus.Waterfall<AgentHandle>() {
                    @Override
                    public AgentHandle handle(AgentHandle agent, EventBus.Next<AgentHandle> next) {
                        if (agent != null && agent.prompt != null) {
                            agent.prompt.setSection("clock", now());
                        }
                        return next.apply(agent);
                    }
                });
        ctx.effect(request);
    }

    @Override
    public void deactivate(HarnessContext ctx) {
        if (request != null) request.dispose();
        request = null;
    }

    static String now() {
        return "当前时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date());
    }
}
