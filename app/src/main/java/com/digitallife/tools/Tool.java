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

    interface Progress {
        void onProgress(String message);
    }
}
