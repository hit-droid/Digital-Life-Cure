package com.digitallife.util;

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

    /** 桌宠松手是否吸附到屏幕左/右边缘（v1.132.0，默认开） */
    public boolean isEdgeDockEnabled() { return sp.getBoolean("edge_dock", true); }
    public void setEdgeDockEnabled(boolean v) { sp.edit().putBoolean("edge_dock", v).apply(); }

    /** 开机是否自动拉起桌宠（v1.133.0，默认开；还需 pet_enabled 与悬浮窗权限同时满足） */
    public boolean isAutoStartEnabled() { return sp.getBoolean("auto_start", true); }
    public void setAutoStartEnabled(boolean v) { sp.edit().putBoolean("auto_start", v).apply(); }

    /** 用户意图：桌宠是否应在运行（开机自启只看它，不看进程是否还活着；v1.133.0） */
    public boolean isPetEnabled() { return sp.getBoolean("pet_enabled", false); }
    public void setPetEnabled(boolean v) { sp.edit().putBoolean("pet_enabled", v).apply(); }

    /** 桌宠位置是否锁定（v1.135.0，默认不锁；锁拖拽不锁点击，长按菜单里可解锁） */
    public boolean isPetLocked() { return sp.getBoolean("pet_locked", false); }
    public void setPetLocked(boolean v) { sp.edit().putBoolean("pet_locked", v).apply(); }

    public float getOverlayX() { return sp.getFloat("overlay_x", -1f); }
    public float getOverlayY() { return sp.getFloat("overlay_y", -1f); }
    public void setOverlayPos(float x, float y) { sp.edit().putFloat("overlay_x", x).putFloat("overlay_y", y).apply(); }

    /** 默认模型目录名（启动时自动加载；空 = 加载第一个内置模型） */
    public String getDefaultModelDir() { return sp.getString("default_model_dir", ""); }
    public void setDefaultModelDir(String v) { sp.edit().putString("default_model_dir", v == null ? "" : v).apply(); }

    /** 是否已配置 API */
    public boolean isConfigured() {
        return !getApiBase().trim().isEmpty() && !getApiKey().trim().isEmpty() && !getModel().trim().isEmpty();
    }
}
