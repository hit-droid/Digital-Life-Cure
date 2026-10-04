package com.digitallife.care;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 护理大脑的纯文件 / 文本工具。
 *
 * <p>v1.143.0 从 {@link CareTools} 上帝类下沉：这些方法原本散落在 1400 余行的工具集里，
 * 全是无状态静态逻辑（不依赖 Android API），集中到这里后可单独用 JVM 单测覆盖，
 * 尤其是安全相关的 {@link #safeResolve}（Zip Slip 防护）与路径/尺寸格式化。
 */
public final class CareFileOps {

    private CareFileOps() {
    }

    /**
     * Zip Slip 防护：把 zip 内的相对路径安全解析到 base 目录下。
     * 绝对路径、含 {@code ..} 的路径、以及 canonical 解析后逃逸出 base 的路径一律返回 null。
     */
    public static File safeResolve(File base, String name) {
        if (name == null || name.isEmpty()) return null;
        if (name.startsWith("/") || name.contains("..")) return null;
        File f = new File(base, name);
        try {
            String basePath = base.getCanonicalPath();
            String targetPath = f.getCanonicalPath();
            if (!targetPath.startsWith(basePath + File.separator) && !targetPath.equals(basePath)) {
                return null;
            }
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    /** 取目录下第一个以 suffix 结尾的文件，没有返回 null */
    public static File firstFile(File dir, String suffix) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(suffix));
        if (files != null && files.length > 0) return files[0];
        return null;
    }

    /** 递归统计目录下以 suffix 结尾的文件数 */
    public static int countFiles(File dir, String suffix) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.isDirectory()) {
                count += countFiles(f, suffix);
            } else if (f.getName().endsWith(suffix)) {
                count++;
            }
        }
        return count;
    }

    /** 按 UTF-8 读全文，行尾统一为 {@code \n} 并 trim */
    public static String readFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    /** 递归删除目录（含自身）；不存在则直接返回 */
    public static void deleteRecursive(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteRecursive(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    /** 人类可读的字节数（B / KB / MB） */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** 相对 root 的路径（去掉前导分隔符与 root 前缀） */
    public static String relPath(File root, File f) {
        return f.getAbsolutePath().substring(root.getAbsolutePath().length())
                .replaceAll("^[/\\\\]", "");
    }

    /** 去掉 model3.json / model.json 后缀（忽略大小写），无后缀原样返回 */
    public static String stripModelJsonSuffix(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".model3.json")) {
            return fileName.substring(0, fileName.length() - ".model3.json".length());
        }
        if (lower.endsWith(".model.json")) {
            return fileName.substring(0, fileName.length() - ".model.json".length());
        }
        return fileName;
    }

    /** 把模型名净化为安全的目录名：空名回落 model，非法字符替换为下划线 */
    public static String sanitizeDirName(String name) {
        if (name == null || name.trim().isEmpty()) return "model";
        return name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** 递归复制目录 / 文件 */
    public static void copyRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) throw new Exception("无法创建目录: " + dst);
            File[] files = src.listFiles();
            if (files != null) {
                for (File f : files) copyRecursive(f, new File(dst, f.getName()));
            }
        } else {
            copyFile(src, dst);
        }
    }

    /** 复制单个文件，自动建父目录 */
    public static void copyFile(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
}
