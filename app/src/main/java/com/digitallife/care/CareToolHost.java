package com.digitallife.care;

import com.digitallife.brain.Tools;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 把护理工具表适配成 harness 的 {@link Tools} seam。
 * CareTools 保持原有的 schema 与 dispatch，只是换个入口被 agent-loop 调用。
 */
public final class CareToolHost extends Tools {

    private final CareTools care;
    private final CareExecutor executor;

    public CareToolHost(CareTools care, CareExecutor executor) {
        super(true);
        this.care = care;
        this.executor = executor;
    }

    public CareTools care() {
        return care;
    }

    @Override
    public JSONArray toJsonArray() {
        JSONArray base = care == null ? new JSONArray() : care.getToolSchemas();
        return appendPlayMotion(base);
    }

    @Override
    public String describe() {
        JSONArray schemas = toJsonArray();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < schemas.length(); i++) {
            JSONObject t = schemas.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            if (fn == null) continue;
            sb.append("- ").append(fn.optString("name"))
                    .append(": ").append(fn.optString("description")).append("\n");
        }
        return sb.toString();
    }

    @Override
    public void execute(String name, JSONObject args, Callback cb) {
        JSONObject a = args != null ? args : new JSONObject();
        if ("play_motion".equals(name)) {
            // 实时播放动作：走执行层，桌宠立即响应
            String action = a.optString("action", "");
            if (executor == null) {
                // 不能静默成功：否则模型会以为动作已播放，后续决策全部基于假前提
                cb.onResult(name, a, null, "执行层不可用，无法播放动作");
                return;
            }
            try {
                executor.playAction(action);
                cb.onResult(name, a, "已在桌宠上播放动作: " + action, null);
            } catch (Exception e) {
                cb.onResult(name, a, null, com.digitallife.ui.UiKit.safeMsg(e));
            }
            return;
        }
        if (care == null) {
            cb.onResult(name, a, null, "护理工具表不可用");
            return;
        }
        String result = care.execute(name, a);
        cb.onResult(name, a, truncate(result), null);
    }

    /** 工具结果上限：护理工具（如 analyze_model）输出可能极长，截断后才喂给模型 */
    static final int MAX_RESULT_CHARS = 2000;

    static String truncate(String result) {
        if (result == null) return null;
        if (result.length() <= MAX_RESULT_CHARS) return result;
        return result.substring(0, MAX_RESULT_CHARS) + "\n...（结果已截断）";
    }

    /** 测试钩子：暴露截断契约，避免测试复制一份实现 */
    static String truncateForTest(String raw) {
        return truncate(raw);
    }

    /** 追加「实时播放动作」工具：护理大脑可直接让桌宠播放某个动作 */
    private static JSONArray appendPlayMotion(JSONArray base) {
        JSONArray out = base == null ? new JSONArray() : base;
        try {
            JSONObject schema = new JSONObject();
            schema.put("type", "function");
            JSONObject fn = new JSONObject();
            fn.put("name", "play_motion");
            fn.put("description", "立即让桌宠播放某个动作（如挥手、拍手、跳舞、惊讶等），用于实时预览/执行");
            JSONObject params = new JSONObject();
            params.put("type", "object");
            JSONObject props = new JSONObject();
            JSONObject action = new JSONObject();
            action.put("type", "string");
            action.put("description", "动作名");
            props.put("action", action);
            params.put("properties", props);
            params.put("required", new JSONArray(new String[]{"action"}));
            fn.put("parameters", params);
            schema.put("function", fn);
            out.put(schema);
        } catch (Exception ignored) {
        }
        return out;
    }
}
