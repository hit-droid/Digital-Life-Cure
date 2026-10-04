package com.digitallife;

import android.app.Application;

import com.digitallife.storage.PendingRestore;

import java.io.File;

/**
 * v1.146.0：进程启动入口，只负责一件事——在**任何存储（SharedPreferences / SQLite）
 * 打开之前**，应用设置页暂存的「从备份恢复」。
 *
 * <p>{@link #onCreate()} 早于所有 Activity / Service / Receiver，此时覆盖
 * {@code shared_prefs/} 与 {@code databases/} 不会被内存态或文件锁覆盖。</p>
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
            PendingRestore.applyIfPending(
                    getCacheDir(),
                    filesDir,
                    new File(dataDir, "shared_prefs"),
                    new File(dataDir, "databases"));
        } catch (Throwable ignored) {
            // 恢复失败绝不能拖垮启动：待恢复文件已由 PendingRestore 清掉，不会反复失败
        }
    }
}
