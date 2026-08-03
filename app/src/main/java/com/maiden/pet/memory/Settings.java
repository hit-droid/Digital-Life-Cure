package com.maiden.pet.memory;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 用户配置持久化：API 连接、角色名字、互动偏好。
 */
public class Settings {
    private static final String PREF = "pet_settings";
    private final SharedPreferences sp;
    private final Context ctx;

    public Settings(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 访问其它 SharedPreferences 文件（如记忆库） */
    public SharedPreferences getPrefs(String name) {
        return ctx.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    public Context getContext() {
        return ctx;
    }

    public String getApiBase() { return sp.getString("api_base", ""); }
    public void setApiBase(String v) { sp.edit().putString("api_base", v).apply(); }

    public String getApiKey() { return sp.getString("api_key", ""); }
    public void setApiKey(String v) { sp.edit().putString("api_key", v).apply(); }

    public String getModel() { return sp.getString("model", ""); }
    public void setModel(String v) { sp.edit().putString("model", v).apply(); }

    public String getPetName() { return sp.getString("pet_name", "小汐"); }
    public void setPetName(String v) { sp.edit().putString("pet_name", v).apply(); }

    public boolean isVoiceEnabled() { return sp.getBoolean("voice_enabled", true); }
    public void setVoiceEnabled(boolean v) { sp.edit().putBoolean("voice_enabled", v).apply(); }

    public boolean isProactiveEnabled() { return sp.getBoolean("proactive_enabled", true); }
    public void setProactiveEnabled(boolean v) { sp.edit().putBoolean("proactive_enabled", v).apply(); }

    public int getScale() { return sp.getInt("scale", 100); }
    public void setScale(int v) { sp.edit().putInt("scale", v).apply(); }

    public float getOverlayX() { return sp.getFloat("overlay_x", -1f); }
    public float getOverlayY() { return sp.getFloat("overlay_y", -1f); }
    public void setOverlayPos(float x, float y) { sp.edit().putFloat("overlay_x", x).putFloat("overlay_y", y).apply(); }

    /** 是否已配置 API */
    public boolean isConfigured() {
        return !getApiBase().trim().isEmpty() && !getApiKey().trim().isEmpty() && !getModel().trim().isEmpty();
    }
}
