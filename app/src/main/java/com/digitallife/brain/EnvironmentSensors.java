package com.digitallife.brain;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.text.format.DateFormat;

import com.digitallife.service.PetAccessibilityService;

import java.util.Date;

/**
 * L3 环境感知：采集 Android 系统状态，作为桌宠「内心独白」的上下文。
 * 数据完全本地获取，无额外权限（前台应用来自可选的无障碍服务，未授权时返回空）。
 */
public class EnvironmentSensors {

    private final Context ctx;

    public EnvironmentSensors(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** 一次环境快照 */
    public static class SystemContext {
        public final String timeStr;
        public final int batteryPercent;
        public final boolean isCharging;
        public final String foregroundApp;
        public final int userIdleMinutes;

        public SystemContext(String timeStr, int batteryPercent, boolean isCharging,
                             String foregroundApp, int userIdleMinutes) {
            this.timeStr = timeStr;
            this.batteryPercent = batteryPercent;
            this.isCharging = isCharging;
            this.foregroundApp = foregroundApp;
            this.userIdleMinutes = userIdleMinutes;
        }
    }

    public SystemContext capture(int userIdleMinutes) {
        int batteryPct = 50;
        boolean charging = false;
        try {
            Intent b = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    batteryPct = level * 100 / scale;
                }
                int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Exception ignored) {
        }

        String timeStr;
        try {
            timeStr = DateFormat.getTimeFormat(ctx).format(new Date());
        } catch (Exception e) {
            timeStr = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(new Date());
        }

        String fg = PetAccessibilityService.getForegroundPackage();
        if (fg == null || fg.isEmpty()) fg = "桌面/未知";

        return new SystemContext(timeStr, batteryPct, charging, fg, Math.max(0, userIdleMinutes));
    }
}
