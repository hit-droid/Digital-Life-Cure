package com.digitallife.plugin;

import com.digitallife.tools.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

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

    /** shell 执行白名单：仅允许只读、无副作用命令，降低 AI/插件被诱导执行任意命令的风险 */
    private static final Set<String> SAFE_COMMANDS = new HashSet<>(Arrays.asList(
            "echo", "ls", "cat", "pwd", "date", "whoami", "uname", "df", "free",
            "uptime", "printenv", "env", "ps", "head", "tail", "grep", "wc"
    ));

    /** 危险模式：shell 元字符、危险命令、路径穿越 */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "[;|&><`$]|\\b(rm|mv|mkfs|shutdown|reboot|sudo|su|chmod|chown|dd|curl|wget|nc|telnet|ssh|scp|python|python3|perl|bash|sh|node)\\b|\\.\\.");

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
            if (!isSafeShellCommand(cmd)) {
                return "error: 命令被安全策略拒绝（仅允许只读白名单命令）";
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

    /**
     * shell 命令安全校验：命令必须在白名单内、不得含危险模式、
     * 不得用绝对路径/相对路径执行可执行文件（防止绕过白名单）。
     */
    private static boolean isSafeShellCommand(String cmd) {
        String t = cmd == null ? "" : cmd.trim();
        if (t.isEmpty()) return false;
        if (FORBIDDEN.matcher(t).find()) return false;
        String first = t.split("\\s+")[0];
        if (first.contains("/")) return false;
        return SAFE_COMMANDS.contains(first);
    }
}