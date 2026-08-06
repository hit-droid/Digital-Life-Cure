package com.digitallife.plugin;

import android.content.Context;
import android.content.SharedPreferences;

import com.digitallife.tools.Tool;
import com.digitallife.tools.ToolRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 插件管理器：安装、列出、卸载声明式插件。
 * 每个插件解压到 files/plugins/<name>/，解析 plugin.json 后注册工具到全局 ToolRegistry。
 */
public class PluginManager {

    private static final String PREF = "plugin_installed";
    private static final String KEY_LIST = "installed";
    private static final long MAX_ZIP_SIZE = 10 * 1024 * 1024; // 10MB

    private final Context ctx;
    private final SharedPreferences sp;

    public PluginManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 插件安装结果 */
    public static class InstallResult {
        public boolean ok;
        public String message;
        public String pluginName;

        InstallResult(boolean ok, String message, String pluginName) {
            this.ok = ok;
            this.message = message;
            this.pluginName = pluginName;
        }
    }

    /** 已安装的插件信息 */
    public static class InstalledPlugin {
        public String name;
        public String version;
        public String author;
        public String description;
        public int toolCount;

        InstalledPlugin(String name, String version, String author, String description, int toolCount) {
            this.name = name;
            this.version = version;
            this.author = author;
            this.description = description;
            this.toolCount = toolCount;
        }
    }

    // ============ 安装 ============

