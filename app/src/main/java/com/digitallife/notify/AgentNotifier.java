package com.digitallife.notify;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.digitallife.R;
import com.digitallife.ui.MainActivity;

/**
 * 智能体系统通知（v1.24.0）。
 * 借鉴 Operit AI 的多渠道通知设计。
 * 使用纯系统 API（无 AndroidX 依赖），保持项目兼容性。
 */
public class AgentNotifier {

    private static final String CHANNEL_ID = "agent_notify";

    public static void notify(Context ctx, String title, String content) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "数字生命",
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("智能体主动消息、提醒与工具结果");
                nm.createNotificationChannel(channel);
            }
            Intent intent = new Intent(ctx, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23
                                    ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder = new Notification.Builder(ctx, CHANNEL_ID);
            } else {
                builder = new Notification.Builder(ctx);
            }
            builder.setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(title != null ? title : "数字生命")
                    .setContentText(content != null ? content : "")
                    .setStyle(new Notification.BigTextStyle()
                            .bigText(content != null ? content : ""))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            // Android 8+ 必须传 channel id；旧版 API 26 前 deprecated
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setPriority(Notification.PRIORITY_DEFAULT);
            }
            nm.notify((int) System.currentTimeMillis(), builder.build());
        } catch (Exception e) {
            // 静默失败
        }
    }
}
