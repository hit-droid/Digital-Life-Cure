package com.digitallife.notify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.digitallife.R;
import com.digitallife.ui.MainActivity;

/**
 * 智能体系统通知（v1.24.0）。
 * 借鉴 Operit AI 的多渠道通知设计。
 */
public class AgentNotifier {

    private static final String CHANNEL_ID = "agent_notify";

    public static void notify(Context ctx, String title, String content) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            // Android 8+ 需要创建渠道
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "数字生命",
                        NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("智能体主动消息、提醒与工具结果");
                nm.createNotificationChannel(channel);
            }
            // 点击通知 → 打开 MainActivity
            Intent intent = new Intent(ctx, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 23
                                    ? PendingIntent.FLAG_IMMUTABLE : 0));
            NotificationCompat.Builder builder =
                    new NotificationCompat.Builder(ctx, CHANNEL_ID)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle(title != null ? title : "数字生命")
                            .setContentText(content != null ? content : "")
                            .setStyle(new NotificationCompat.BigTextStyle()
                                    .bigText(content != null ? content : ""))
                            .setAutoCancel(true)
                            .setContentIntent(pi);
            nm.notify((int) System.currentTimeMillis(), builder.build());
        } catch (Exception e) {
            // 静默失败
        }
    }
}
