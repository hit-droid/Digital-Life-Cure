package com.digitallife.harness;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.digitallife.brain.LLMClient;

/**
 * 可脚本化的 LLM provider：按预设回合依次回调，用于在无网络环境下驱动 agent-loop。
 * 每次 chatStream 消费一个脚本项；脚本项可以只发文本，也可以先发工具调用再收尾。
 */
final class ScriptedLlm implements LlmAdapter {

    static final class ToolCall {
        final String name;
        final JSONObject args;

        ToolCall(String name, JSONObject args) {
            this.name = name;
            this.args = args;
        }
    }

    static final class Turn {
        final String text;
        final List<ToolCall> calls = new ArrayList<>();

        Turn(String text) {
            this.text = text == null ? "" : text;
        }

        Turn withCall(String name, JSONObject args) {
            calls.add(new ToolCall(name, args));
            return this;
        }
    }

    static Turn text(String t) {
        return new Turn(t);
    }

    static Turn call(String name, JSONObject args) {
        return new Turn("").withCall(name, args);
    }

    private final Deque<Turn> script = new ArrayDeque<>();
    final List<List<LLMClient.ChatMessage>> requests = new ArrayList<>();
    final List<JSONObject> extras = new ArrayList<>();
    final List<JSONArray> toolSchemas = new ArrayList<>();
    int cancelCount;
    int streamCount;

    void enqueue(Turn... turns) {
        for (Turn t : turns) script.add(t);
    }

    int remaining() {
        return script.size();
    }

    @Override
    public void chatStream(List<LLMClient.ChatMessage> messages, JSONObject extraSystem,
                           LLMClient.StreamListener listener) {
        requests.add(new ArrayList<>(messages));
        extras.add(extraSystem);
        streamCount++;
        Turn turn = script.poll();
        if (turn == null) {
            listener.onError("脚本已耗尽");
            return;
        }
        for (ToolCall c : turn.calls) {
            listener.onToolCall(c.name, c.args, "call_" + c.name);
        }
        if (!turn.text.isEmpty()) {
            listener.onDelta(turn.text);
        }
        listener.onDone(turn.text);
    }

    @Override
    public void setTools(JSONArray tools) {
        toolSchemas.add(tools);
    }

    @Override
    public void cancel() {
        cancelCount++;
    }
}
