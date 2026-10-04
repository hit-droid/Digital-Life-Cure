package com.digitallife;

import android.app.Application;

import com.digitallife.storage.PendingRestore;
import com.digitallife.util.Settings;

import java.io.File;

/**
 * v1.146.0：进程启动入口，只负责一件事——在**任何存储（SharedPreferences / SQLite）
 * 打开之前**，应用设置页暂存的「从备份恢复」。
 *
 * <p>{@link #onCreate()} 早于所有 Activity / Service / Receiver，此时覆盖
 * {@code shared_prefs/} 与 {@code databases/} 不会被内存态或文件锁覆盖。</p>
 *
 * <p>恢复结果写入 {@link Settings#setRestoreResult(String)}（{@code ok|文件数} /
 * {@code fail|原因}），等设置页第一次被打开时汇报并清除。</p>
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        applyPendingRestore();
    }

    private void applyPendingRestore() {
        try {
            File filesDir = getFilesDir();
            File dataDir = filesDir != null ? filesDir.getParentFile() : null;
            if (filesDir == null || dataDir == null) return;
            File cacheDir = getCacheDir();
            if (!PendingRestore.hasPending(cacheDir)) return;

            String result;
            try {
                int restored = PendingRestore.applyIfPending(
                        cacheDir,
                        filesDir,
                        new File(dataDir, "shared_prefs"),
                        new File(dataDir, "databases"));
                result = "ok|" + restored;
            } catch (Throwable t) {
                result = "fail|" + safeMessage(t);
            }
            // 结果要写进（可能刚被恢复覆盖的）pet_settings，供设置页下次打开时汇报
            new Settings(this).setRestoreResult(result);
        } catch (Throwable ignored) {
            // 恢复失败绝不能拖垮启动：待恢复文件已由 PendingRestore 清掉，不会反复失败
        }
    }

    private static String safeMessage(Throwable t) {
        String msg = t != null ? t.getMessage() : null;
        if (msg == null || msg.isEmpty()) {
            msg = t != null ? t.getClass().getSimpleName() : "未知错误";
        }
        return msg;
    }
}
