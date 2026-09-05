package com.digitallife.brain;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务触发接收器：AlarmManager 到点广播后，持短时唤醒锁执行任务。
 * LLM 调用是异步的（最长约 60s），用 CountDownLatch 等它完成再释放，
 * 唤醒锁兜底 90s 自动释放，防止异常泄漏。
 */
public class TaskReceiver extends BroadcastReceiver {

    private static final long WAKE_LOCK_TIMEOUT_MS = 90000;
    private static final long WAIT_TIMEOUT_MS = 75000;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String taskId = intent != null ? intent.getStringExtra("task_id") : null;
        if (taskId == null || taskId.isEmpty()) return;
        final PendingResult pending = goAsync();
        final Context ctx = context.getApplicationContext();
        PowerManager.WakeLock wl = null;
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "digitallife:task");
                wl.acquire(WAKE_LOCK_TIMEOUT_MS);
            }
        } catch (Exception ignored) {
        }
        final PowerManager.WakeLock wakeLock = wl;
        new Thread(() -> {
            try {
                CountDownLatch done = new CountDownLatch(1);
                TaskScheduler.onTrigger(ctx, taskId, done::countDown);
                done.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            } finally {
                try {
                    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
                } catch (Exception ignored) {
                }
                pending.finish();
            }
        }, "task-receiver").start();
    }
}
