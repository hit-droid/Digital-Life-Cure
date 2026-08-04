package com.digitallife.plugin;

import com.digitallife.tools.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 声明式插件工具适配器。将 plugin.json 中的工具定义包装为 Tool 接口。
 * 支持 executor: builtin:echo, builtin:shell, lua:script.lua
 */
public class PluginTool implements Tool {

    private final String qualifiedName;
    private final String description;
    private final JSONObject parameters;
    private final String executor;
    private final String pluginDir;

    public PluginTool(PluginManifest manifest, PluginManifest.ToolDef def, String pluginDir) {
        this.qualifiedName = manifest.name + "_" + def.name;
        this.description = def.description;
        this.parameters = def.parameters != null ? def.parameters : new JSONObject();
        this.executor = def.executor;
        this.pluginDir = pluginDir;
    }

    PluginTool(String prefix, PluginManifest.ToolDef def, String pluginDir) {
        this.qualifiedName = prefix + "_" + def.name;
        this.description = def.description;
        this.parameters = def.parameters != null ? def.parameters : new JSONObject();
        this.executor = def.executor;
        this.pluginDir = pluginDir;
    }

    @Override
    public String getName() {
        return qualifiedName;
    }

    @Override
    public JSONObject getSchema() {
        JSONObject schema = new JSONObject();
        try {
            schema.put("type", "function");
            schema.put("function", new JSONObject()
                    .put("name", qualifiedName)
                    .put("description", description)
                    .put("parameters", parameters));
        } catch (Exception ignored) {
        }
        return schema;
    }

    @Override
    public String execute(JSONObject args, Progress progress) throws Exception {
        if (executor == null || executor.startsWith("builtin:echo")) {
            if (progress != null) progress.onProgress("echo: " + (args != null ? args.toString() : "{}"));
            return "echo: " + (args != null ? args.toString() : "{}");
        }

        if (executor.startsWith("builtin:shell")) {
            String cmd = args != null ? args.optString("command", "") : "";
            if (cmd.isEmpty()) {
                return "error: no command specified";
            }
            if (progress != null) progress.onProgress("执行: " + cmd);
            Process proc = Runtime.getRuntime().exec(cmd);
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
            int exit = proc.waitFor();
            return "exit=" + exit + "\n" + output.toString().trim();
        }

        if (executor.startsWith("lua:")) {
            return "error: Lua executor not yet implemented";
        }

        return "error: unknown executor: " + executor;
    }
}