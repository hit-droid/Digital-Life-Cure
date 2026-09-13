package com.digitallife.harness;

import com.digitallife.brain.LLMClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SessionLog {

    private final List<SessionEvent> events = new ArrayList<>();
    private EventBus bus;
    private int generation;

    public void attach(EventBus bus) {
        this.bus = bus;
    }

    public synchronized SessionEvent append(String type, JSONObject data) {
        SessionEvent e = new SessionEvent(type, data);
        events.add(e);
        if (bus != null) bus.emit("session/event", e);
        return e;
    }

    public synchronized List<SessionEvent> all() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized void clear() {
        events.clear();
        generation++;
    }

    public synchronized int generation() {
        return generation;
    }

    public synchronized List<LLMClient.ChatMessage> deriveMessages() {
        List<LLMClient.ChatMessage> out = new ArrayList<>();
        for (SessionEvent e : events) {
            if (SessionEvent.USER_MESSAGE.equals(e.type)) {
                String t = e.text();
                if (t != null && !t.isEmpty()) {
                    String role = e.data.optString("role", "user");
                    if (!"system".equals(role)) role = "user";
                    out.add(new LLMClient.ChatMessage(role, t));
                }
            } else if (SessionEvent.ASSISTANT_MESSAGE.equals(e.type)) {
                LLMClient.ChatMessage m = new LLMClient.ChatMessage("assistant",
                        emptyToNull(e.data.optString("text", null)));
                JSONArray tcs = e.data.optJSONArray("tool_calls");
                if (tcs != null && tcs.length() > 0) m.toolCalls = tcs;
                if (m.content != null || m.toolCalls != null) out.add(m);
            } else if (SessionEvent.TOOL_RESULT.equals(e.type)) {
                LLMClient.ChatMessage m = new LLMClient.ChatMessage("tool", e.text());
                m.toolCallId = e.data.optString("tool_call_id", null);
                out.add(m);
            }
        }
        return out;
    }

    public synchronized List<SessionEvent> recent(int n) {
        int size = events.size();
        if (n >= size) return new ArrayList<>(events);
        return new ArrayList<>(events.subList(size - n, size));
    }

    private static String emptyToNull(String s) {
        if (s == null || s.isEmpty()) return null;
        return s;
    }
}
