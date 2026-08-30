package com.digitallife.brain;

import com.digitallife.brain.Tools.Callback;
import com.digitallife.tools.ToolUsageLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 计划执行器（v1.24.0）。
 * 借鉴 Operit AI 的多步计划 + step-by-step execute 设计。
 * 解析 LLM 返回的 {"plan":[...]} JSON，逐条执行工具调用，
 * 每步结果回传供 LLM 下一轮决策。
 */
public class PlanExecutor {

    public interface PlanListener {
        void onPlanStart(int steps);
        void onStepStart(int index, String tool, JSONObject args);
        void onStepEnd(int index, String tool, String result, String error);
        void onPlanEnd(boolean allOk);
    }

    public static class Step {
        public final int index;
        public final String tool;
        public final JSONObject args;
        public final String result;
        public final String error;
        public final long durationMs;

        public Step(int index, String tool, JSONObject args,
                    String result, String error, long durationMs) {
            this.index = index;
            this.tool = tool;
            this.args = args;
            this.result = result;
            this.error = error;
            this.durationMs = durationMs;
        }
    }

    private final List<Step> history = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public List<Step> history() {
        return history;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 从 LLM 回复中提取计划（若包含 "plan":[...]）。 */
    public static JSONArray extractPlan(String llmText) {
        if (llmText == null) return null;
        // 简单启发式：找 "plan" 字段的 JSON 数组
        int idx = llmText.indexOf("\"plan\"");
        if (idx < 0) return null;
        int bracketStart = llmText.indexOf('[', idx);
        if (bracketStart < 0) return null;
        int depth = 0;
        int bracketEnd = -1;
        for (int i = bracketStart; i < llmText.length(); i++) {
            char c = llmText.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    bracketEnd = i;
                    break;
                }
            }
        }
        if (bracketEnd < 0) return null;
        try {
            return new JSONArray(llmText.substring(bracketStart, bracketEnd + 1));
        } catch (JSONException e) {
            return null;
        }
    }

    /** 顺序执行计划中的每一步工具。 */
    public void executePlan(JSONArray plan, Tools tools, PlanListener listener) {
        if (plan == null || plan.length() == 0) {
            if (listener != null) listener.onPlanEnd(true);
            return;
        }
        if (!running.compareAndSet(false, true)) {
            // 已有计划在跑，丢弃
            if (listener != null) listener.onPlanEnd(false);
            return;
        }
        int total = plan.length();
        if (listener != null) listener.onPlanStart(total);
        executeStep(plan, 0, tools, listener, true);
    }

    private void executeStep(JSONArray plan, int idx, Tools tools,
                             PlanListener listener, boolean allOk) {
        if (idx >= plan.length()) {
            running.set(false);
            if (listener != null) listener.onPlanEnd(allOk);
            return;
        }
        JSONObject step = plan.optJSONObject(idx);
        if (step == null) {
            executeStep(plan, idx + 1, tools, listener, allOk);
            return;
        }
        String tool = step.optString("tool", "");
        JSONObject args = step.optJSONObject("args");
        if (args == null) args = new JSONObject();
        if (listener != null) listener.onStepStart(idx, tool, args);
        if (tool.isEmpty()) {
            Step s = new Step(idx, tool, args, null, "missing tool name", 0);
            history.add(s);
            if (listener != null) listener.onStepEnd(idx, tool, null, "missing tool name");
            executeStep(plan, idx + 1, tools, listener, false);
            return;
        }
        long t0 = System.currentTimeMillis();
        tools.execute(tool, args, new Callback() {
            @Override
            public void onResult(String toolName, JSONObject args,
                                 String resultText, String error) {
                long dt = System.currentTimeMillis() - t0;
                Step s = new Step(idx, toolName, args, resultText, error, dt);
                history.add(s);
                if (listener != null) {
                    listener.onStepEnd(idx, toolName, resultText, error);
                }
                // 错误也继续，LLM 会基于错误信息调整
                executeStep(plan, idx + 1, tools, listener, allOk && error == null);
            }
        });
    }
}
