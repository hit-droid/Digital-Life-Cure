package com.digitallife.harness;

import android.content.Context;

import com.digitallife.brain.LLMClient;
import com.digitallife.brain.Tools;
import com.digitallife.harness.plugin.AgentLoopPlugin;
import com.digitallife.harness.plugin.GuardPlugin;
import com.digitallife.harness.plugin.SessionPlugin;
import com.digitallife.harness.plugin.SystemPromptPlugin;
import com.digitallife.harness.plugin.ToolsPlugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class DeepSeekHarness {

    public static final String PROFILE_CHAT = "chat";
    public static final int MAX_STEPS = 6;

    private static DeepSeekHarness current;

    private final HarnessContext ctx = new HarnessContext();
    private final List<Plugin> plugins = new ArrayList<>();
    private AgentHandle live;
    private int agentSeq;

    public static DeepSeekHarness boot(Context app) {
        DeepSeekHarness h = new DeepSeekHarness();
        h.mount(new SessionPlugin());
        h.mount(new SystemPromptPlugin());
        h.mount(new ToolsPlugin());
        h.mount(new AgentLoopPlugin());
        h.mount(new GuardPlugin(app));
        h.activateAll();
        current = h;
        return h;
    }

    public static DeepSeekHarness current() {
        return current;
    }

    public List<String> pluginIds() {
        List<String> ids = new ArrayList<>();
        for (Plugin p : plugins) ids.add(p.id());
        return ids;
    }

    public HarnessContext context() {
        return ctx;
    }

    public void mount(Plugin plugin) {
        if (plugin != null) plugins.add(plugin);
    }

    public void activateAll() {
        for (Plugin p : plugins) {
            p.activate(ctx);
        }
    }

    public AgentHandle liveAgent() {
        return live;
    }

    public SessionLog session() {
        return ctx.get("sessions");
    }

    public PromptAssembler prompt() {
        return ctx.get("systemPrompt");
    }

    public ToolPipeline tools() {
        return ctx.get("tools");
    }

    public AgentLoop loop() {
        return ctx.get("agentLoop");
    }

    public void bind(LlmAdapter llm, Tools host, String systemPrompt) {
        ToolPipeline pipeline = tools();
        if (pipeline != null) pipeline.setHost(host);
        PromptAssembler pa = prompt();
        if (pa != null) {
            pa.setSection("persona", systemPrompt);
            pa.setToolSchemas(host != null ? host.toJsonArray() : new JSONArray());
        }
        if (live != null) live.llm = llm;
    }

    public void seedHistory(List<LLMClient.ChatMessage> history) {
        SessionLog log = session();
        if (log == null) return;
        log.clear();
        if (history == null) return;
        for (LLMClient.ChatMessage m : history) {
            if (m == null || m.role == null) continue;
            JSONObject d = new JSONObject();
            try {
                if (m.content != null) d.put("text", m.content);
                if (m.toolCalls != null) d.put("tool_calls", m.toolCalls);
                if (m.toolCallId != null) d.put("tool_call_id", m.toolCallId);
            } catch (Exception ignored) {
            }
            if ("user".equals(m.role) || "system".equals(m.role)) {
                try {
                    d.put("role", m.role);
                } catch (Exception ignored) {
                }
                log.append(SessionEvent.USER_MESSAGE, d);
            } else if ("assistant".equals(m.role)) {
                log.append(SessionEvent.ASSISTANT_MESSAGE, d);
            } else if ("tool".equals(m.role)) {
                log.append(SessionEvent.TOOL_RESULT, d);
            }
        }
    }

    public AgentHandle startTurn(LlmAdapter llm, Tools host, String systemPrompt,
                                 List<LLMClient.ChatMessage> history,
                                 AgentHandle.Listener listener) {
        cancel();
        bind(llm, host, systemPrompt);
        seedHistory(history);
        live = new AgentHandle("agent-" + (++agentSeq), ctx);
        live.llm = llm;
        live.listener = listener;
        live.maxSteps = MAX_STEPS;
        live.running = true;
        AgentLoop loop = loop();
        if (loop != null) loop.runTurn(live, null);
        return live;
    }

    public void cancel() {
        if (live == null) return;
        live.cancelled = true;
        live.retired = true;
        live.running = false;
        if (live.llm != null) {
            try {
                live.llm.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    public boolean isBusy() {
        return live != null && live.running;
    }

    public boolean endedOnTool() {
        SessionLog log = session();
        if (log == null) return false;
        List<SessionEvent> all = log.all();
        for (int i = all.size() - 1; i >= 0; i--) {
            String t = all.get(i).type;
            if (SessionEvent.TOOL_RESULT.equals(t)) return true;
            if (SessionEvent.ASSISTANT_MESSAGE.equals(t)
                    || SessionEvent.USER_MESSAGE.equals(t)
                    || SessionEvent.TURN_START.equals(t)) {
                return false;
            }
        }
        return false;
    }

    public void dispose() {
        cancel();
        ctx.dispose();
        live = null;
    }
}
