package com.digitallife.mcp;

import com.digitallife.mcp.McpClient.McpToolSpec;
import com.digitallife.tools.Tool;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MCP 远程工具到本地 Tool 的适配器。
 * 工具名加 namespace 前缀，避免与内置/其它服务器冲突；
 * execute 转发到远程服务器 tools/call。
 */
public class McpTool implements Tool {

    private final McpClient client;
    private final String qualifiedName;
    private final String remoteName;
    private final JSONObject schema;

    public McpTool(McpClient client, String namespace, McpToolSpec spec) {
        this.client = client;
        this.remoteName = spec.name;
        this.qualifiedName = (namespace == null || namespace.isEmpty())
                ? spec.name : namespace + "_" + spec.name;
        this.schema = buildSchema(spec);
    }

    @Override
    public String getName() {
        return qualifiedName;
    }

    /** 远程 MCP 服务器属第三方、不可信：默认需要用户审批后才执行 */
    @Override
    public boolean isExternal() {
        return true;
    }

    @Override
    public JSONObject getSchema() {
        return schema;
    }

    @Override
    public String execute(JSONObject args, Progress progress) throws Exception {
        if (client == null) {
            return "（远程工具未绑定执行客户端）";
        }
        return client.callTool(remoteName, args);
    }

    private JSONObject buildSchema(McpToolSpec spec) {
        try {
            JSONObject fn = new JSONObject();
            fn.put("name", qualifiedName);
            fn.put("description", spec.description);
            JSONObject params = spec.inputSchema;
            if (params == null) {
                params = new JSONObject();
                params.put("type", "object");
                params.put("properties", new JSONObject());
            }
            // 仅透传 object 类型参数结构
            fn.put("parameters", params);
            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", fn);
            return tool;
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
