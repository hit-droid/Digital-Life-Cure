package com.digitallife.brain;

import com.digitallife.tools.ToolRegistry;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 大脑内置工具集（表现层工具：表情/动作/说话/移动等）。
 * 与全局 {@link ToolRegistry} 聚合：LLM 可见工具 = 内置工具 + 全局（MCP/插件）工具。
 */
public class Tools {

    public interface Callback {
        void onResult(String toolName, JSONObject args, String resultText, String error);
    }

    private final List<JSONObject> schemas = new ArrayList<>();
    private final List<Executor> executors = new ArrayList<>();

    public interface Executor {
        String execute(JSONObject args) throws Exception;
    }

    public Tools() {
        register("set_expression",
                "设置角色的面部表情，用于表达当前情绪",
                new String[]{"emotion", "intensity"},
                args -> {
                    String e = args.optString("emotion", "neutral");
                    double intensity = args.optDouble("intensity", 0.5);
                    if (onExpression != null) onExpression.onExpression(e, (float) intensity);
                    return "表情已切换为 " + e;
                });
        register("play_animation",
                "播放角色的身体动作动画",
                new String[]{"animation"},
                args -> {
                    String a = args.optString("animation", "idle");
                    if (onAnimation != null) onAnimation.onAnimation(a);
                    return "已播放动作 " + a;
                });
        register("say",
                "让角色说出一句话（显示气泡+语音合成）",
                new String[]{"text"},
                args -> {
                    String text = args.optString("text", "");
                    if (onSpeak != null) onSpeak.onSpeak(text);
                    return "已说：" + text;
                });
        register("get_time",
                "获取当前时间，用于问候、报时等",
                new String[]{},
                args -> {
                    return "当前时间：" + (onTime != null ? onTime.onTime() : "");
                });
        register("move",
                "让角色移动到屏幕指定位置",
                new String[]{"x", "y"},
                args -> {
                    double x = args.optDouble("x", 50);
                    double y = args.optDouble("y", 80);
                    if (onMove != null) onMove.onMove((float) x, (float) y);
                    return "已移动到 (" + x + ", " + y + ")";
                });
    }

