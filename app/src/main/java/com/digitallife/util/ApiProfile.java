package com.digitallife.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一个 API 配置项（Profile）。
 * 每个大脑（对话 AI / 护理 AI）可维护多个 Profile，随时切换。
 * 支持密钥池：apiKeys 为多个 key（按序自动轮换），apiKey 作为单 key 兼容字段保留。
 */
public class ApiProfile {
    public String id;
    public String name;
    public String baseUrl;
    /** 单 key 兼容字段：密钥池为空时作为主 key 使用 */
    public String apiKey;
    public String model;
    /** 密钥池：多个 API Key，请求时自动轮换 */
    public List<String> apiKeys = new ArrayList<>();

    public ApiProfile() {
    }

    public ApiProfile(String id, String name, String baseUrl, String apiKey, String model) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        addKey(apiKey);
    }

    /** 追加一个 key 到密钥池（忽略空白） */
    public void addKey(String key) {
        if (key == null) return;
        String k = key.trim();
        if (k.isEmpty()) return;
        if (apiKeys == null) apiKeys = new ArrayList<>();
        if (!apiKeys.contains(k)) apiKeys.add(k);
    }

    public void setApiKeys(List<String> keys) {
        apiKeys = new ArrayList<>();
        if (keys != null) {
            for (String k : keys) addKey(k);
        }
    }

    /** 参与轮换的 key 列表：密钥池非空用密钥池，否则用单 key 字段 */
    public List<String> effectiveKeys() {
        if (apiKeys != null && !apiKeys.isEmpty()) return apiKeys;
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            return Collections.singletonList(apiKey.trim());
        }
        return new ArrayList<>();
    }

    /** 主 key（用于测试连接等单 key 场景） */
    public String primaryKey() {
        List<String> keys = effectiveKeys();
        return keys.isEmpty() ? "" : keys.get(0);
    }

    public boolean isComplete() {
        return baseUrl != null && !baseUrl.trim().isEmpty()
                && !effectiveKeys().isEmpty()
                && model != null && !model.trim().isEmpty();
    }
}
