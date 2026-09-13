package com.digitallife.harness;

import org.json.JSONObject;

public final class SessionEvent {

    public static final String TURN_START = "turn/start";
    public static final String TURN_END = "turn/end";
    public static final String STEP_START = "step/start";
    public static final String STEP_END = "step/end";
    public static final String SYSTEM_MESSAGE = "system/message";
    public static final String USER_MESSAGE = "user/message";
    public static final String ASSISTANT_MESSAGE = "assistant/message";
    public static final String ASSISTANT_ATTEMPT = "assistant/attempt";
    public static final String TOOL_CALL = "tool/call";
    public static final String TOOL_RESULT = "tool/result";
    public static final String REQUEST_HEADER = "request/header";

    public final String type;
    public final long ts;
    public final JSONObject data;

    public SessionEvent(String type, JSONObject data) {
        this.type = type == null ? "" : type;
        this.ts = System.currentTimeMillis();
        this.data = data != null ? data : new JSONObject();
    }

    public String text() {
        return data.optString("text", data.optString("content", ""));
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", type);
            o.put("ts", ts);
            o.put("data", data);
        } catch (Exception ignored) {
        }
        return o;
    }
}
