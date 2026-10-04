package com.digitallife.mcp;

import android.content.Context;
import android.content.SharedPreferences;

import com.digitallife.util.SecureStore;

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
    /** v1.143.0：headerValue 多为 Authorization 令牌，落盘改走设备级加密（与 API Key 同策略） */
    private final SecureStore secure = new SecureStore();
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
                        // 历史明文（无 enc:v1: 前缀）原样返回，下次 save 时透明加密回写
                        secure.decrypt(o.optString("headerValue", "")),
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
        McpServerConfig removed = null;
        List<McpServerConfig> keep = new ArrayList<>();
        for (McpServerConfig c : list()) {
            if (c.id.equals(id)) {
                removed = c;
            } else {
                keep.add(c);
            }
        }
        persist(keep);
        // 同步断开已连接实例并注销其注册的工具，避免删除后工具残留
        if (removed != null && removed.endpoint != null) {
            java.util.Iterator<McpClient> it = connected.iterator();
            while (it.hasNext()) {
                McpClient cl = it.next();
                if (removed.endpoint.equals(cl.getEndpoint())) {
                    try {
                        cl.unregisterTools();
                    } catch (Exception ignored) {
                    }
                    it.remove();
                }
            }
        }
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
                o.put("headerValue", secure.encrypt(c.headerValue == null ? "" : c.headerValue));
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

    /** 断开并移除所有 MCP 工具（仅注销 MCP 注册的工具，保留内置/插件工具） */
    public void disconnectAll() {
        for (McpClient c : connected) {
            try {
                c.unregisterTools();
            } catch (Exception ignored) {
            }
        }
        connected.clear();
    }

    public String newId() {
        return UUID.randomUUID().toString();
    }
}
