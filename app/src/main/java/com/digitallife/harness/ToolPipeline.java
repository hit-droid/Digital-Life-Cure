package com.digitallife.harness;

import com.digitallife.brain.Tools;

import org.json.JSONObject;

public final class ToolPipeline {

    public static final class Call {
        public String name;
        public JSONObject args;
        public String toolCallId;
        public String result;
        public String error;
        public boolean rejected;

        public Call(String name, JSONObject args, String toolCallId) {
            this.name = name;
            this.args = args != null ? args : new JSONObject();
            this.toolCallId = toolCallId;
        }
    }

    private final EventBus events;
    private Tools host;

    public ToolPipeline(EventBus events) {
        this.events = events;
    }

    public void setHost(Tools host) {
        this.host = host;
    }

    public Tools host() {
        return host;
    }

    public Call execute(String name, JSONObject args, String toolCallId) {
        Call call = new Call(name, args, toolCallId);
        if (events != null) {
            call = events.waterfall("tools/pre-execute", call);
        }
        if (call.rejected) {
            if (call.error == null) call.error = "工具调用被拒绝";
            if (events != null) events.waterfall("tools/post-execute", call);
            return call;
        }
        if (host == null) {
            call.error = "工具宿主未就绪";
            if (events != null) events.waterfall("tools/post-execute", call);
            return call;
        }
        if (events != null) call = events.waterfall("tools/execute", call);
        if (call.result == null && call.error == null) {
            try {
                call.result = host.executeSync(call.name, call.args);
            } catch (Exception e) {
                call.error = com.digitallife.ui.UiKit.safeMsg(e);
            }
        }
        if (events != null) call = events.waterfall("tools/post-execute", call);
        return call;
    }
}
