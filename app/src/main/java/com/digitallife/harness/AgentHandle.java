package com.digitallife.harness;

import com.digitallife.brain.LLMClient;

public final class AgentHandle {

    public interface Listener {
        void onDelta(String text);

        void onToolCall(String name, org.json.JSONObject args, String toolCallId);

        void onToolResult(String name, boolean ok, String result);

        void onDone(String fullText);

        void onError(String error);

        void onTurn(String phase);
    }

    public final String id;
    public final HarnessContext ctx;
    public final SessionLog session;
    public final PromptAssembler prompt;
    public final ToolPipeline tools;
    public Listener listener;
    public LLMClient llm;
    public volatile boolean cancelled;
    public volatile boolean retired;
    public volatile boolean running;
    public int maxSteps = 6;

    public AgentHandle(String id, HarnessContext ctx) {
        this.id = id;
        this.ctx = ctx;
        this.session = ctx.get("sessions");
        this.prompt = ctx.get("systemPrompt");
        this.tools = ctx.get("tools");
    }

    public void inject(String text) {
        if (text == null || text.isEmpty()) return;
        org.json.JSONObject d = new org.json.JSONObject();
        try {
            d.put("text", text);
        } catch (Exception ignored) {
        }
        session.append(SessionEvent.USER_MESSAGE, d);
    }
}
