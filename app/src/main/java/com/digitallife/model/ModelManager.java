package com.digitallife.model;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.digitallife.render.Live2DNative;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 模型管理器：负责「导入模型」功能。
 *
 * 内置模型随 APK 打包在 assets/ 中，运行时不可变；
 * 导入模型解压到内部存储 files/models/<name>/ 后，通过
 * Live2DNative.nativeAddModelDir() 注册到 C++ 侧，与内置模型
 * 走同一条 ChangeScene 加载链路。
 */
public class ModelManager {

    private static final String TAG = "ModelManager";

    /** zip 包内模型 json 文件名 */
    private static final String[] MODEL_JSON_NAMES = {".model3.json", ".model.json"};

    /** 单文件解压上限（防御 zip bomb） */
    private static final long MAX_ENTRY_SIZE = 200L * 1024 * 1024;
    /** 总解压上限 */
    private static final long MAX_TOTAL_SIZE = 400L * 1024 * 1024;

    public static class ImportResult {
        public final boolean ok;
        public final String message;
        /** 成功时：模型目录名（files/models/ 下的子目录名） */
        public final String modelDir;

        public ImportResult(boolean ok, String message, String modelDir) {
            this.ok = ok;
            this.message = message;
            this.modelDir = modelDir;
        }
    }

    private ModelManager() {
    }

