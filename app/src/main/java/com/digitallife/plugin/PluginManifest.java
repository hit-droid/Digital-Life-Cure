package com.digitallife.plugin;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件 manifest（plugin.json）数据模型。
 */
public class PluginManifest {
    public String name;
    public String version;
    public String author;
    public String description;
    public final List<ToolDef> tools = new ArrayList<>();
    public String type = "tools"; // tools | memory

    /** 一个工具定义（声明式） */
    public static class ToolDef {
        public String name;
        public String description;
        public JSONObject parameters;
        public String executor; // "builtin:echo" | "builtin:shell" | "lua:script.lua"

        ToolDef() {
        }
    }

    /** 从 plugin.json 字符串解析 */
    public static PluginManifest parse(String jsonText) throws Exception {
        JSONObject root = new JSONObject(jsonText);
        PluginManifest m = new PluginManifest();
        m.name = root.optString("name", "unknown");
        m.version = root.optString("version", "0.0.0");
        m.author = root.optString("author", "");
        m.description = root.optString("description", "");
        m.type = root.optString("type", "tools");
        JSONArray arr = root.optJSONArray("tools");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.getJSONObject(i);
                ToolDef td = new ToolDef();
                td.name = t.optString("name", "tool_" + i);
                td.description = t.optString("description", "");
                td.parameters = t.optJSONObject("parameters");
                td.executor = t.optString("executor", "builtin:echo");
                if (td.parameters == null) {
                    td.parameters = new JSONObject();
                    td.parameters.put("type", "object");
                    td.parameters.put("properties", new JSONObject());
                }
                m.tools.add(td);
            }
        }
        return m;
    }
}