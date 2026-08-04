package com.digitallife.mcp;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MCP 服务器配置管理器。
 * 保存用户添加的多个 MCP 服务器（名称/端点/API Key/命名空间），
 * 每个服务器连接后把其工具注册进全局 ToolRegistry。
 */
public class McpServerManager {

    private static final String PREF = "mcp_servers";
    private static final String KEY_LIST = "servers";

    private final SharedPreferences sp;
    private final List<McpClient> connected = new ArrayList<>();

    public McpServerManager(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static class McpServerConfig {
        public String id;
        public String name;
        public String endpoint;
        public String headerName;
        public String headerValue;
        public String namespace;

        public McpServerConfig() {
        }

        public McpServerConfig(String id, String name, String endpoint, String headerName, String headerValue, String namespace) {
            this.id = id;
            this.name = name;
            this.endpoint = endpoint;
            this.headerName = headerName;
            this.headerValue = headerValue;
            this.namespace = namespace;
        }
    }

    // ============ 配置持久化 ============

    public List<McpServerConfig> list() {
        List<McpServerConfig> list = new ArrayList<>();
        String raw = sp.getString(KEY_LIST, "");
        if (raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new McpServerConfig(
                        o.optString("id", UUID.randomUUID().toString()),
                        o.optString("name", ""),
                        o.optString("endpoint", ""),
                        o.optString("headerName", ""),
                        o.optString("headerValue", ""),
                        o.optString("namespace", "")));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public void save(McpServerConfig cfg) {
        List<McpServerConfig> all = list();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(cfg.id)) {
                all.set(i, cfg);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(cfg);
        persist(all);
    }

    public void remove(String id) {
        List<McpServerConfig> keep = new ArrayList<>();
        for (McpServerConfig c : list()) {
            if (!c.id.equals(id)) keep.add(c);
        }
        persist(keep);
    }

    private void persist(List<McpServerConfig> list) {
        JSONArray arr = new JSONArray();
        for (McpServerConfig c : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", c.id);
                o.put("name", c.name == null ? "" : c.name);
                o.put("endpoint", c.endpoint == null ? "" : c.endpoint);
                o.put("headerName", c.headerName == null ? "" : c.headerName);
                o.put("headerValue", c.headerValue == null ? "" : c.headerValue);
                o.put("namespace", c.namespace == null ? "" : c.namespace);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString(KEY_LIST, arr.toString()).apply();
    }

    // ============ 连接管理 ============

    /**
     * 连接一个服务器并把其工具注册进全局注册表。
     * 线程安全由调用方保证（建议后台线程）。
     */
    public void connect(McpServerConfig cfg) {
        McpClient client = new McpClient(cfg.endpoint, cfg.headerName, cfg.headerValue);
        client.connectAndRegister(cfg.namespace);
        connected.add(client);
    }

    /** 连接全部已保存服务器 */
    public void connectAll() {
        for (McpServerConfig c : list()) {
            try {
                connect(c);
            } catch (Exception e) {
                // 单个失败不影响其它服务器
                e.printStackTrace();
            }
        }
    }

    /** 断开并移除所有已注册的 MCP 工具 */
    public void disconnectAll() {
        connected.clear();
        com.digitallife.tools.ToolRegistry.getInstance().unregisterAll();
    }

    public String newId() {
        return UUID.randomUUID().toString();
    }
}
