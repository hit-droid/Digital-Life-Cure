package com.digitallife.util;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 备份恢复（参考 OpenMinis 的 .minisbak 导出）。
 * 打包 databases/（聊天/记忆/思绪 SQLite）与 shared_prefs/（设置/模型配置/人设），
 * 输出到应用专属外置目录 backups/，无需存储权限。
 * 恢复时先自动做一次当前状态的安全备份，再覆盖写回；重启 App 后生效。
 */
public class BackupManager {

    private static final String BACKUP_DIR = "backups";

    // ============ 导出 ============

    /** 导出全部本地数据，返回备份文件绝对路径；失败返回 null */
    public static String exportBackup(Context ctx) {
        Context app = ctx.getApplicationContext();
        File dir = new File(app.getExternalFilesDir(null), BACKUP_DIR);
        if (!dir.exists() && !dir.mkdirs()) return null;
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File out = new File(dir, "digitallife_" + stamp + ".zip");
        List<File> files = collectDataFiles(app);
        if (files.isEmpty()) return null;
        ZipOutputStream zos = null;
        try {
            zos = new ZipOutputStream(new FileOutputStream(out));
            byte[] buf = new byte[8192];
            for (File f : files) {
                // entry 名带相对路径，恢复时按路径还原
                String entryName = entryNameFor(app, f);
                if (entryName == null) continue;
                zos.putNextEntry(new ZipEntry(entryName));
                FileInputStream in = new FileInputStream(f);
                int n;
                while ((n = in.read(buf)) != -1) {
                    zos.write(buf, 0, n);
                }
                in.close();
                zos.closeEntry();
            }
            zos.close();
            zos = null;
            return out.getAbsolutePath();
        } catch (Exception e) {
            if (out.exists()) out.delete();
            return null;
        } finally {
            try {
                if (zos != null) zos.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ============ 恢复 ============

    /**
     * 从备份文件恢复。先把当前数据安全备份为 before_restore_*.zip，
     * 再解压覆盖。返回 null 表示成功；否则返回错误信息。
     */
    public static String restoreBackup(Context ctx, String fileName) {
        Context app = ctx.getApplicationContext();
        File src = resolveBackup(app, fileName);
        if (src == null || !src.exists()) return "找不到备份文件：" + fileName;
        // 安全网：恢复前自动备份当前状态
        exportBackup(app);
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new FileInputStream(src));
            byte[] buf = new byte[8192];
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                File target = resolveEntry(app, e.getName());
                if (target == null) continue; // 非法路径直接跳过
                File parent = target.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(target);
                int n;
                while ((n = zis.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                }
                fos.close();
                zis.closeEntry();
            }
            zis.close();
            zis = null;
            return null;
        } catch (Exception ex) {
            return "恢复失败：" + ex.getMessage();
        } finally {
            try {
                if (zis != null) zis.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ============ 列表 ============

    public static String describe(Context ctx) {
        Context app = ctx.getApplicationContext();
        File dir = new File(app.getExternalFilesDir(null), BACKUP_DIR);
        File[] files = dir.exists() ? dir.listFiles() : null;
        List<String> names = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && f.getName().endsWith(".zip")) {
                    names.add(f.getName() + "（" + f.length() / 1024 + " KB）");
                }
            }
        }
        if (names.isEmpty()) return "还没有备份，可用 backup_data 创建";
        StringBuilder sb = new StringBuilder("备份目录：" + dir.getAbsolutePath());
        for (String n : names) {
            sb.append("\n- ").append(n);
        }
        return sb.toString();
    }

    // ============ 内部 ============

    /** 收集 databases/ 与 shared_prefs/ 下的数据文件（跳过 journal/wal 临时文件） */
    private static List<File> collectDataFiles(Context app) {
        List<File> out = new ArrayList<>();
        File dbDir = new File(app.getApplicationInfo().dataDir, "databases");
        File spDir = new File(app.getApplicationInfo().dataDir, "shared_prefs");
        collectInto(dbDir, out);
        collectInto(spDir, out);
        return out;
    }

    private static void collectInto(File dir, List<File> out) {
        File[] files = dir != null && dir.exists() ? dir.listFiles() : null;
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (name.endsWith("-journal") || name.endsWith("-wal")
                    || name.endsWith("-shm") || name.endsWith(".bak")) {
                continue;
            }
            out.add(f);
        }
    }

    private static String entryNameFor(Context app, File f) {
        String dataDir = app.getApplicationInfo().dataDir;
        String path = f.getAbsolutePath();
        if (path.startsWith(dataDir)) {
            return path.substring(dataDir.length() + 1); // databases/xxx.db
        }
        return null;
    }

    /** 把 zip entry 名解析回数据目录内文件；越界路径（../）一律拒绝 */
    private static File resolveEntry(Context app, String entryName) {
        if (entryName == null || entryName.contains("..")) return null;
        if (!entryName.startsWith("databases/") && !entryName.startsWith("shared_prefs/")) {
            return null;
        }
        return new File(app.getApplicationInfo().dataDir, entryName);
    }

    private static File resolveBackup(Context app, String fileName) {
        if (fileName == null) return null;
        // 只允许裸文件名，防目录穿越
        if (fileName.contains("/") || fileName.contains("..")) {
            fileName = new File(fileName).getName();
        }
        return new File(new File(app.getExternalFilesDir(null), BACKUP_DIR), fileName);
    }
}