    /**
     * 从 zip 内容导入一个模型：
     * 1. 扫描 zip 找到 *.model3.json / *.model.json 及其所在目录
     * 2. 把该目录下所有文件解压到 files/models/<name>/
     * 3. 注册到 C++ 侧
     */
    public static ImportResult importFromUri(Context ctx, Uri uri) {
        try {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) {
                return new ImportResult(false, "无法打开所选文件", null);
            }
            in.close();
            return importFromStream(ctx, uri);
        } catch (Exception e) {
            Log.e(TAG, "import failed", e);
            return new ImportResult(false, "导入失败：" + e.getMessage(), null);
        }
    }

    private static ImportResult importFromStream(Context ctx, Uri uri) throws Exception {
        ZipInputStream zis = new ZipInputStream(ctx.getContentResolver().openInputStream(uri));

        // 第一遍：找出模型 json 路径，确定模型根目录前缀
        String jsonPath = null;
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.isDirectory()) continue;
            String name = entry.getName();
            for (String suffix : MODEL_JSON_NAMES) {
                if (name.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                    if (jsonPath == null || name.length() < jsonPath.length()) {
                        jsonPath = name;  // 取最浅的 json（模型主文件）
                    }
                }
            }
        }
        zis.close();

        if (jsonPath == null) {
            return new ImportResult(false,
                    "zip 中未找到模型定义文件（*.model3.json / *.model.json）", null);
        }

        // 确定模型根目录前缀（json 所在目录，去掉前导 /）
        String rootPrefix = jsonPath.substring(0, jsonPath.lastIndexOf('/'));
        if (rootPrefix.endsWith("/")) rootPrefix = rootPrefix.substring(0, rootPrefix.length() - 1);

        // 确定模型目录名：优先 json 所在最外层目录，其次 json 文件名 base
        String modelDirName = extractTopDirName(rootPrefix, jsonPath);

        // json 文件名 base（去掉 .model3.json / .model.json 后缀），C++ 会自己拼接扩展名
        String jsonFileName = jsonPath.substring(jsonPath.lastIndexOf('/') + 1);
        String jsonBase = stripModelJsonSuffix(jsonFileName);

        // 目标目录 files/models/<name>/
        File modelsRoot = Live2DNative.getModelsDir();
        if (modelsRoot == null) {
            return new ImportResult(false, "模型目录未初始化", null);
        }
        File targetDir = new File(modelsRoot, sanitizeDirName(modelDirName));
        // 已存在则删除旧的（覆盖导入）
        deleteRecursively(targetDir);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return new ImportResult(false, "无法创建模型目录", null);
        }

        try {
            // 第二遍：解压模型根目录下的文件（去掉 rootPrefix 前缀，使 json 落在模型目录根下）
            ZipInputStream zis2 = new ZipInputStream(
                    ctx.getContentResolver().openInputStream(uri));
            long total = 0;
            int fileCount = 0;
            ZipEntry e;
            while ((e = zis2.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName();
                if (!isUnderRoot(name, rootPrefix)) continue;
                String rel = relPathUnder(name, rootPrefix);
                if (rel.isEmpty()) continue;
                File outFile = new File(targetDir, rel);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                long size = writeEntry(zis2, outFile);
                total += size;
                fileCount++;
                if (total > MAX_TOTAL_SIZE) {
                    throw new java.io.IOException("解压内容过大，已取消");
                }
            }
            zis2.close();

            if (fileCount == 0) {
                throw new java.io.IOException("未解压到任何模型文件");
            }

            // 注册到 C++ 侧
            Live2DNative.nativeAddModelDir(targetDir.getName(), jsonBase);

            // 导入后自动补动作：零动作模型注册 Idle/TapBody，让新模型立即可表演
            String patch = autoPatchMotions(ctx, targetDir);
            if (patch == null) patch = "模型自带动作";

            Log.i(TAG, "imported model: " + targetDir.getName() + " files=" + fileCount);
            return new ImportResult(true,
                    "导入成功：" + targetDir.getName() + "（" + fileCount + " 个文件）\n" + patch,
                    targetDir.getName());
        } catch (Exception ex) {
            deleteRecursively(targetDir);
            return new ImportResult(false, "导入失败：" + ex.getMessage(), null);
        }
    }

    private static long writeEntry(ZipInputStream zis, File outFile) throws Exception {
        long size = 0;
        byte[] buf = new byte[8192];
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            int n;
            while ((n = zis.read(buf)) > 0) {
                size += n;
                if (size > MAX_ENTRY_SIZE) {
                    throw new java.io.IOException("文件超过大小限制：" + outFile.getName());
                }
                fos.write(buf, 0, n);
            }
        }
        return size;
    }

    /** 根目录前缀下的相对路径 */
    private static boolean isUnderRoot(String name, String rootPrefix) {
        if (rootPrefix.isEmpty()) return true;
        return name.startsWith(rootPrefix + "/");
    }

    private static String relPathUnder(String name, String rootPrefix) {
        if (rootPrefix.isEmpty()) return name;
        return name.substring(rootPrefix.length() + 1);
    }

    /** 从模型根目录前缀提取最外层目录名作为模型目录名；无目录时用 json 文件名 base */
    private static String extractTopDirName(String rootPrefix, String jsonPath) {
        if (rootPrefix != null && !rootPrefix.isEmpty()) {
            String[] parts = rootPrefix.split("/");
            if (parts.length > 0 && !parts[0].isEmpty()) return parts[0];
        }
        String f = jsonPath.substring(jsonPath.lastIndexOf('/') + 1);
        return stripModelJsonSuffix(f);
    }

    /** 去掉 .model3.json / .model.json 后缀，得到 C++ 拼接所需的 base 名 */
    private static String stripModelJsonSuffix(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : MODEL_JSON_NAMES) {
            if (lower.endsWith(suffix)) {
                return fileName.substring(0, fileName.length() - suffix.length());
            }
        }
        return fileName;
    }

    /**
     * 导入后自动补动作：若模型 json 未声明 Idle/TapBody 动作组（或声明了但文件缺失），
     * 从内置 huohuo 模型拷贝兼容动作并注册到模型 json，让"零动作"模型也能表演。
     * 返回补丁描述；无需修补时返回 null。
     */
    public static String autoPatchMotions(Context ctx, File modelDir) {
        if (modelDir == null || !modelDir.isDirectory()) return null;
        try {
            // 找模型 json（.model.json 优先）
            File jsonFile = null;
            for (String suffix : MODEL_JSON_NAMES) {
                jsonFile = findJsonFile(modelDir, suffix);
                if (jsonFile != null) break;
            }
            if (jsonFile == null) return null;

            org.json.JSONObject root = new org.json.JSONObject(readFileString(jsonFile));
            boolean isModel3 = jsonFile.getName().toLowerCase(Locale.ROOT).endsWith(".model3.json");
            org.json.JSONObject fr = isModel3 ? root.optJSONObject("FileReferences") : null;
            org.json.JSONObject motions = fr != null ? fr.optJSONObject("Motions") : null;
            if (motions == null) motions = root.optJSONObject("Motions");
            if (motions == null) motions = root.optJSONObject("motions");

            if (motions == null) {
                motions = new org.json.JSONObject();
            }
            boolean idleOk = hasUsableGroup(modelDir, motions, "Idle");
            boolean tapOk = hasUsableGroup(modelDir, motions, "TapBody");
            if (idleOk && tapOk) return null; // 无需修补

            StringBuilder patched = new StringBuilder();
            String idleFile = copyCompatMotion(ctx, modelDir, "keshui.motion3.json", "compat_idle.motion3.json");
            if (!idleOk && idleFile != null) {
                org.json.JSONArray arr = new org.json.JSONArray();
                org.json.JSONObject m = new org.json.JSONObject();
                m.put("File", idleFile);
                m.put("Loop", true);
                arr.put(m);
                motions.put("Idle", arr);
                patched.append("Idle");
            }
            String tapFile = copyCompatMotion(ctx, modelDir, "yaotou.motion3.json", "compat_tap.motion3.json");
            if (!tapOk && tapFile != null) {
                org.json.JSONArray arr = new org.json.JSONArray();
                org.json.JSONObject m = new org.json.JSONObject();
                m.put("File", tapFile);
                arr.put(m);
                motions.put("TapBody", arr);
                if (patched.length() > 0) patched.append("、");
                patched.append("TapBody");
            }
            if (patched.length() == 0) return null;

            if (isModel3) {
                if (fr == null) {
                    fr = new org.json.JSONObject();
                    root.put("FileReferences", fr);
                }
                fr.put("Motions", motions);
            } else {
                root.put("Motions", motions);
            }
            writeFileString(jsonFile, root.toString(2));
            Log.i(TAG, "autoPatchMotions: " + jsonFile.getName() + " added [" + patched + "]");
            return "已自动注册动作组: [" + patched + "]（从内置模型复制兼容动作，使零动作模型也能表演）";
        } catch (Exception e) {
            Log.w(TAG, "autoPatchMotions failed", e);
            return null;
        }
    }

    private static boolean hasUsableGroup(File modelDir, org.json.JSONObject motions, String group) {
        org.json.JSONArray arr = motions.optJSONArray(group);
        if (arr == null || arr.length() == 0) return false;
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            String file = m.optString("File", "");
            if (!file.isEmpty() && new File(modelDir, file).isFile()) return true;
        }
        return false;
    }

    /** 从 assets/huohuo 拷贝一个动作文件到模型目录（重命名），成功返回目标文件名 */
    private static String copyCompatMotion(Context ctx, File modelDir, String srcName, String dstName) {
        try (InputStream in = ctx.getAssets().open("huohuo/" + srcName);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(modelDir, dstName))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            return dstName;
        } catch (Exception e) {
            Log.w(TAG, "copyCompatMotion failed: " + srcName, e);
            return null;
        }
    }

    private static File findJsonFile(File dir, String suffix) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                File sub = findJsonFile(f, suffix);
                if (sub != null) return sub;
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(suffix)) {
                return f;
            }
        }
        return null;
    }

    private static String readFileString(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static void writeFileString(File f, String content) throws Exception {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(f)) {
            fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static String sanitizeDirName(String name) {
        if (name == null || name.trim().isEmpty()) return "model";
        return name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * 列出内部存储已导入的模型目录名（含 *.model3.json / *.model.json 的子目录）。
     */
    public static List<String> listImportedModelDirs(Context ctx) {
        File modelsRoot = new File(ctx.getFilesDir(), "models");
        if (!modelsRoot.isDirectory()) return Collections.emptyList();
        File[] children = modelsRoot.listFiles();
        if (children == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (File c : children) {
            if (c.isDirectory() && containsModelJson(c)) {
                out.add(c.getName());
            }
        }
        Collections.sort(out);
        return out;
    }

    private static boolean containsModelJson(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return false;
        for (File f : files) {
            if (f.isDirectory()) {
                if (containsModelJson(f)) return true;
            } else {
                String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(".model3.json") || name.endsWith(".model.json")) return true;
            }
        }
        return false;
    }

    /**
     * 扫描内部存储所有已导入模型并重新注册到 C++ 侧。
     * 应用重启后 C++ 动态模型列表为空，需在启动时恢复。
     */
    public static void registerImportedModels(Context ctx) {
        File modelsRoot = new File(ctx.getFilesDir(), "models");
        if (!modelsRoot.isDirectory()) return;
        File[] children = modelsRoot.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (!c.isDirectory()) continue;
            String jsonBase = findModelJsonBase(c);
            if (jsonBase == null) continue;
            Live2DNative.nativeAddModelDir(c.getName(), jsonBase);
            Log.i(TAG, "re-registered imported model: " + c.getName() + " (json=" + jsonBase + ")");
        }
    }

    /** 在模型目录中查找 model json，返回 base 名（不含扩展名），找不到返回 null */
    private static String findModelJsonBase(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.isDirectory()) {
                String sub = findModelJsonBase(f);
                if (sub != null) return sub;
            } else {
                String name = f.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".model3.json") || lower.endsWith(".model.json")) {
                    return stripModelJsonSuffix(name);
                }
            }
        }
        return null;
    }

    /** 删除目录（含子文件） */
    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
