package com.digitallife.harness;

import com.digitallife.brain.BrainLog;
import com.digitallife.brain.LLMClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

public final class AgentLoop {

    public static final String EVENT_PRE_STEP = "agent/pre-step";
    public static final String EVENT_REQUEST = "agent/request";
    public static final String EVENT_TURN_STOPPING = "agent/turn-stopping";
    public static final String EVENT_STREAM = "agent/assistant-stream";

    private final HarnessContext ctx;

    public AgentLoop(HarnessContext ctx) {
        this.ctx = ctx;
    }

    public void runTurn(AgentHandle agent, String userText) {
        if (agent == null) return;
        agent.cancelled = false;
        SessionLog log = agent.session;
        JSONObject turnData = new JSONObject();
        try {
            turnData.put("text", userText == null ? "" : userText);
        } catch (Exception ignored) {
        }
        log.append(SessionEvent.TURN_START, turnData);
        if (userText != null && !userText.isEmpty()) {
            JSONObject u = new JSONObject();
            try {
                u.put("text", userText);
            } catch (Exception ignored) {
            }
            log.append(SessionEvent.USER_MESSAGE, u);
        }
        JSONObject sys = new JSONObject();
        try {
            sys.put("text", agent.prompt.render());
        } catch (Exception ignored) {
        }
        log.append(SessionEvent.SYSTEM_MESSAGE, sys);
        BrainLog.getInstance().log("harness", "turn/start");
        if (agent.listener != null) agent.listener.onTurn("start");
        runStep(agent, 1);
    }

    private void runStep(AgentHandle agent, int stepIndex) {
        EventBus events = ctx.events();
        SessionLog log = agent.session;
        int max = agent.maxSteps > 0 ? agent.maxSteps : 6;
        if (agent.cancelled) {
            finishTurn(agent, false);
            return;
        }
        if (stepIndex > max) {
            BrainLog.getInstance().log("harness", "step cap " + max);
            if (agent.listener != null) agent.listener.onDone("");
            finishTurn(agent, true);
            return;
        }
        Boolean enter = events.waterfall(EVENT_PRE_STEP, Boolean.TRUE);
        if (enter != null && !enter) {
            finishTurn(agent, false);
            return;
        }
        JSONObject stepMeta = new JSONObject();
        try {
            stepMeta.put("index", stepIndex);
        } catch (Exception ignored) {
        }
        log.append(SessionEvent.STEP_START, stepMeta);
        events.waterfall(EVENT_REQUEST, agent);

        LLMClient llm = agent.llm;
        if (llm == null) {
            if (agent.listener != null) agent.listener.onError("没有可用的模型配置");
            log.append(SessionEvent.STEP_END, stepMeta);
            finishTurn(agent, false);
            return;
        }

        List<LLMClient.ChatMessage> msgs = log.deriveMessages();
        JSONObject extra = new JSONObject();
        try {
            extra.put("system", agent.prompt.render());
            String td = agent.prompt.toolsDesc();
            if (!td.trim().isEmpty()) {
                extra.put("tools_desc",
                        "你拥有以下工具，可以在必要时调用它们帮用户做事。"
                                + "先判断是否需要工具，不需要就直接回答；需要就调用，调用后结合结果继续回答：\n"
                                + td);
            }
        } catch (Exception ignored) {
        }
        llm.setTools(agent.prompt.toolSchemas());
        final boolean[] toolOwes = new boolean[]{false};
        final int gen = log.generation();
        events.emit(EVENT_STREAM, "start");
        llm.chatStream(msgs, extra, new LLMClient.StreamListener() {
            @Override
            public void onDelta(String text) {
                if (stale()) return;
                events.emit(EVENT_STREAM, text);
                if (agent.listener != null) agent.listener.onDelta(text);
            }

            @Override
            public void onToolCall(String name, JSONObject args, String toolCallId) {
                if (stale()) return;
                handleToolCall(agent, name, args, toolCallId);
                toolOwes[0] = true;
            }

            @Override
            public void onDone(String fullText) {
                if (stale()) return;
                events.emit(EVENT_STREAM, "end");
                String text = fullText == null ? "" : fullText;
                if (agent.cancelled) {
                    recordAttempt(log, "cancelled", text, null);
                    log.append(SessionEvent.STEP_END, stepMeta);
                    finishTurn(agent, false);
                    return;
                }
                if (toolOwes[0]) {
                    log.append(SessionEvent.STEP_END, stepMeta);
                    runStep(agent, stepIndex + 1);
                    return;
                }
                JSONObject asst = new JSONObject();
                try {
                    asst.put("text", text);
                } catch (Exception ignored) {
                }
                log.append(SessionEvent.ASSISTANT_MESSAGE, asst);
                log.append(SessionEvent.STEP_END, stepMeta);
                if (agent.listener != null) agent.listener.onDone(text);
                finishTurn(agent, true);
            }

            private boolean stale() {
                return agent.retired || agent.session.generation() != gen;
            }

            @Override
            public void onError(String error) {
                if (stale()) return;
                events.emit(EVENT_STREAM, "end");
                recordAttempt(log, "error", null, error);
                log.append(SessionEvent.STEP_END, stepMeta);
                if (agent.cancelled) {
                    finishTurn(agent, false);
                    return;
                }
                if (agent.listener != null) agent.listener.onError(error);
                finishTurn(agent, false);
            }
        });
    }

