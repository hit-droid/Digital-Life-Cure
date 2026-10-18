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
    private final SecureStore secure = new SecureStore();

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

    /**
     * 上次「从备份恢复」的结果（由 {@code com.digitallife.App} 在启动恢复后写入：
     * 成功为 {@code ok|<文件数>}，失败为 {@code fail|<原因>}；空串表示无待汇报结果）。
     * 恢复发生在启动早期、界面还没起来，只能先存这里，等设置页读一次后清除。
     */
    public String getRestoreResult() { return sp.getString("restore_result", ""); }
    public void setRestoreResult(String v) {
        sp.edit().putString("restore_result", v == null ? "" : v).apply();
    }
    public void clearRestoreResult() { sp.edit().remove("restore_result").apply(); }

    /**
     * 读取 API Key。磁盘上是密文（{@code enc:v1:...}）；读到旧明文时透明迁移为密文，
     * 迁移只在设备支持加密时执行，且幂等（迁移后前缀存在，不再重复写）。
     */
    public String getApiKey() {
        String stored = sp.getString("api_key", "");
        if (stored == null || stored.isEmpty()) return "";
        if (SecureCrypto.isEncrypted(stored)) return secure.decrypt(stored);
        if (secure.isSupported()) {
            String enc = secure.encrypt(stored);
            if (SecureCrypto.isEncrypted(enc)) {
                sp.edit().putString("api_key", enc).apply();
            }
        }
        return stored;
    }

    public void setApiKey(String v) {
        if (v == null) v = "";
        sp.edit().putString("api_key", v.isEmpty() ? "" : secure.encrypt(v)).apply();
    }

    /** 当前设备是否支持加密存储（API<23 会降级明文，设置页据此提示） */
    public boolean isSecureStorageSupported() {
        return secure.isSupported();
    }

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

    /**
     * 开机自动播放的连招（v1.178.0）：模型目录名 + 连招名；连招名为空 = 不自动播放。
     * 只有该连招所属模型恰好是当前默认模型时才会在桌宠就绪时播放（否则静默跳过）。
     */
    public String getStartupComboModel() { return sp.getString("startup_combo_model", ""); }
    public String getStartupComboName() { return sp.getString("startup_combo_name", ""); }

    /** 设置 / 清空开机自动播放的连招（传空串即清空） */
    public void setStartupCombo(String modelDir, String comboName) {
        sp.edit()
                .putString("startup_combo_model", modelDir == null ? "" : modelDir)
                .putString("startup_combo_name", comboName == null ? "" : comboName)
                .apply();
    }

    /**
     * 主题·强调色键（v1.179.0）：取值见 {@code ui.Accent}（purple/blue/cyan/green/orange/pink），
     * 默认 purple。由主题页写入、{@code ui.ThemeManager} 读取并套用。
     */
    public String getAccent() { return sp.getString("theme_accent", "purple"); }
    public void setAccent(String v) {
        sp.edit().putString("theme_accent", v == null || v.isEmpty() ? "purple" : v).apply();
    }

    /**
     * 主题·外观键（v1.179.0）：{@code dark}（深紫，默认）/ {@code oled}（纯黑）。
     * 注意两者同为**深色**——本项目坚持单套深色色板，不提供浅色模式（见 color guard）。
     */
    public String getAppearance() { return sp.getString("theme_appearance", "dark"); }
    public void setAppearance(String v) {
        sp.edit().putString("theme_appearance", v == null || v.isEmpty() ? "dark" : v).apply();
    }

    /** 是否已配置 API */
    public boolean isConfigured() {
        return !getApiBase().trim().isEmpty() && !getApiKey().trim().isEmpty() && !getModel().trim().isEmpty();
    }
}
