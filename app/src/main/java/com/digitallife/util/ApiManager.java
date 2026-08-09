package com.digitallife.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API Profile 管理器。
 * 按大脑分 scope（chat=对话 AI，care=护理 AI）分别保存多套 API 配置，
 * 支持增删改查、设置当前生效配置，并把当前配置同步到 Settings 供运行层读取。
 */
public class ApiManager {

    public static final String SCOPE_CHAT = "chat";
    public static final String SCOPE_CARE = "care";

    private static final String PREF = "api_profiles";
    private static final String KEY_PROFILES = "_profiles";
    private static final String KEY_CURRENT = "_current";

    private final SharedPreferences sp;
    /** 密钥池轮换游标（静态跨实例递增，保证每次 new ApiManager 后仍能逐次轮换） */
    private static final AtomicInteger keyCursor = new AtomicInteger(0);

    public ApiManager(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ============ 查询 ============

    public List<ApiProfile> list(String scope) {
        List<ApiProfile> list = new ArrayList<>();
        String raw = sp.getString(scope + KEY_PROFILES, "");
        if (raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                ApiProfile p = new ApiProfile(
                        o.optString("id", UUID.randomUUID().toString()),
                        o.optString("name", ""),
                        o.optString("baseUrl", ""),
                        o.optString("apiKey", ""),
                        o.optString("model", ""));
                JSONArray keys = o.optJSONArray("apiKeys");
                if (keys != null && keys.length() > 0) {
                    List<String> keyList = new ArrayList<>();
                    for (int j = 0; j < keys.length(); j++) {
                        String k = keys.optString(j, "");
                        if (!k.trim().isEmpty()) keyList.add(k.trim());
                    }
                    p.setApiKeys(keyList);
                }
                list.add(p);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public ApiProfile find(String scope, String id) {
        for (ApiProfile p : list(scope)) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /** 当前生效的 Profile；无显式选择时取第一个 */
    public ApiProfile getCurrent(String scope) {
        String curId = sp.getString(scope + KEY_CURRENT, "");
        ApiProfile p = curId.isEmpty() ? null : find(scope, curId);
        if (p == null) {
            List<ApiProfile> all = list(scope);
            if (!all.isEmpty()) {
                p = all.get(0);
                setCurrent(scope, p.id);
            }
        }
        return p;
    }

    public String getCurrentId(String scope) {
        return sp.getString(scope + KEY_CURRENT, "");
    }

    // ============ 密钥池 ============

    /** 返回配置的密钥池（多个 key 逐次轮换）；无密钥池时回退单 key */
    public String nextKey(String scope, String profileId) {
        ApiProfile p = find(scope, profileId);
        if (p == null) return "";
        List<String> keys = p.effectiveKeys();
        if (keys.isEmpty()) return "";
        int idx = Math.floorMod(keyCursor.getAndIncrement(), keys.size());
        return keys.get(idx);
    }

    // ============ 写入 ============

    public String newId() {
        return UUID.randomUUID().toString();
    }

    public void setCurrent(String scope, String id) {
        sp.edit().putString(scope + KEY_CURRENT, id).apply();
    }

    /** 新增或更新一个 Profile（按 id 判断） */
    public void save(String scope, ApiProfile profile) {
        List<ApiProfile> all = list(scope);
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(profile.id)) {
                all.set(i, profile);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(profile);
        persist(scope, all);
        // 首个配置自动设为当前
        if (getCurrent(scope) == null) {
            setCurrent(scope, profile.id);
        }
    }

    public void remove(String scope, String id) {
        List<ApiProfile> all = list(scope);
        List<ApiProfile> keep = new ArrayList<>();
        for (ApiProfile p : all) {
            if (!p.id.equals(id)) keep.add(p);
        }
        persist(scope, keep);
        if (id.equals(getCurrentId(scope))) {
            // 删除的是当前配置：切到剩余第一个
            setCurrent(scope, keep.isEmpty() ? "" : keep.get(0).id);
        }
    }

    private void persist(String scope, List<ApiProfile> list) {
        JSONArray arr = new JSONArray();
        for (ApiProfile p : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name == null ? "" : p.name);
                o.put("baseUrl", p.baseUrl == null ? "" : p.baseUrl);
                o.put("apiKey", p.apiKey == null ? "" : p.apiKey);
                o.put("model", p.model == null ? "" : p.model);
                JSONArray keys = new JSONArray();
                if (p.apiKeys != null) {
                    for (String k : p.apiKeys) {
                        if (k != null && !k.trim().isEmpty()) keys.put(k.trim());
                    }
                }
                o.put("apiKeys", keys);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString(scope + KEY_PROFILES, arr.toString()).apply();
    }

    // ============ 同步到 Settings ============

    /** 把 scope 的当前配置写入 Settings 兼容字段，供 AICore 等运行层读取 */
    public void syncCurrentToSettings(String scope, Settings settings) {
        ApiProfile cur = getCurrent(scope);
        if (cur == null) return;
        settings.setApiBase(cur.baseUrl);
        settings.setApiKey(cur.primaryKey());
        settings.setModel(cur.model);
    }
}
