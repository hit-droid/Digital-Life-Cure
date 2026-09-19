package com.digitallife.harness;

import android.content.Context;

import com.digitallife.brain.LLMClient;
import com.digitallife.brain.Tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class DeepSeekHarness {

    public static final String PROFILE_CHAT = Profile.CHAT;
    public static final int MAX_STEPS = 6;

    private static DeepSeekHarness current;

    private final HarnessContext ctx = new HarnessContext();
    private final List<Plugin> plugins = new ArrayList<>();
    private AgentHandle live;
    private int agentSeq;
    private String profileName = Profile.CHAT;
    private boolean activated;

    public static DeepSeekHarness boot(Context app) {
        return boot(Profile.chat(app), true);
    }

    /**
     * 挂载一棵不注册为 current 的独立 plugin 树（子智能体 / 护理大脑用）。
     */
    public static DeepSeekHarness bootIsolated(Context app) {
        return boot(Profile.isolated(app), false);
    }

    public static DeepSeekHarness boot(Profile profile, boolean registerCurrent) {
        DeepSeekHarness h = new DeepSeekHarness();
        if (profile != null) {
            h.profileName = profile.name;
            for (Plugin p : profile.plugins()) h.mount(p);
        }
        h.activateAll();
        if (registerCurrent) current = h;
        return h;
    }

    public static DeepSeekHarness current() {
        return current;
    }

    public String profileName() {
        return profileName;
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
        if (plugin == null) return;
        plugins.add(plugin);
        if (activated) plugin.activate(ctx);
    }

    public boolean unmount(String id) {
        if (id == null) return false;
        for (int i = plugins.size() - 1; i >= 0; i--) {
            Plugin p = plugins.get(i);
            if (!id.equals(p.id())) continue;
            try {
                p.deactivate(ctx);
            } catch (Exception ignored) {
            }
            plugins.remove(i);
            return true;
        }
        return false;
    }

    public void activateAll() {
        for (Plugin p : plugins) {
            p.activate(ctx);
        }
        activated = true;
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

    public AgentRegistry agents() {
        return ctx.get("agents");
    }

    public void bind(LlmAdapter llm, Tools host, String systemPrompt) {
        ToolPipeline pipeline = tools();
        if (pipeline != null) pipeline.setHost(host);
        PromptAssembler pa = prompt();
        if (pa != null) {
            for (Plugin p : plugins) {
                String id = p.id();
                if ("dsh-persona".equals(id) || "dsh-memory".equals(id)
                        || "dsh-skills".equals(id)) {
                    p.activate(ctx);
                }
            }
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                pa.setSection("persona", systemPrompt);
            }
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
        return startTurn(llm, host, systemPrompt, history, listener, MAX_STEPS);
    }

    /**
     * @param maxSteps 本轮最多允许的 step 数；必须在此处传入，因为首个 step
     *                 会在本方法内同步开始并读取该值。
     */
    public AgentHandle startTurn(LlmAdapter llm, Tools host, String systemPrompt,
                                 List<LLMClient.ChatMessage> history,
                                 AgentHandle.Listener listener, int maxSteps) {
        cancel();
        bind(llm, host, systemPrompt);
        seedHistory(history);
        live = new AgentHandle("agent-" + (++agentSeq), ctx);
        live.llm = llm;
        live.listener = listener;
        live.maxSteps = maxSteps > 0 ? maxSteps : MAX_STEPS;
        live.running = true;
        AgentRegistry reg = agents();
        if (reg != null) reg.register(live);
        AgentLoop loop = loop();
        if (loop != null) loop.runTurn(live, null);
        return live;
    }

    public void cancel() {
        if (live == null) return;
        live.cancelled = true;
        live.retired = true;
        live.running = false;
        AgentRegistry reg = agents();
        if (reg != null) reg.unregister(live.id);
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
        for (int i = plugins.size() - 1; i >= 0; i--) {
            try {
                plugins.get(i).deactivate(ctx);
            } catch (Exception ignored) {
            }
        }
        plugins.clear();
        ctx.dispose();
        live = null;
        if (current == this) current = null;
        activated = false;
    }
}
