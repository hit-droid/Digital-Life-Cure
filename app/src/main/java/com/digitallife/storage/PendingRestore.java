package com.digitallife.storage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 「下次启动时恢复」的两阶段落地（纯逻辑，传 {@link File} 目录，便于 JVM 单测）。
 *
 * <p>恢复必须写 {@code shared_prefs/} 与 {@code databases/}，而这些文件在进程运行期间
 * 可能被 SharedPreferences / SQLite 占用或随后被内存态覆盖。因此导入时**不立即写盘**，
 * 而是先把解压用的 zip 落到 cache 里的待处理文件；等进程下次启动、任何存储尚未打开时
 * （见 {@code com.digitallife.App}）再真正解压写入。</p>
 */
public final class PendingRestore {

    public static final String PENDING_FILE = "pending_restore.zip";

    private PendingRestore() {
    }

    /** 当前是否有待处理的恢复 */
    public static boolean hasPending(File cacheDir) {
        return cacheDir != null && new File(cacheDir, PENDING_FILE).isFile();
    }

    /** 暂存待恢复的 zip（先写 .tmp 再改名，避免半截文件被误用） */
    public static void stage(File cacheDir, byte[] zip) throws IOException {
        if (cacheDir == null) throw new IOException("cacheDir is null");
        if (zip == null) throw new IOException("zip is null");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IOException("无法创建缓存目录");
        }
        File tmp = new File(cacheDir, PENDING_FILE + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(zip);
            os.flush();
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        File dst = new File(cacheDir, PENDING_FILE);
        if (dst.exists() && !dst.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("无法覆盖旧的待恢复文件");
        }
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("暂存恢复文件失败");
        }
    }

    /**
     * 若存在待恢复文件则解压写入并删除之。无待处理时返回 0。
     *
     * <p>无论成功失败都会删除待处理文件——失败多半是包损坏/磁盘满，留在那里只会让每次
     * 启动都失败一次。</p>
     */
    public static int applyIfPending(File cacheDir, File filesDir, File prefsDir, File dbDir)
            throws IOException {
        File pending = new File(cacheDir, PENDING_FILE);
        if (!cacheDir.isDirectory() || !pending.isFile()) return 0;
        try {
            byte[] zip = readAll(pending);
            return BackupArchive.restore(zip, filesDir, prefsDir, dbDir).restored;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            pending.delete();
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(0, f.length()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
