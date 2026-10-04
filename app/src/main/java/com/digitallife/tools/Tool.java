package com.digitallife.tools;

import org.json.JSONObject;

/**
 * 统一工具接口。内置工具、插件工具、MCP 远程工具都以同一形态暴露给 LLM。
 */
public interface Tool {

    /** 工具名（LLM function_call 中的 name） */
    String getName();

    /** OpenAI 兼容的 function schema（type=function，含 name/description/parameters） */
    JSONObject getSchema();

    /**
     * 执行工具。
     * @param args        LLM 传入的参数
     * @param progress    执行进度回调（可空），用于流式/分步回报
     * @return 回填给 LLM 的结果文本
     */
    String execute(JSONObject args, Progress progress) throws Exception;

    /**
     * 是否为外部/第三方工具（如 MCP 远程工具）。
     *
     * <p>外部工具来源不可信，工具循环默认要求用户审批（见
     * {@code harness.ToolApprovalPolicy}）；内置与本地插件工具保持 {@code false}，避免打扰。</p>
     */
    default boolean isExternal() {
        return false;
    }

    interface Progress {
        void onProgress(String message);
    }
}
