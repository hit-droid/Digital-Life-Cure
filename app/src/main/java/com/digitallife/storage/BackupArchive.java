package com.digitallife.storage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 备份包解压/恢复（纯逻辑，传 {@link File} 根目录，便于 JVM 单测）。
 *
 * <p>与 {@link DataPort#exportZip} 互为逆操作：导出时 zip 顶层前缀固定为
 * {@code files/}、{@code shared_prefs/}、{@code databases/}；恢复时按前缀映射回三个真实目录。</p>
 *
 * <p>安全：只认这三个已知顶层前缀，其余一律忽略；逐条目做 Zip Slip 校验（拒绝绝对路径、
 * {@code .}/{@code ..} 段与反斜杠穿越）；{@code files/models/}（已导入模型）不覆盖。</p>
 */
public final class BackupArchive {

    private BackupArchive() {
    }

    /** 恢复结果：写入的文件数与被忽略的条目数 */
    public static final class Result {
        public final int restored;
        public final int skipped;

        Result(int restored, int skipped) {
            this.restored = restored;
            this.skipped = skipped;
        }
    }

    /**
     * 把备份 zip 解压写入三个目标目录。目录不存在会自动创建。
     *
     * @return 恢复结果（{@link Result#restored} 为写入文件数）
     */
    public static Result restore(byte[] zip, File filesDir, File prefsDir, File dbDir)
            throws IOException {
        if (zip == null) throw new IOException("zip is null");
        int restored = 0;
        int skipped = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                File target = resolveTarget(e.getName(), filesDir, prefsDir, dbDir);
                if (target == null) {
                    skipped++;
                    continue;
                }
                if (e.isDirectory()) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw new IOException("无法创建目录: " + target);
                    }
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录: " + parent);
                }
                try (OutputStream os = new FileOutputStream(target)) {
                    copy(in, os);
                }
                restored++;
            }
        }
        return new Result(restored, skipped);
    }

    /**
     * 把 zip 条目名映射为磁盘目标文件；非法或未知前缀返回 {@code null}（调用方跳过）。
     * 包级可见以便单测直接验证 Zip Slip 防护。
     */
    static File resolveTarget(String entryName, File filesDir, File prefsDir, File dbDir) {
        if (entryName == null) return null;
        String name = entryName.replace('\\', '/');
        if (name.isEmpty() || name.startsWith("/")) return null;

        File root;
        String relative;
        if (name.startsWith("files/")) {
            root = filesDir;
            relative = name.substring("files/".length());
        } else if (name.startsWith("shared_prefs/")) {
            root = prefsDir;
            relative = name.substring("shared_prefs/".length());
        } else if (name.startsWith("databases/")) {
            root = dbDir;
            relative = name.substring("databases/".length());
        } else {
            return null;
        }
        if (root == null || relative.isEmpty()) return null;

        // 已导入模型不覆盖（导出时也排除了，双保险）
        if (root == filesDir
                && (relative.equals(DataPort.KEEP_MODELS)
                || relative.startsWith(DataPort.KEEP_MODELS + "/"))) {
            return null;
        }
        // 逐段校验，杜绝 .. / . / 空段造成的穿越
        for (String seg : relative.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) return null;
        }
        return new File(root, relative);
    }

    private static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            os.write(buf, 0, n);
        }
    }
}