    private void finishTurn(AgentHandle agent, boolean completed) {
        agent.running = false;
        if (agent.retired) return;
        ctx.events().emit(EVENT_TURN_STOPPING, agent);
        JSONObject end = new JSONObject();
        try {
            end.put("completed", completed);
        } catch (Exception ignored) {
        }
        agent.session.append(SessionEvent.TURN_END, end);
        BrainLog.getInstance().log("harness", "turn/end");
        if (agent.listener != null) agent.listener.onTurn("end");
    }

    private static void recordAttempt(SessionLog log, String reason, String text, String error) {
        JSONObject attempt = new JSONObject();
        try {
            attempt.put("reason", reason);
            if (text != null) attempt.put("text", text);
            if (error != null) attempt.put("error", error);
        } catch (Exception ignored) {
        }
        log.append(SessionEvent.ASSISTANT_ATTEMPT, attempt);
    }

    private void handleToolCall(AgentHandle agent, String name, JSONObject args, String toolCallId) {
        if (agent.retired || agent.cancelled) return;
        final int gen = agent.session.generation();
        String cleanId = (toolCallId == null || toolCallId.isEmpty())
                ? "call_" + name + "_" + System.currentTimeMillis() : toolCallId;
        JSONObject callData = new JSONObject();
        try {
            callData.put("name", name);
            callData.put("args", args != null ? args : new JSONObject());
            callData.put("tool_call_id", cleanId);
        } catch (Exception ignored) {
        }
        agent.session.append(SessionEvent.TOOL_CALL, callData);
        if (agent.listener != null) {
            agent.listener.onToolCall(name, args != null ? args : new JSONObject(), cleanId);
        }

        JSONArray tcs = new JSONArray();
        try {
            JSONObject tc = new JSONObject();
            tc.put("id", cleanId);
            tc.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("arguments", args != null ? args.toString() : "{}");
            tc.put("function", fn);
            tcs.put(tc);
        } catch (Exception ignored) {
        }
        JSONObject asst = new JSONObject();
        try {
            asst.put("tool_calls", tcs);
        } catch (Exception ignored) {
        }
        agent.session.append(SessionEvent.ASSISTANT_MESSAGE, asst);

        ToolPipeline.Call result = agent.tools.execute(name, args, cleanId);
        if (agent.retired || agent.session.generation() != gen) return;
        boolean ok = result.error == null;
        String text = ok
                ? (result.result != null && !result.result.isEmpty() ? result.result : "（工具无返回）")
                : "工具执行失败：" + result.error;
        JSONObject resData = new JSONObject();
        try {
            resData.put("name", name);
            resData.put("text", text);
            resData.put("ok", ok);
            resData.put("tool_call_id", cleanId);
        } catch (Exception ignored) {
        }
        agent.session.append(SessionEvent.TOOL_RESULT, resData);
        BrainLog.getInstance().log("harness", (ok ? "tool ok " : "tool fail ") + name);
        if (agent.listener != null) agent.listener.onToolResult(name, ok, text);
    }
}
