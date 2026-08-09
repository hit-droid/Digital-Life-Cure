package com.digitallife.model;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模型能力感知：判断一个模型目录是否真正具备可用动作（Motions）。
 *
 * 背景：本 App 的动作来自 model.json/model3.json 的 FileReferences.Motions
 * 声明的动作组（如 Idle/TapBody）。有些 VTS 型模型虽带 .motion3.json 文件，
 * 但 json 未声明动作组，或声明的动作文件缺失（如"哥特妹妹"），这类模型
 * 在 App 里播放不出任何表演动作。L1 引擎据此让位或全量接管：
 *  - 有动作（组存在且引用的动作文件齐全）→ 让模型自带动画做主，L1 让位
 *  - 无动作（没组、组为空、或引用文件缺失）→ L1 全量接管制造活物感
 *
 * 内置模型在 assets/，导入模型在 files/models/，统一从这里读取判定。
 */
public class ModelInspector {

    private static final String TAG = "ModelInspector";

    /** model json 后缀（.model.json 优先，与 C++ 加载顺序一致） */
    private static final String[] JSON_NAMES = {".model.json", ".model3.json"};

    private ModelInspector() {
    }

    /**
     * 判断模型目录是否具备可用动作。
     *
     * @param modelDirName 模型目录名（assets 或 files/models 下的子目录名）
     * @return true = 声明了动作组且引用的动作文件齐全，false = 无动作或动作残缺
     */
    public static boolean hasUsableMotions(Context ctx, String modelDirName) {
        if (modelDirName == null || modelDirName.isEmpty()) return false;
        try {
            ModelJsonInfo info = findModelJson(ctx, modelDirName);
            if (info == null) return false;

            JSONObject root = new JSONObject(info.content);
            // model3: FileReferences.Motions；Cubism 2: 顶层 Motions/motions
            JSONObject motions = null;
            JSONObject fr = root.optJSONObject("FileReferences");
            if (fr != null) motions = fr.optJSONObject("Motions");
            if (motions == null) motions = root.optJSONObject("Motions");
            if (motions == null) motions = root.optJSONObject("motions");
            if (motions == null || motions.length() == 0) return false;

            // 至少一个组引用的动作文件存在才算"有动作"
            java.util.Iterator<String> it = motions.keys();
            while (it.hasNext()) {
                String group = it.next();
                JSONArray arr = motions.optJSONArray(group);
                if (arr == null || arr.length() == 0) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject motion = arr.optJSONObject(i);
                    if (motion == null) continue;
                    String file = motion.optString("File", "");
                    if (!file.isEmpty() && exists(ctx, info.baseDir, info.subDir, file)) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            Log.w(TAG, "hasUsableMotions failed for " + modelDirName, e);
            return false;
        }
    }

    /** 列出模型声明的动作组名（用于补动作判断） */
    public static List<String> listMotionGroups(Context ctx, String modelDirName) {
        List<String> groups = new ArrayList<>();
        try {
            ModelJsonInfo info = findModelJson(ctx, modelDirName);
            if (info == null) return groups;
            JSONObject root = new JSONObject(info.content);
            JSONObject motions = null;
            JSONObject fr = root.optJSONObject("FileReferences");
            if (fr != null) motions = fr.optJSONObject("Motions");
            if (motions == null) motions = root.optJSONObject("Motions");
            if (motions == null) motions = root.optJSONObject("motions");
            if (motions == null) return groups;
            java.util.Iterator<String> it = motions.keys();
            while (it.hasNext()) groups.add(it.next());
        } catch (Exception ignored) {
        }
        return groups;
    }

    // ==================== 内部 ====================

    private static final class ModelJsonInfo {
        String baseDir;   // "assets" 或 "files"
        String subDir;    // 模型目录名
        String fileName;  // 模型 json 文件名
        String content;   // json 内容
    }

    /** 读取模型 json：优先 files/models/<dir>，其次 assets/<dir> */
    private static ModelJsonInfo findModelJson(Context ctx, String modelDirName) {
        File filesDir = new File(new File(ctx.getFilesDir(), "models"), modelDirName);
        if (filesDir.isDirectory()) {
            for (String suffix : JSON_NAMES) {
                File f = new File(filesDir, findFileName(filesDir, suffix));
                if (f.isFile()) {
                    try {
                        ModelJsonInfo info = new ModelJsonInfo();
                        info.baseDir = "files";
                        info.subDir = modelDirName;
                        info.fileName = f.getName();
                        info.content = readFile(f);
                        return info;
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        // assets
        try {
            String[] names = ctx.getAssets().list(modelDirName);
            if (names != null) {
                for (String suffix : JSON_NAMES) {
                    for (String n : names) {
                        if (n.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                            try (InputStream in = ctx.getAssets().open(modelDirName + "/" + n)) {
                                byte[] buf = new byte[in.available()];
                                int read = 0;
                                while (read < buf.length) {
                                    int r = in.read(buf, read, buf.length - read);
                                    if (r < 0) break;
                                    read += r;
                                }
                                ModelJsonInfo info = new ModelJsonInfo();
                                info.baseDir = "assets";
                                info.subDir = modelDirName;
                                info.fileName = n;
                                info.content = new String(buf, 0, read, StandardCharsets.UTF_8);
                                return info;
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String findFileName(File dir, String suffix) {
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(suffix));
        if (files != null && files.length > 0) return files[0].getName();
        return "none";
    }

    private static String readFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        return sb.toString();
    }

    /** 判断模型目录下某相对路径的文件是否存在（assets 或 files） */
    private static boolean exists(Context ctx, String baseDir, String modelDir, String relPath) {
        String rel = relPath.replace('\\', '/');
        if (baseDir.equals("files")) {
            return new File(new File(new File(ctx.getFilesDir(), "models"), modelDir), rel).isFile();
        }
        try {
            ctx.getAssets().open(modelDir + "/" + rel).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
