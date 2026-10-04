package com.digitallife.storage;

import org.json.JSONException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 备份包解压/恢复（纯逻辑，传 {@link File} 根目录，便于 JVM 单测）。
 *
 * <p>与 {@link DataPort#exportZip} 互为逆操作：导出时 zip 顶层前缀固定为
 * {@code files/}、{@code shared_prefs/}、{@code databases/}；恢复时按前缀映射回三个真实目录。</p>
 *
 * <p>安全：<b>先校验后写入</b>——若包内带 {@link BackupManifest 清单}，先流式比对全部条目的
 * size/CRC32，不符直接抛异常、一个字节都不写（避免半截或损坏的包把数据覆盖成半截）；
 * 无清单的旧包跳过强校验以向后兼容。写入阶段只认三个已知顶层前缀，其余一律忽略；
 * 逐条目做 Zip Slip 校验（拒绝绝对路径、{@code .}/{@code ..} 段与反斜杠穿越）；
 * {@code files/models/}（已导入模型）不覆盖。</p>
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
        verifyIntegrity(zip);
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
     * 恢复前完整性校验：流式读一遍 zip，算出每条数据的 size/CRC32，与包内清单比对。
     *
     * <p>无清单（v1.146.0 之前的旧包）直接放行；有清单则要求条目集合完全一致
     * （不缺失、不多出、size/CRC 全对），任一不符抛 {@link IOException}。</p>
     */
    private static void verifyIntegrity(byte[] zip) throws IOException {
        byte[] manifestBytes = null;
        Map<String, long[]> actual = new LinkedHashMap<>();
        byte[] buf = new byte[8192];
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                boolean isManifest = BackupManifest.ENTRY_NAME.equals(e.getName());
                ByteArrayOutputStream bos = isManifest ? new ByteArrayOutputStream() : null;
                CRC32 crc = new CRC32();
                long size = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    crc.update(buf, 0, n);
                    size += n;
                    if (bos != null) bos.write(buf, 0, n);
                }
                if (isManifest) {
                    manifestBytes = bos.toByteArray();
                } else {
                    actual.put(e.getName(), new long[]{size, crc.getValue()});
                }
            }
        }
        if (manifestBytes == null) return; // 旧包：无清单，跳过强校验
        List<BackupManifest.Item> expected;
        try {
            expected = BackupManifest.parse(new String(manifestBytes, StandardCharsets.UTF_8));
        } catch (JSONException ex) {
            throw new IOException("备份清单损坏", ex);
        }
        BackupManifest.verify(expected, actual);
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