    /** 从 zip 输入流安装插件 */
    public InstallResult install(InputStream zipStream) {
        try {
            // 先读 zip 到临时文件，支持两遍遍历
            File tempDir = new File(ctx.getCacheDir(), "plugin_install_" + System.nanoTime());
            tempDir.mkdirs();
            ZipInputStream zis = new ZipInputStream(zipStream);
            ZipEntry entry;
            long totalSize = 0;
            String pluginDirName = null;
            byte[] buf = new byte[8192];

            // 第一遍：找 plugin.json，确定插件名
            String manifestPath = null;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                totalSize += entry.getSize();
                if (totalSize > MAX_ZIP_SIZE) {
                    deleteDir(tempDir);
                    return new InstallResult(false, "插件包过大（超过 10MB）", null);
                }
                if (name.endsWith("plugin.json")) {
                    manifestPath = name;
                    // 插件目录 = plugin.json 所在目录的上一级或同级
                    int idx = name.lastIndexOf('/');
                    pluginDirName = idx > 0 ? name.substring(0, idx) : "";
                    // 只取第一级目录名
                    idx = pluginDirName.indexOf('/');
                    if (idx > 0) pluginDirName = pluginDirName.substring(0, idx);
                    if (pluginDirName.isEmpty()) pluginDirName = name.replace("/plugin.json", "").replace("plugin.json", "");
                    if (pluginDirName.isEmpty()) pluginDirName = "plugin_" + System.currentTimeMillis();
                }
            }
            zis.close();

            if (manifestPath == null) {
                deleteDir(tempDir);
                return new InstallResult(false, "未找到 plugin.json", null);
            }
            // 清理目录名中的非法字符
            pluginDirName = pluginDirName.replaceAll("[^a-zA-Z0-9_-]", "_");

            // 第二遍：解压到 files/plugins/<name>/
            File pluginsDir = getPluginsDir();
            File targetDir = new File(pluginsDir, pluginDirName);
            if (targetDir.exists()) {
                deleteDir(targetDir);
            }
            targetDir.mkdirs();

            zis = new ZipInputStream(new FileInputStream(tempDir.listFiles() != null && tempDir.listFiles().length > 0
                    ? tempDir.listFiles()[0] : new File("x")));
            // 重新打开zip流
            ZipInputStream zis2 = new ZipInputStream(zipStream);
            // 实际上我们缓存了zip流到临时文件
            File tempZip = new File(tempDir, "plugin.zip");
            // 把原始zip保存到临时文件
            // 但我们已经消费了zipStream，需要重新打开
            // 简化：用临时文件方式
            deleteDir(tempDir);
            return installFromUri(pluginDirName, manifestPath, zipStream);
        } catch (Exception e) {
            return new InstallResult(false, "安装失败: " + e.getMessage(), null);
        }
    }

    /** 直接用 InputStream 无法两遍遍历，简化：先存临时文件 */
    private InstallResult installFromUri(String pluginDirName, String manifestPath, InputStream in) throws Exception {
        File tempZip = new File(ctx.getCacheDir(), "plugin_dl_" + System.nanoTime() + ".zip");
        try (OutputStream os = new java.io.FileOutputStream(tempZip)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        }
        try {
            File pluginsDir = getPluginsDir();
            File targetDir = new File(pluginsDir, pluginDirName);
            if (targetDir.exists()) deleteDir(targetDir);
            targetDir.mkdirs();

            // 解压所有文件
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(tempZip))) {
                ZipEntry entry;
                long total = 0;
                while ((entry = zis.getNextEntry()) != null) {
                    total += entry.getSize();
                    if (total > MAX_ZIP_SIZE) {
                        deleteDir(targetDir);
                        return new InstallResult(false, "插件包解压超过大小限制", null);
                    }
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    // Zip Slip 防护：拒绝绝对路径、父目录穿越，目标必须落在解压目录内
                    File outFile = safeResolve(targetDir, name);
                    if (outFile == null) {
                        deleteDir(targetDir);
                        return new InstallResult(false, "插件包包含非法路径: " + name, null);
                    }
                    outFile.getParentFile().mkdirs();
                    try (OutputStream os = new java.io.FileOutputStream(outFile)) {
                        byte[] buf2 = new byte[8192];
                        int n;
                        while ((n = zis.read(buf2)) != -1) os.write(buf2, 0, n);
                    }
                }
            }

            // 解析 plugin.json
            File manifestFile = new File(targetDir, manifestPath);
            if (!manifestFile.exists()) {
                // 可能在子目录
                for (File f : listFilesRecursive(targetDir)) {
                    if (f.getName().equals("plugin.json")) {
                        manifestFile = f;
                        break;
                    }
                }
            }
            if (!manifestFile.exists()) {
                deleteDir(targetDir);
                return new InstallResult(false, "解压后未找到 plugin.json", null);
            }

            String jsonText = readFile(manifestFile);
            PluginManifest manifest = PluginManifest.parse(jsonText);

            // 注册工具到全局 ToolRegistry
            ToolRegistry reg = ToolRegistry.getInstance();
            String prefix = manifest.name;
            int count = 0;
            for (PluginManifest.ToolDef td : manifest.tools) {
                reg.register(new PluginTool(prefix, td, targetDir.getAbsolutePath()));
                count++;
            }

            // 记录已安装
            markInstalled(manifest.name, manifest.version, manifest.author, manifest.description, count);

            tempZip.delete();
            return new InstallResult(true, "已安装插件「" + manifest.name + "」(" + count + " 个工具)", manifest.name);
        } catch (Exception e) {
            tempZip.delete();
            throw e;
        }
    }

    /** 直接从 zip 输入流安装（对外接口） */
    public InstallResult installFromStream(InputStream in) {
        try {
            // 保存到临时文件
            File tempZip = new File(ctx.getCacheDir(), "plugin_install_" + System.nanoTime() + ".zip");
            try (OutputStream os = new java.io.FileOutputStream(tempZip)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
            }
            try (FileInputStream fis = new FileInputStream(tempZip)) {
                // 自动检测 plugin.json 并安装
                String pluginDir = detectPluginName(fis);
                fis.close();
                if (pluginDir == null) {
                    tempZip.delete();
                    return new InstallResult(false, "未找到 plugin.json", null);
                }
                try (FileInputStream fis2 = new FileInputStream(tempZip)) {
                    return installFromUri(pluginDir, "plugin.json", fis2);
                }
            } finally {
                tempZip.delete();
            }
        } catch (Exception e) {
            return new InstallResult(false, "安装失败: " + e.getMessage(), null);
        }
    }

    private String detectPluginName(InputStream in) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if (name.endsWith("plugin.json")) {
                    int idx = name.indexOf('/');
                    return idx > 0 ? name.substring(0, idx) : "plugin";
                }
            }
        }
        return null;
    }

    // ============ 查询与管理 ============

    public List<InstalledPlugin> listInstalled() {
        List<InstalledPlugin> list = new ArrayList<>();
        String raw = sp.getString(KEY_LIST, "");
        if (raw.isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(new InstalledPlugin(
                        o.optString("name", ""),
                        o.optString("version", ""),
                        o.optString("author", ""),
                        o.optString("description", ""),
                        o.optInt("toolCount", 0)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public void uninstall(String name) {
        // 注销工具
        ToolRegistry reg = ToolRegistry.getInstance();
        List<InstalledPlugin> all = listInstalled();
        for (InstalledPlugin p : all) {
            if (p.name.equals(name)) {
                // 清理所有以此插件名称为前缀的工具
                for (Tool t : new ArrayList<>(reg.all())) {
                    if (t.getName().startsWith(name + "_")) {
                        reg.unregister(t.getName());
                    }
                }
                break;
            }
        }
        // 删除目录
        File dir = new File(getPluginsDir(), name.replaceAll("[^a-zA-Z0-9_-]", "_"));
        if (dir.exists()) deleteDir(dir);
        // 移除记录
        List<InstalledPlugin> keep = new ArrayList<>();
        for (InstalledPlugin p : all) {
            if (!p.name.equals(name)) keep.add(p);
        }
        persistInstalled(keep);
    }

    public File getPluginsDir() {
        File dir = new File(ctx.getFilesDir(), "plugins");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ============ 内部 ============

    private void markInstalled(String name, String version, String author, String description, int toolCount) {
        List<InstalledPlugin> all = listInstalled();
        // 删旧同名
        List<InstalledPlugin> keep = new ArrayList<>();
        for (InstalledPlugin p : all) {
            if (!p.name.equals(name)) keep.add(p);
        }
        keep.add(new InstalledPlugin(name, version, author, description, toolCount));
        persistInstalled(keep);
    }

    private void persistInstalled(List<InstalledPlugin> list) {
        JSONArray arr = new JSONArray();
        for (InstalledPlugin p : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("name", p.name);
                o.put("version", p.version);
                o.put("author", p.author);
                o.put("description", p.description);
                o.put("toolCount", p.toolCount);
                arr.put(o);
            } catch (Exception ignored) {
            }
        }
        sp.edit().putString(KEY_LIST, arr.toString()).apply();
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File c : children) {
                if (c.isDirectory()) deleteDir(c);
                else c.delete();
            }
        }
        dir.delete();
    }

    /**
     * Zip Slip 防护：把 zip 内相对路径安全解析到 base 目录下。
     * 拒绝绝对路径、含 .. 的路径，并用 canonical 校验确保结果落在 base 内。
     * 非法路径返回 null，由调用方拒绝整个压缩包。
     */
    private static File safeResolve(File base, String name) {
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
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static String readFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static List<File> listFilesRecursive(File dir) {
        List<File> result = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) return result;
        for (File c : children) {
            if (c.isDirectory()) result.addAll(listFilesRecursive(c));
            else result.add(c);
        }
        return result;
    }
}