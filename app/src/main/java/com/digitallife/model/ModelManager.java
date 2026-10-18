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
            return new ImportResult(false, "导入失败：" + com.digitallife.ui.UiKit.safeMsg(e), null);
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
            return new ImportResult(false, "导入失败：" + com.digitallife.ui.UiKit.safeMsg(ex), null);
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

    /** 校验 zip 条目路径安全：必须在根目录前缀下，且不含路径穿越段（Zip Slip） */
    private static boolean isUnderRoot(String name, String rootPrefix) {
        if (name == null || name.isEmpty()) return false;
        if (name.startsWith("/") || name.contains("\\")) return false;
        if (!rootPrefix.isEmpty() && !name.startsWith(rootPrefix + "/")) return false;
        for (String seg : name.split("/")) {
            if (seg.equals("..") || seg.isEmpty()) return false;
        }
        return true;
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

    // ==================== 动作写回（跨模型动作池用） ====================

    /** 目标模型是否可写：必须是已导入模型（files/models/&lt;dir&gt; 且含模型 json）。内置模型在 assets，只读。 */
    public static boolean isWritableModel(Context ctx, String dirName) {
        if (dirName == null || dirName.isEmpty()) return false;
        File dir = new File(new File(ctx.getFilesDir(), "models"), dirName);
        return dir.isDirectory() && containsModelJson(dir);
    }

    /**
     * 把一段动作文件写入目标「导入」模型目录，并注册到指定动作组。
     *
     * <p>对应动作池页的「复用 / 导入」：文件落在模型目录根（与 {@link #autoPatchMotions} 同约定，
     * 重名自动加 {@code _1/_2} 后缀），json 写回前先备份为 {@code .bak}。
     * 注意 Live2D 动作绑定参数 ID，跨不同骨骼的模型复用可能错位/无效果——仅同源模型间有意义。</p>
     *
     * @return 结果描述（成功或失败原因），供界面直接提示
     */
    public static String registerMotionIntoModel(Context ctx, String targetDirName,
                                                 String fileName, byte[] data, String groupName) {
        if (!isWritableModel(ctx, targetDirName)) {
            return "目标不可写：内置模型只读，请选择已导入的模型";
        }
        if (data == null || data.length == 0) {
            return "动作文件内容为空，已取消";
        }
        try {
            File dir = new File(new File(ctx.getFilesDir(), "models"), targetDirName);
            // 1) 写入动作文件（重名则加后缀，不覆盖已有动作）
            String safe = sanitizeFileName(fileName);
            File out = new File(dir, safe);
            int n = 1;
            while (out.exists()) out = new File(dir, addSuffix(safe, n++));
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(data);
            }

            // 2) 找到模型 json，把新文件追加进 Motions 的指定组
            ModelJson mj = loadModelJson(dir);
            if (mj == null) {
                return "已写入动作文件 " + out.getName() + "，但未找到模型 json，未注册动作组";
            }
            addMotionToGroup(mj.motions, groupName, out.getName());
            saveModelJson(mj);
            Log.i(TAG, "registerMotionIntoModel: " + out.getName() + " -> "
                    + targetDirName + "/" + groupName);
            return "已把 " + out.getName() + " 注册到「" + groupName + "」组";
        } catch (Exception e) {
            Log.w(TAG, "registerMotionIntoModel failed", e);
            return "操作失败：" + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    /**
     * 把模型目录内**已存在**的动作文件（典型：动作池里检出的「未注册文件」）注册进指定动作组。
     *
     * <p>与 {@link #registerMotionIntoModel} 的唯一区别：不复制文件，只改模型 json——用于把
     * 磁盘上孤立、引擎播不出的 {@code .motion3.json} 一键挂进某个组。同组已有同文件则跳过。</p>
     */
    public static String registerExistingFileIntoModel(Context ctx, String targetDirName,
                                                       String relFile, String groupName) {
        if (!isWritableModel(ctx, targetDirName)) {
            return "目标不可写：内置模型只读，请选择已导入的模型";
        }
        String rel = normalizeRel(relFile);
        if (rel.isEmpty()) return "未指定动作文件";
        if (groupName == null || groupName.trim().isEmpty()) return "请填写动作组名";
        try {
            File dir = new File(new File(ctx.getFilesDir(), "models"), targetDirName);
            if (!new File(dir, rel).isFile()) return "文件不存在：" + rel;
            ModelJson mj = loadModelJson(dir);
            if (mj == null) return "未找到模型 json，无法注册动作组";
            boolean added = addMotionToGroup(mj.motions, groupName.trim(), rel);
            if (!added) return rel + " 已在「" + groupName + "」组中，未重复注册";
            saveModelJson(mj);
            return "已把 " + baseName(rel) + " 注册到「" + groupName + "」组，可点「播」验证";
        } catch (Exception e) {
            Log.w(TAG, "registerExistingFileIntoModel failed", e);
            return "操作失败：" + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    /**
     * 从指定动作组移除一个动作的注册（只改 json，**文件保留**，会变成「未注册文件」）。
     * 组因此变空则删除该组。
     */
    public static String removeMotionFromModel(Context ctx, String targetDirName,
                                               String groupName, String relFile) {
        if (!isWritableModel(ctx, targetDirName)) {
            return "目标不可写：内置模型只读";
        }
        String rel = normalizeRel(relFile);
        if (rel.isEmpty() || groupName == null || groupName.isEmpty()) return "参数不完整";
        try {
            File dir = new File(new File(ctx.getFilesDir(), "models"), targetDirName);
            ModelJson mj = loadModelJson(dir);
            if (mj == null) return "未找到模型 json";
            org.json.JSONArray arr = mj.motions.optJSONArray(groupName);
            if (arr == null) return "未找到动作组：" + groupName;
            org.json.JSONArray next = new org.json.JSONArray();
            boolean removed = false;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                if (!removed && rel.equals(normalizeRel(it.optString("File", "")))) {
                    removed = true;
                    continue;
                }
                next.put(it);
            }
            if (!removed) return "该动作不在「" + groupName + "」组中";
            if (next.length() == 0) mj.motions.remove(groupName);
            else mj.motions.put(groupName, next);
            saveModelJson(mj);
            return "已从「" + groupName + "」移除注册（文件保留，可在下方「未注册文件」区重新注册）";
        } catch (Exception e) {
            Log.w(TAG, "removeMotionFromModel failed", e);
            return "操作失败：" + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    /** 重命名动作组（改 Motions 的 key）；新名已存在则把旧组成员并入新组 */
    public static String renameMotionGroup(Context ctx, String targetDirName,
                                           String oldName, String newName) {
        if (!isWritableModel(ctx, targetDirName)) {
            return "目标不可写：内置模型只读";
        }
        if (oldName == null || oldName.isEmpty()) return "旧组名缺失";
        String nn = newName == null ? "" : newName.trim();
        if (nn.isEmpty()) return "新组名不能为空";
        if (nn.equals(oldName)) return "新旧组名相同";
        try {
            File dir = new File(new File(ctx.getFilesDir(), "models"), targetDirName);
            ModelJson mj = loadModelJson(dir);
            if (mj == null) return "未找到模型 json";
            org.json.JSONArray arr = mj.motions.optJSONArray(oldName);
            if (arr == null) return "未找到动作组：" + oldName;
            org.json.JSONArray exist = mj.motions.optJSONArray(nn);
            if (exist == null) {
                mj.motions.put(nn, arr);
                mj.motions.remove(oldName);
                saveModelJson(mj);
                return "已把动作组「" + oldName + "」重命名为「" + nn + "」";
            }
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject it = arr.optJSONObject(i);
                if (it != null) exist.put(it);
            }
            mj.motions.remove(oldName);
            saveModelJson(mj);
            return "「" + nn + "」已存在，已把「" + oldName + "」的动作并入其中";
        } catch (Exception e) {
            Log.w(TAG, "renameMotionGroup failed", e);
            return "操作失败：" + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    // ==================== 表情写回（动作池表情区用） ====================

    /**
     * 把一段表情文件写入目标「导入」模型目录，并以指定 Name 注册进 {@code Expressions}。
     *
     * <p>与动作不同，表情由引擎**按 Name 播放**（如 {@code F07}），重名会互相覆盖，
     * 故先查重再落盘；重名文件自动加 {@code _1/_2} 后缀，json 写回前先备份 {@code .bak}。</p>
     */
    public static String registerExpressionIntoModel(Context ctx, String targetDirName,
                                                     String fileName, byte[] data, String exprName) {
        if (!isWritableModel(ctx, targetDirName)) {
            return "目标不可写：内置模型只读，请选择已导入的模型";
        }
        if (data == null || data.length == 0) return "表情文件内容为空，已取消";
        String name = exprName == null ? "" : exprName.trim();
        if (name.isEmpty()) return "请填写表情名（引擎按名字播放，如 F07）";
        try {
            File dir = new File(new File(ctx.getFilesDir(), "models"), targetDirName);
            ModelJson mj = loadModelJson(dir);
            if (mj == null) return "未找到模型 json，无法注册表情";
            if (exprNameExists(mj.expressions, name)) {
                return "表情名「" + name + "」已被占用（引擎按名字播放，重名会互相覆盖），请换一个";
            }
            String safe = sanitizeFileName(fileName, ".exp3.json");
            File out = new File(dir, safe);
            int n = 1;
            while (out.exists()) out = new File(dir, addSuffix(safe, n++));
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(data);
            }
            addExpression(mj.expressions, name, out.getName());
            saveModelJson(mj);
            Log.i(TAG, "registerExpressionIntoModel: " + out.getName() + " as " + name
                    + " -> " + targetDirName);
            return "已把 " + out.getName() + " 注册为表情「" + name + "」";
        } catch (Exception e) {
            Log.w(TAG, "registerExpressionIntoModel failed", e);
            return "操作失败：" + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    /**
     * 把模型目录内**已存在**的表情文件（典型：动作池检出的「未注册表情」）按指定 Name 注册。
     * 与 {@link #registerExpressionIntoModel} 的区别：不复制文件，只改模型 json。
     */
    public static String registerExistingFileAsExpression(Context ctx, String targetDirName,
                                                         String relFile, String exprName) {
        if (!isWritableModel(ctx, targetDirName)) {
            return "目标不可写：内置模型只读，请选择已导入的模型";
        }
        String rel = normalizeRel(relFile);
        if (rel.isEmpty()) return "未指定表情文件";
        String name = exprName == null ? "" : exprName.trim();
        if (name.isEmpty()) return "请填写表情名（如 F07）";
        try {
            File dir = new File(new File(ctx.getFilesDir(), "models"), targetDirName);
            if (!new File(dir, rel).isFile()) return "文件不存在：" + rel;
            ModelJson mj = loadModelJson(dir);
            if (mj == null) return "未找到模型 json，无法注册表情";
            if (exprNameExists(mj.expressions, name)) {
                return "表情名「" + name + "」已被占用，请换一个";
            }
            addExpression(mj.expressions, name, rel);
            saveModelJson(mj);
            return "已把 " + baseName(rel) + " 注册为表情「" + name + "」，可点「播」验证";
        } catch (Exception e) {
            Log.w(TAG, "registerExistingFileAsExpression failed", e);
            return "操作失败：" + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    /** 从 Expressions 移除一个表情的注册（只改 json，**文件保留**，会变成「未注册表情」） */
    public static String removeExpressionFromModel(Context ctx, String targetDirName, String exprName) {
        if (!isWritableModel(ctx, targetDirName)) {
            return "目标不可写：内置模型只读";
        }
        String name = exprName == null ? "" : exprName.trim();
        if (name.isEmpty()) return "参数不完整";
        try {
            File dir = new File(new File(ctx.getFilesDir(), "models"), targetDirName);
            ModelJson mj = loadModelJson(dir);
            if (mj == null) return "未找到模型 json";
            org.json.JSONArray next = new org.json.JSONArray();
            boolean removed = false;
            for (int i = 0; i < mj.expressions.length(); i++) {
                org.json.JSONObject it = mj.expressions.optJSONObject(i);
                if (it == null) continue;
                if (!removed && name.equals(it.optString("Name", ""))) {
                    removed = true;
                    continue;
                }
                next.put(it);
            }
            if (!removed) return "未找到表情：" + name;
            mj.expressions = next;
            saveModelJson(mj);
            return "已移除表情「" + name + "」的注册（文件保留，可在下方「未注册表情」区重新注册）";
        } catch (Exception e) {
            Log.w(TAG, "removeExpressionFromModel failed", e);
            return "操作失败：" + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    /** 重命名表情的 Name（引擎按名播放，故旧名→新名即改指向；重名拒绝） */
    public static String renameExpression(Context ctx, String targetDirName,
                                          String oldName, String newName) {
        if (!isWritableModel(ctx, targetDirName)) {
            return "目标不可写：内置模型只读";
        }
        if (oldName == null || oldName.isEmpty()) return "旧表情名缺失";
        String nn = newName == null ? "" : newName.trim();
        if (nn.isEmpty()) return "新表情名不能为空";
        if (nn.equals(oldName)) return "新旧表情名相同";
        try {
            File dir = new File(new File(ctx.getFilesDir(), "models"), targetDirName);
            ModelJson mj = loadModelJson(dir);
            if (mj == null) return "未找到模型 json";
            if (exprNameExists(mj.expressions, nn)) return "表情名「" + nn + "」已被占用，请换一个";
            boolean found = false;
            for (int i = 0; i < mj.expressions.length(); i++) {
                org.json.JSONObject it = mj.expressions.optJSONObject(i);
                if (it != null && oldName.equals(it.optString("Name", ""))) {
                    it.put("Name", nn);
                    found = true;
                }
            }
            if (!found) return "未找到表情：" + oldName;
            saveModelJson(mj);
            return "已把表情「" + oldName + "」重命名为「" + nn + "」";
        } catch (Exception e) {
            Log.w(TAG, "renameExpression failed", e);
            return "操作失败：" + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    private static boolean exprNameExists(org.json.JSONArray arr, String name) {
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject it = arr.optJSONObject(i);
            if (it != null && name.equals(it.optString("Name", ""))) return true;
        }
        return false;
    }

    private static void addExpression(org.json.JSONArray arr, String name, String file) throws Exception {
        org.json.JSONObject item = new org.json.JSONObject();
        item.put("Name", name);
        item.put("File", file);
        arr.put(item);
    }

    /** 模型 json 的内存视图：记录 Motions / Expressions 的落点，写回时按原样放回 */
    private static final class ModelJson {
        File file;
        org.json.JSONObject root;
        org.json.JSONObject motions;
        boolean atFileReferences;
        boolean isModel3;
        /** 装载时 Motions 是否本就存在（避免表情操作把空的 Motions 塞进 json） */
        boolean motionsExisted;
        /** 表情声明数组（{@code {Name, File}}） */
        org.json.JSONArray expressions;
        /** 表情落点是否为 FileReferences.Expressions（Live2D 规范位置，也是新建时的默认） */
        boolean exprAtFileReferences;
        /** 装载时 Expressions 是否本就存在 */
        boolean expressionsExisted;
    }

    /**
     * 装载模型 json。加载顺序与 C++ / MotionLibrary 一致：优先 {@code .model.json}，
     * 其次 {@code .model3.json}；Motions 优先取 {@code FileReferences.Motions}，再退回根级
     * {@code Motions/motions}；Expressions 优先取 {@code FileReferences.Expressions}，再退回根级
     * {@code Expressions/expressions}——与扫描层完全对齐，确保「看到的」和「写回的」是同一处。
     */
    private static ModelJson loadModelJson(File dir) throws Exception {
        File jsonFile = findJsonFile(dir, ".model.json");
        if (jsonFile == null) jsonFile = findJsonFile(dir, ".model3.json");
        if (jsonFile == null) return null;
        boolean isModel3 = jsonFile.getName().toLowerCase(Locale.ROOT).endsWith(".model3.json");
        org.json.JSONObject root = new org.json.JSONObject(readFileString(jsonFile));
        org.json.JSONObject fr = root.optJSONObject("FileReferences");
        org.json.JSONObject motions = fr != null ? fr.optJSONObject("Motions") : null;
        boolean atFr = motions != null;
        if (motions == null) {
            motions = root.optJSONObject("Motions");
            if (motions == null) motions = root.optJSONObject("motions");
        }
        boolean motionsExisted = motions != null;
        if (motions == null) {
            motions = new org.json.JSONObject();
            atFr = isModel3; // 新建：model3 落 FileReferences.Motions，model.json 落根级 Motions
        }

        // 表情：始终是数组；新建时落 FileReferences（Live2D 规范位置，实测内置模型也在此）
        org.json.JSONArray expressions = fr != null ? fr.optJSONArray("Expressions") : null;
        boolean exprAtFr = expressions != null;
        if (expressions == null) expressions = root.optJSONArray("Expressions");
        if (expressions == null) expressions = root.optJSONArray("expressions");
        boolean expressionsExisted = expressions != null;
        if (expressions == null) {
            expressions = new org.json.JSONArray();
            exprAtFr = true;
        }

        ModelJson mj = new ModelJson();
        mj.file = jsonFile;
        mj.root = root;
        mj.motions = motions;
        mj.atFileReferences = atFr;
        mj.isModel3 = isModel3;
        mj.motionsExisted = motionsExisted;
        mj.expressions = expressions;
        mj.exprAtFileReferences = exprAtFr;
        mj.expressionsExisted = expressionsExisted;
        return mj;
    }

    private static void saveModelJson(ModelJson mj) throws Exception {
        // Motions：只在「本就有」或「本次确实加了内容」时写回，避免顺带注入空 Motions
        if (mj.motionsExisted || mj.motions.length() > 0) {
            if (mj.atFileReferences) {
                org.json.JSONObject fr = mj.root.optJSONObject("FileReferences");
                if (fr == null) {
                    fr = new org.json.JSONObject();
                    mj.root.put("FileReferences", fr);
                }
                fr.put("Motions", mj.motions);
            } else {
                mj.root.put("Motions", mj.motions);
            }
        }
        // Expressions：同理，避免无谓改动
        if (mj.expressionsExisted || mj.expressions.length() > 0) {
            if (mj.exprAtFileReferences) {
                org.json.JSONObject fr = mj.root.optJSONObject("FileReferences");
                if (fr == null) {
                    fr = new org.json.JSONObject();
                    mj.root.put("FileReferences", fr);
                }
                fr.put("Expressions", mj.expressions);
            } else {
                mj.root.put("Expressions", mj.expressions);
            }
        }
        backupFile(mj.file);
        writeFileString(mj.file, mj.root.toString(2));
    }

    /** 把文件加进动作组；返回 false 表示该组已含此文件（不重复注册） */
    private static boolean addMotionToGroup(org.json.JSONObject motions, String group, String file)
            throws Exception {
        org.json.JSONArray arr = motions.optJSONArray(group);
        if (arr == null) {
            arr = new org.json.JSONArray();
            motions.put(group, arr);
        }
        for (int i = 0; i < arr.length(); i++) {
            org.json.JSONObject it = arr.optJSONObject(i);
            if (it != null && file.equals(normalizeRel(it.optString("File", "")))) return false;
        }
        org.json.JSONObject item = new org.json.JSONObject();
        item.put("File", file);
        arr.put(item);
        return true;
    }

    private static String normalizeRel(String path) {
        return path == null ? "" : path.replace('\\', '/').trim();
    }

    private static String baseName(String path) {
        String p = path == null ? "" : path;
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** 写回模型 json 前先备份为 {@code .bak}，出错可手工还原 */
    private static void backupFile(File f) {
        try {
            File bak = new File(f.getAbsolutePath() + ".bak");
            try (java.io.FileInputStream in = new java.io.FileInputStream(f);
                 FileOutputStream out = new FileOutputStream(bak)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "backupFile failed: " + f.getName(), e);
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null || name.trim().isEmpty()) return "motion.motion3.json";
        String n = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!n.toLowerCase(Locale.ROOT).endsWith(".json")) n = n + ".motion3.json";
        return n;
    }

    /** 表情文件名清洗：空名兜底 {@code file.exp3.json}，并补上 defaultSuffix 后缀 */
    private static String sanitizeFileName(String name, String defaultSuffix) {
        if (name == null || name.trim().isEmpty()) return "file" + defaultSuffix;
        String n = name.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!n.toLowerCase(Locale.ROOT).endsWith(".json")) n = n + defaultSuffix;
        return n;
    }

    private static String addSuffix(String name, int n) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return name + "_" + n;
        return name.substring(0, dot) + "_" + n + name.substring(dot);
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
