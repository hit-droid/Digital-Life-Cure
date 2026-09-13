package com.digitallife.harness;

/**
 * LLM 能力 seam 的工厂角色：让子智能体等 Consumer 能拿到独立实例，
 * 而不必知道主对话用的是哪份配置。
 */
public interface LlmFactory {

    LlmAdapter create();
}
