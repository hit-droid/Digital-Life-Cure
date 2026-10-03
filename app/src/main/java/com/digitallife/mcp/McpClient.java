package com.digitallife.mcp;

import com.digitallife.tools.Tool;
import com.digitallife.tools.ToolRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP（Model Context Protocol）Streamable HTTP 客户端。
 * 连接 Smithery 或任何 MCP 服务器：initialize → tools/list → tools/call。
 * 拉取到的工具注册进全局 {@link ToolRegistry}，供所有大脑调用。
 */
public class McpClient {

    /** 服务器连接状态回调 */
    public interface ConnectionListener {
        void onConnected(int toolCount);
        void onError(String error);
    }

    /** JSON-RPC id 用小整数递增：System.nanoTime() 超出 double 精度，org.json 读写会失真 */
    private static final java.util.concurrent.atomic.AtomicLong REQUEST_ID =
            new java.util.concurrent.atomic.AtomicLong(0);

    private final String endpoint;
    private final String headerName;
    private final String headerValue;
    private String sessionId;
    /** 本客户端注册进全局注册表的工具名（带 namespace 前缀），disconnect 时精确注销 */
    private final List<String> registeredToolNames = new ArrayList<>();

    public McpClient(String endpoint, String headerName, String headerValue) {
        this.endpoint = endpoint;
        this.headerName = headerName;
        this.headerValue = headerValue;
    }

    /** 服务器端点地址（用于删除时精确匹配已连接实例） */
    public String getEndpoint() {
        return endpoint;
    }

    // ============ 握手与工具发现 ============

    /** 连接服务器：握手 + 拉取工具列表，并注册到全局 ToolRegistry（前缀 namespace_） */
    public void connectAndRegister(String namespace) {
        try {
            initialize();
            List<McpToolSpec> specs = listTools();
            ToolRegistry reg = ToolRegistry.getInstance();
            for (McpToolSpec spec : specs) {
                McpTool tool = new McpTool(this, namespace, spec);
                reg.register(tool);
                registeredToolNames.add(tool.getName());
            }
        } catch (Exception e) {
            throw new RuntimeException("MCP 连接失败: " + com.digitallife.ui.UiKit.safeMsg(e), e);
        }
    }

    /** 注销本客户端注册的所有工具（仅自己的，不动全局其它工具） */
    public void unregisterTools() {
        ToolRegistry reg = ToolRegistry.getInstance();
        for (String name : registeredToolNames) {
            reg.unregister(name);
        }
        registeredToolNames.clear();
    }

    private void initialize() throws Exception {
        JSONObject params = new JSONObject();
        JSONObject clientInfo = new JSONObject();
        clientInfo.put("name", "digitallife");
        clientInfo.put("version", "1.0.0");
        params.put("protocolVersion", "2025-03-26");
        params.put("capabilities", new JSONObject());
        params.put("clientInfo", clientInfo);
        request("initialize", params);
        // 注意：不要在此清空 sessionId——request() 已从响应头取出 Mcp-Session-Id，
        // 标准 Streamable HTTP 服务器要求 initialize 之后的每个请求都回带它。
        // 协议要求 initialize 之后发送 initialized 通知（best-effort，服务器不支持也不阻断）。
        notify("notifications/initialized", new JSONObject());
    }

    private List<McpToolSpec> listTools() throws Exception {
        JSONObject res = request("tools/list", new JSONObject());
        List<McpToolSpec> specs = new ArrayList<>();
        JSONArray tools = res.optJSONArray("tools");
        if (tools == null) return specs;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.getJSONObject(i);
            specs.add(new McpToolSpec(
                    t.optString("name"),
                    t.optString("description", ""),
                    t.optJSONObject("inputSchema")));
        }
        return specs;
    }

    /** 调用远程工具 */
    public String callTool(String name, JSONObject args) throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", name);
        params.put("arguments", args != null ? args : new JSONObject());
        JSONObject res = request("tools/call", params);
        // 结果：structuredContent 或 content[]（text）
        JSONArray content = res.optJSONArray("content");
        StringBuilder sb = new StringBuilder();
        if (content != null) {
            for (int i = 0; i < content.length(); i++) {
                JSONObject c = content.getJSONObject(i);
                if ("text".equals(c.optString("type")) && c.has("text")) {
                    sb.append(c.optString("text"));
                }
            }
        }
        JSONObject structured = res.optJSONObject("structuredContent");
        if (structured != null) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(structured.toString());
        }
        if (res.optBoolean("isError", false)) {
            return "[MCP 工具错误] " + (sb.length() > 0 ? sb.toString() : "无返回");
        }
        return sb.length() > 0 ? sb.toString() : "（工具无文本返回）";
    }

    /** JSON-RPC 请求：发送并解析出 result（无 result 时返回空对象，避免调用方 NPE） */
    private JSONObject request(String method, JSONObject params) throws Exception {
        long id = REQUEST_ID.incrementAndGet();
        JSONObject body = new JSONObject();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.put("params", params != null ? params : new JSONObject());

        JSONObject json = McpResponseParser.parse(post(body), id);
        JSONObject error = json.optJSONObject("error");
        if (error != null) {
            throw new Exception(error.optString("message", "MCP 错误"));
        }
        JSONObject result = json.optJSONObject("result");
        return result != null ? result : new JSONObject();
    }

    /**
     * JSON-RPC 通知（无 id、无响应）。协议要求 initialize 后发送 notifications/initialized；
     * 通知是 best-effort，服务器返回错误也不影响后续调用。
     */
    private void notify(String method, JSONObject params) {
        try {
            JSONObject body = new JSONObject();
            body.put("jsonrpc", "2.0");
            body.put("method", method);
            if (params != null) body.put("params", params);
            post(body);
        } catch (Exception ignored) {
            // 通知失败不阻断握手
        }
    }

    /** 发送 JSON-RPC 报文，返回响应体文本（SSE 或 JSON 均由调用方解析）。 */
    private String post(JSONObject body) throws Exception {
        URL url = new URL(endpoint);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            if (headerName != null && !headerName.isEmpty() && headerValue != null && !headerValue.isEmpty()) {
                conn.setRequestProperty(headerName, headerValue);
            }
            if (sessionId != null) {
                conn.setRequestProperty("Mcp-Session-Id", sessionId);
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String responseText = readStream(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            String sessionHeader = conn.getHeaderField("Mcp-Session-Id");
            if (sessionHeader != null && !sessionHeader.isEmpty()) {
                sessionId = sessionHeader;
            }
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + ": " + responseText);
            }
            return responseText;
        } finally {
            // 异常路径也必须释放连接，避免 HttpURLConnection 泄漏
            if (conn != null) conn.disconnect();
        }
    }

    private String readStream(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        return sb.toString().trim();
    }

    // ============ 工具规格 ============

    public static class McpToolSpec {
        public final String name;
        public final String description;
        public final JSONObject inputSchema;

        McpToolSpec(String name, String description, JSONObject inputSchema) {
            this.name = name;
            this.description = description == null ? "" : description;
            this.inputSchema = inputSchema;
        }
    }
}
