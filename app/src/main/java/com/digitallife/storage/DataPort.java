package com.digitallife.storage;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 用户数据的导出与清除（纯逻辑，传 {@link File} 根目录，便于 JVM 单测）。
 *
 * <p>覆盖三类落盘位置：
 * <ul>
 *   <li>{@code shared_prefs/} —— 设置、角色、记忆索引、开关等全部配置</li>
 *   <li>{@code databases/} —— 记忆库等 SQLite 数据</li>
 *   <li>{@code files/} —— 技能、插件等，但**排除 {@code models/}**（体积大，
 *       导出会把包撑爆，清除后还得让用户重新导入模型）</li>
 * </ul>
 *
 * <p>注意：导出会包含加密后的 API Key（{@code enc:v1:...}），它是密文且密钥
 * 在设备 Keystore 中，离开本机无法解开。
 */
public final class DataPort {

    /** files/ 下不导出、不清除的子目录名 */
    public static final String KEEP_MODELS = "models";

    private static final Set<String> KEEP = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(KEEP_MODELS)));

    private DataPort() {
    }

    /** zip 内路径 + 对应磁盘文件 */
    public static final class Entry {
        public final String path;
        public final File file;

        Entry(String path, File file) {
            this.path = path;
            this.file = file;
        }
    }

    /** 该顶层目录名是否被排除（当前仅 models） */
    public static boolean isExcluded(String name) {
        return name != null && KEEP.contains(name);
    }

    /** 收集要导出/清除的顶层条目；目录不存在时自动跳过 */
    public static List<Entry> exportEntries(File filesDir, File prefsDir, File dbDir) {
        List<Entry> out = new ArrayList<>();
        collect(out, prefsDir, "shared_prefs", null);
        collect(out, dbDir, "databases", null);
        collect(out, filesDir, "files", KEEP);
        return out;
    }

    private static void collect(List<Entry> out, File dir, String prefix, Set<String> keep) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
        for (File c : children) {
            if (keep != null && keep.contains(c.getName())) continue;
            out.add(new Entry(prefix + "/" + c.getName(), c));
        }
    }

    /**
     * 把条目打包成 zip（递归目录）。调用方负责关闭 {@code out}（SAF 流由系统管理），
     * 这里只 {@code finish()} 写完中央目录。
     *
     * @return 写入的文件数（不含目录条目）
     */
    public static int exportZip(OutputStream out, List<Entry> entries) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out));
        int[] count = {0};
        for (Entry e : entries) {
            addToZip(zip, e.path, e.file, count);
        }
        zip.finish();
        zip.flush();
        return count[0];
    }

    private static void addToZip(ZipOutputStream zip, String path, File f, int[] count)
            throws IOException {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children == null || children.length == 0) {
                zip.putNextEntry(new ZipEntry(path + "/"));
                zip.closeEntry();
                return;
            }
            Arrays.sort(children, (a, b) -> a.getName().compareTo(b.getName()));
            for (File c : children) {
                addToZip(zip, path + "/" + c.getName(), c, count);
            }
            return;
        }
        zip.putNextEntry(new ZipEntry(path));
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                zip.write(buf, 0, n);
            }
        }
        zip.closeEntry();
        count[0]++;
    }

    /** 清除导出范围内的全部用户数据，保留 {@code files/models/}。返回删除的文件/目录数 */
    public static int clear(File filesDir, File prefsDir, File dbDir) {
        int[] count = {0};
        for (Entry e : exportEntries(filesDir, prefsDir, dbDir)) {
            deleteRecursively(e.file, count);
        }
        return count[0];
    }

    private static void deleteRecursively(File f, int[] count) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    deleteRecursively(c, count);
                }
            }
        }
        if (f.delete()) count[0]++;
    }
}
