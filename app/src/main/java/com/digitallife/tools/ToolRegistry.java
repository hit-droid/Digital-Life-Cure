package com.digitallife.tools;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 全局工具注册表（单例）。
 * 内置工具、插件工具、MCP 远程工具统一注册到这里，
 * 各大脑（对话 AI / 护理 AI）通过它拿到合并后的工具列表并执行。
 */
public class ToolRegistry {

    private static final ToolRegistry INSTANCE = new ToolRegistry();

    private final ConcurrentMap<String, Tool> tools = new ConcurrentHashMap<>();

    private ToolRegistry() {
    }

    public static ToolRegistry getInstance() {
        return INSTANCE;
    }

    // ============ 注册 / 注销 ============

    public void register(Tool tool) {
        if (tool == null || tool.getName() == null) return;
        tools.put(tool.getName(), tool);
    }

    public void unregister(String name) {
        if (name == null) return;
        tools.remove(name);
    }

    public void unregisterAll() {
        tools.clear();
    }

    public Tool find(String name) {
        return name == null ? null : tools.get(name);
    }

    public List<Tool> all() {
        return new ArrayList<>(tools.values());
    }

    /** v1.26.0：返回所有已注册工具名（用于容错匹配） */
    public List<String> allToolNames() {
        List<String> out = new ArrayList<>();
        for (Tool t : tools.values()) {
            if (t != null && t.getName() != null) out.add(t.getName());
        }
        return out;
    }

    public boolean isEmpty() {
        return tools.isEmpty();
    }

    // ============ LLM 侧描述 ============

    /** 合并后的 tools 数组（供 LLM function calling） */
    public JSONArray toJsonArray() {
        JSONArray arr = new JSONArray();
        for (Tool t : tools.values()) {
            JSONObject schema = t.getSchema();
            if (schema != null) arr.put(schema);
        }
        return arr;
    }

    /** 工具清单描述（注入 system prompt 用） */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Tool t : tools.values()) {
            String name = t.getName();
            String desc = schemaDescription(t.getSchema());
            sb.append("- ").append(name).append(": ").append(desc).append("\n");
        }
        return sb.toString();
    }

    private String schemaDescription(JSONObject schema) {
        if (schema == null) return "";
        JSONObject fn = schema.optJSONObject("function");
        return fn != null ? fn.optString("description", "") : "";
    }

    // ============ 执行 ============

    /**
     * 执行工具（带统一异常处理）。
     * @return 结果文本；工具不存在返回错误说明
     */
    public String execute(String name, JSONObject args, Tool.Progress progress) {
        Tool tool = find(name);
        if (tool == null) {
            return "未知工具: " + name;
        }
        try {
            return tool.execute(args != null ? args : new JSONObject(), progress);
        } catch (Exception e) {
            return "工具执行失败: " + name + " -> " + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }
}
