package com.digitallife.tools;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「最近文件」——把设备上**可访问、用户关心**的文件按修改时间倒序取前 N 条。
 *
 * <p>背景（v1.180.0）：工具市场里的 {@code get_files_recent}「最近文件」此前是占位实现
 * （只回一句「请在设置里查看」）。本类把它做成真的：扫描应用专属目录（备份 / 导出）与
 * 公共下载 / 文档目录，按 mtime 倒序给出文件名 + 大小 + 时间 + 路径。</p>
 *
 * <p><b>边界（有意为之）</b>：不申请、不依赖任何存储权限——公共目录在受限时会
 * {@code listFiles()} 返回 null / 抛 SecurityException，这里一律静默跳过（拿不到就退化为
 * 「未找到」），绝不崩；不递归模型目录（{@code models}）与缓存（{@code cache}），避免把成百上千
 * 个资源文件刷屏。扫描有总量上限 {@link #SCAN_CAP}，慢盘上也不会卡死。</p>
 *
 * <p>纯读、无副作用；{@link #collect} / {@link #render} / {@link #humanSize} 为纯逻辑，
 * 便于单测。</p>
 */
public final class RecentFiles {

    /** 默认条数 */
    static final int DEFAULT_MAX = 10;
    /** 条数上限，防止一次返回过长 */
    static final int MAX_MAX = 30;
    /** 单次扫描的文件总数上限 */
    static final int SCAN_CAP = 500;
    /** 子目录递归深度（0 = 只看目录本身的直接文件） */
    private static final int MAX_DEPTH = 2;
    /** 不进入的目录名（模型资源 / 缓存，噪音大且非「用户文件」） */
    private static final String[] SKIP_DIRS = {"cache", "models"};

    private RecentFiles() {
    }

    /** 取最近文件列表（纯文本），默认 {@link #DEFAULT_MAX} 条 */
    public static String recent(Context ctx) {
        return recent(ctx, DEFAULT_MAX);
    }

    /** 取最近文件列表（纯文本）；{@code max<=0} 用默认 10，超过上限按 30 夹取 */
    public static String recent(Context ctx, int max) {
        if (ctx == null) return "无法读取文件：上下文为空";
        int n = clampMax(max);
        List<File> files = collect(candidateDirs(ctx), SCAN_CAP);
        if (files.isEmpty()) return "未找到可访问的最近文件（可先备份或导出到下载目录，再查看）";
        return render(files, n);
    }

    /** 条数夹取：非正 → 默认；超上限 → 上限 */
    static int clampMax(int max) {
        if (max <= 0) return DEFAULT_MAX;
        return Math.min(max, MAX_MAX);
    }

    /** 候选目录：应用专属外置 / 内部文件目录 + 公共「下载」「文档」（受限时静默跳过） */
    @SuppressWarnings("deprecation")
    static List<File> candidateDirs(Context ctx) {
        List<File> dirs = new ArrayList<>();
        try {
            File ext = ctx.getExternalFilesDir(null);
            if (ext != null) dirs.add(ext);
        } catch (Exception ignored) {
        }
        try {
            File internal = ctx.getFilesDir();
            if (internal != null) dirs.add(internal);
        } catch (Exception ignored) {
        }
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null) dirs.add(downloads);
            File documents = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS);
            if (documents != null) dirs.add(documents);
        } catch (Exception ignored) {
        }
        return dirs;
    }

    /** 收集候选目录下的文件（限深度、跳隐藏、跳过噪音目录），按 mtime 倒序、受 cap 限制 */
    static List<File> collect(List<File> dirs, int cap) {
        List<File> out = new ArrayList<>();
        if (dirs == null || cap <= 0) return out;
        // cap 是「返回条数」，不是「扫描条数」：先扫够候选再排序截断。
        // 此前把 cap 当作 walk 的提前退出条件，cap 较小时只会看到 listFiles() 的前几个
        // （文件系统顺序任意），排序后拿到的并不是真正的最近文件。
        int scanBudget = Math.max(cap, SCAN_CAP);
        for (File d : dirs) {
            if (d == null || out.size() >= scanBudget) continue;
            try {
                walk(d, MAX_DEPTH, out, scanBudget);
            } catch (Exception ignored) {
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (out.size() > cap) {
            return new ArrayList<>(out.subList(0, cap));
        }
        return out;
    }

    private static void walk(File dir, int depth, List<File> out, int cap) {
        if (dir == null || depth < 0 || out.size() >= cap) return;
        File[] children;
        try {
            children = dir.listFiles();
        } catch (Exception e) {
            return;
        }
        if (children == null) return;
        for (File f : children) {
            if (out.size() >= cap) return;
            if (f == null || isHidden(f)) continue;
            if (f.isDirectory()) {
                if (isSkipDir(f.getName())) continue;
                walk(f, depth - 1, out, cap);
            } else if (f.length() > 0) {
                out.add(f);
            }
        }
    }

    private static boolean isHidden(File f) {
        String n = f.getName();
        return n.startsWith(".") || n.endsWith(".nomedia");
    }

    private static boolean isSkipDir(String name) {
        for (String s : SKIP_DIRS) {
            if (s.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** 渲染为文本：标题 + 每行「文件名 · 大小 · MM-dd HH:mm」+ 缩进路径 */
    static String render(List<File> files, int max) {
        if (files == null || files.isEmpty()) return "未找到可访问的最近文件";
        int n = Math.min(Math.max(max, 1), files.size());
        StringBuilder sb = new StringBuilder("🕘 最近文件（按修改时间倒序，最多 ")
                .append(n).append(" 条）：");
        for (int i = 0; i < n; i++) {
            File f = files.get(i);
            sb.append("\n- ").append(f.getName())
                    .append(" · ").append(humanSize(f.length()))
                    .append(" · ").append(stamp(f.lastModified()))
                    .append("\n  ").append(f.getAbsolutePath());
        }
        return sb.toString();
    }

    /** 字节数转人类可读（B/KB/MB/GB，保留一位小数） */
    static String humanSize(long bytes) {
        if (bytes < 0) return "0B";
        if (bytes < 1024) return bytes + "B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return round1(kb) + "KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return round1(mb) + "MB";
        return round1(mb / 1024.0) + "GB";
    }

    private static String round1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    static String stamp(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ms));
    }
}