    private void register(String name, String desc, String[] required, Executor ex) {
        try {
            JSONObject schema = new JSONObject();
            JSONObject fn = new JSONObject();
            fn.put("name", name);
            fn.put("description", desc);
            JSONObject params = new JSONObject();
            params.put("type", "object");
            JSONObject props = new JSONObject();
            for (String r : required) {
                JSONObject prop = new JSONObject();
                prop.put("type", "string");
                switch (r) {
                    case "emotion":
                        prop.put("type", "string");
                        prop.put("enum", new String[]{"neutral", "happy", "sad", "angry", "surprised", "shy", "excited", "sleepy"});
                        break;
                    case "intensity":
                        prop.put("type", "number");
                        prop.put("minimum", 0);
                        prop.put("maximum", 1);
                        break;
                    case "animation":
                        prop.put("type", "string");
                        prop.put("enum", new String[]{"idle", "wave", "clap", "point", "tilt_head", "stretch", "bounce", "sit"});
                        break;
                    case "x":
                    case "y":
                        prop.put("type", "number");
                        break;
                    default:
                        prop.put("type", "string");
                        break;
                }
                props.put(r, prop);
            }
            params.put("properties", props);
            org.json.JSONArray req = new org.json.JSONArray();
            for (String r : required) req.put(r);
            if (req.length() > 0) params.put("required", req);
            fn.put("parameters", params);
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", fn);
            schemas.add(tool);
            executors.add(ex);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 生成 LLM 需要的 tools JSON 数组（内置 + 全局 MCP/插件工具） */
    public org.json.JSONArray toJsonArray() {
        org.json.JSONArray arr = new org.json.JSONArray();
        for (JSONObject s : schemas) arr.put(s);
        org.json.JSONArray global = ToolRegistry.getInstance().toJsonArray();
        for (int i = 0; i < global.length(); i++) arr.put(global.opt(i));
        return arr;
    }

    /** 生成 tools 的描述文本（注入 system prompt 用） */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (JSONObject t : schemas) {
            JSONObject fn = t.optJSONObject("function");
            sb.append("- ").append(fn.optString("name"))
              .append(": ").append(fn.optString("description")).append("\n");
        }
        String globalDesc = ToolRegistry.getInstance().describe();
        if (!globalDesc.trim().isEmpty()) sb.append(globalDesc);
        return sb.toString();
    }

    /** v1.26.0：返回所有已知工具名（用于容错匹配） */
    public java.util.List<String> allToolNames() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < schemas.size(); i++) {
            JSONObject t = schemas.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            if (fn == null) continue;
            String name = fn.optString("name");
            if (!name.isEmpty()) out.add(name);
        }
        // 追加全局工具名
        try {
            for (String n : ToolRegistry.getInstance().allToolNames()) {
                if (n != null && !n.isEmpty() && !out.contains(n)) out.add(n);
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** 执行一次工具调用（内置优先，未命中则委托全局注册表） */
    public void execute(String name, JSONObject args, Callback cb) {
        for (int i = 0; i < schemas.size(); i++) {
            if (schemas.get(i).optJSONObject("function").optString("name").equals(name)) {
                // Hook Runner: pre
                String preReject = com.digitallife.tools.HookRunner.getInstance().runPre(name, args);
                if (preReject != null) {
                    cb.onResult(name, args, null, preReject);
                    com.digitallife.tools.HookRunner.getInstance().runError(name, args, preReject);
                    return;
                }
                Executor ex = executors.get(i);
                String res = null;
                String err = null;
                long t0 = System.currentTimeMillis();
                try {
                    res = ex.execute(args != null ? args : new JSONObject());
                } catch (Exception e) {
                    err = com.digitallife.ui.UiKit.safeMsg(e);
                }
                long dt = System.currentTimeMillis() - t0;
                cb.onResult(name, args, res, err);
                // Hook Runner: post
                String post = com.digitallife.tools.HookRunner.getInstance()
                        .runPost(name, args, res, err);
                if (post != null && !post.isEmpty() && onToolSideEffect != null) {
                    onToolSideEffect.onToolSideEffect(name, post, dt);
                }
                if (err != null) {
                    com.digitallife.tools.HookRunner.getInstance().runError(name, args, err);
                }
                return;
            }
        }
        // 全局工具（MCP/插件）
        if (ToolRegistry.getInstance().find(name) != null) {
            String res = ToolRegistry.getInstance().execute(name, args, null);
            if (res.startsWith("工具执行失败") || res.startsWith("未知工具")) {
                cb.onResult(name, args, null, res);
            } else {
                cb.onResult(name, args, res, null);
            }
            return;
        }
        cb.onResult(name, args, "未知工具: " + name, null);
    }

    /** 工具副作用回调（Hook Runner post 钩子注入到 LLM） */
    public interface ToolSideEffectListener {
        void onToolSideEffect(String toolName, String sideEffect, long durationMs);
    }
    private ToolSideEffectListener onToolSideEffect;
    public void setToolSideEffectListener(ToolSideEffectListener l) { this.onToolSideEffect = l; }

    // ============= 工具行为回调（由 AgentBrain/表现层注入） =============
    public interface ExpressionListener { void onExpression(String emotion, float intensity); }
    public interface AnimationListener { void onAnimation(String anim); }
    public interface SpeakListener { void onSpeak(String text); }
    public interface TimeListener { String onTime(); }
    public interface MoveListener { void onMove(float x, float y); }

    private ExpressionListener onExpression;
    private AnimationListener onAnimation;
    private SpeakListener onSpeak;
    private TimeListener onTime;
    private MoveListener onMove;

    public void setExpressionListener(ExpressionListener l) { this.onExpression = l; }
    public void setAnimationListener(AnimationListener l) { this.onAnimation = l; }
    public void setSpeakListener(SpeakListener l) { this.onSpeak = l; }
    public void setTimeListener(TimeListener l) { this.onTime = l; }
    public void setMoveListener(MoveListener l) { this.onMove = l; }
}
