package com.digitallife.util;

/**
 * 一个 API 配置项（Profile）。
 * 每个大脑（对话 AI / 护理 AI）可维护多个 Profile，随时切换。
 */
public class ApiProfile {
    public String id;
    public String name;
    public String baseUrl;
    public String apiKey;
    public String model;

    public ApiProfile() {
    }

    public ApiProfile(String id, String name, String baseUrl, String apiKey, String model) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean isComplete() {
        return baseUrl != null && !baseUrl.trim().isEmpty()
                && apiKey != null && !apiKey.trim().isEmpty()
                && model != null && !model.trim().isEmpty();
    }
}
