package com.digitallife.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import com.digitallife.util.AutoStartPolicy;

/**
 * 开机自启接收器（v1.133.0）。
 *
 * <p>设备重启后，如果用户开着「开机自动启动」、关机前桌宠在跑、且悬浮窗权限仍在，
 * 就把 {@link PetService} 前台服务拉起来，让桌宠自己回来。判定下沉到
 * {@link AutoStartPolicy}，这里只负责取设置 + 起服务。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";
    private static final String ACTION_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !ACTION_QUICKBOOT.equals(action)) return;

        com.digitallife.util.Settings settings = new com.digitallife.util.Settings(context);
        boolean overlayGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(context);
        if (!AutoStartPolicy.shouldStart(
                settings.isAutoStartEnabled(), settings.isPetEnabled(), overlayGranted)) {
            return;
        }

        try {
            Intent i = new Intent(context, PetService.class);
            i.setAction(PetService.ACTION_START);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        } catch (Exception e) {
            Log.w(TAG, "开机拉起桌宠失败", e);
        }
    }
}
