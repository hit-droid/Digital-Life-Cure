package com.digitallife.harness;

import com.digitallife.brain.LLMClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * LLM 能力 seam 的 Service Definition。
 * Provider（LLMClient）实现它，Consumer（AgentLoop）只依赖这个接口，
 * 因此循环可以在没有真实网络的测试里被替换与驱动。
 */
public interface LlmAdapter {

    void chatStream(List<LLMClient.ChatMessage> messages, JSONObject extraSystem,
                    LLMClient.StreamListener listener);

    void setTools(JSONArray tools);

    void cancel();
}
