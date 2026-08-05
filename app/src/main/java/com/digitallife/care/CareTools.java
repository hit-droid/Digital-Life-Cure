package com.digitallife.care;

import android.content.Context;

import com.digitallife.render.Live2DNative;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 护理大脑工具集：zip 解压分析、模型管理、动作管理、工作流、定时任务。
 * 全部在应用内完成，不依赖外部工具。每个操作返回结构化结果文本。
 */
public class CareTools {

    private static final String PREFS_WORKFLOW = "care_workflows";
    private static final String PREFS_SCHEDULE = "care_schedules";
    private static final long MAX_ZIP_SIZE = 400 * 1024 * 1024;
    private static final long MAX_ENTRY_SIZE = 100 * 1024 * 1024;

    private final Context ctx;
    private final android.content.SharedPreferences workflowPrefs;
    private final android.content.SharedPreferences schedulePrefs;

    public CareTools(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        workflowPrefs = ctx.getSharedPreferences(PREFS_WORKFLOW, Context.MODE_PRIVATE);
        schedulePrefs = ctx.getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE);
    }

    // ============ 工具注册 ============

    public JSONArray getToolSchemas() {
        JSONArray arr = new JSONArray();
        try {
            arr.put(makeSchema("inspect_zip", "解压 zip 压缩包并分析内容，返回文件树和模型文件识别结果", new String[]{"zipPath"}));
            arr.put(makeSchema("list_models", "列出所有已安装的 Live2D 模型"));
            arr.put(makeSchema("analyze_model", "完整分析模型：解析 model3.json，检查每个引用文件是否齐全，报告所有参数/纹理/动作/表情/物理信息", new String[]{"modelName"}));
            arr.put(makeSchema("install_model_from_zip", "解压模型 zip 到模型目录并注册，可直接使用", new String[]{"zipPath"}));
            arr.put(makeSchema("repair_model", "修复模型缺失文件", new String[]{"modelName"}));
            arr.put(makeSchema("list_motions", "列出模型所有动作及详情（时长/循环/曲线数）", new String[]{"modelName"}));
            arr.put(makeSchema("get_motion_detail", "查看单个动作的完整参数曲线", new String[]{"modelName", "motionName"}));
            arr.put(makeSchema("generate_motion", "创建新动作，可指定参数曲线", new String[]{"modelName", "motionName", "duration", "curves"}));
            arr.put(makeSchema("edit_motion", "修改动作：时长/循环/参数曲线", new String[]{"modelName", "motionName", "edits"}));
            arr.put(makeSchema("delete_motion", "删除模型动作", new String[]{"modelName", "motionName"}));
            arr.put(makeSchema("create_workflow", "创建多步骤工作流", new String[]{"name", "steps"}));
            arr.put(makeSchema("list_workflows", "列出所有工作流"));
            arr.put(makeSchema("delete_workflow", "删除工作流", new String[]{"name"}));
            arr.put(makeSchema("run_workflow", "执行工作流", new String[]{"name"}));
            arr.put(makeSchema("add_scheduled_task", "添加定时任务", new String[]{"name", "cronExpr", "workflowName"}));
            arr.put(makeSchema("list_scheduled_tasks", "列出所有定时任务"));
            arr.put(makeSchema("remove_scheduled_task", "删除定时任务", new String[]{"name"}));
        } catch (Exception ignored) {
        }
        return arr;
    }

    private JSONObject makeSchema(String name, String desc) throws Exception {
        return makeSchema(name, desc, new String[]{});
    }

    private JSONObject makeSchema(String name, String desc, String[] required) throws Exception {
        JSONObject schema = new JSONObject();
        schema.put("type", "function");
        JSONObject func = new JSONObject();
        func.put("name", name);
        func.put("description", desc);
        JSONObject params = new JSONObject();
        params.put("type", "object");
        JSONObject props = new JSONObject();
        for (String r : required) {
            JSONObject p = new JSONObject();
            p.put("type", "string");
            p.put("description", r);
            props.put(r, p);
        }
        params.put("properties", props);
        if (required.length > 0) {
            params.put("required", new JSONArray(Arrays.asList(required)));
        }
        func.put("parameters", params);
        schema.put("function", func);
        return schema;
    }

    // ============ 工具执行 ============

    public String execute(String toolName, JSONObject args) throws Exception {
        switch (toolName) {
            case "inspect_zip": return inspectZip(args.optString("zipPath", ""));
            case "list_models": return listModels();
            case "analyze_model": return analyzeModel(args.optString("modelName", ""));
            case "install_model_from_zip": return installModelFromZip(args.optString("zipPath", ""));
            case "repair_model": return repairModel(args.optString("modelName", ""));
            case "list_motions": return listMotions(args.optString("modelName", ""));
            case "get_motion_detail": return getMotionDetail(
                    args.optString("modelName", ""), args.optString("motionName", ""));
            case "generate_motion": return generateMotion(
                    args.optString("modelName", ""),
                    args.optString("motionName", ""),
                    args.optDouble("duration", 4.0),
                    args.optString("curves", "[]"));
            case "edit_motion": return editMotion(
                    args.optString("modelName", ""),
                    args.optString("motionName", ""),
                    args.optString("edits", "{}"));
            case "delete_motion": return deleteMotion(
                    args.optString("modelName", ""),
                    args.optString("motionName", ""));
            case "create_workflow": return createWorkflow(
                    args.optString("name", ""), args.optString("steps", "[]"));
            case "list_workflows": return listWorkflows();
            case "delete_workflow": return deleteWorkflow(args.optString("name", ""));
            case "run_workflow": return runWorkflow(args.optString("name", ""));
            case "add_scheduled_task": return addScheduledTask(
                    args.optString("name", ""), args.optString("cronExpr", ""), args.optString("workflowName", ""));
            case "list_scheduled_tasks": return listScheduledTasks();
            case "remove_scheduled_task": return removeScheduledTask(args.optString("name", ""));
            default: return "未知工具: " + toolName;
        }
    }

    // ============ zip 解压分析 ============

    /**
     * 解压 zip 到临时目录，返回文件树 + 模型文件识别。
     */
    public String inspectZip(String zipPath) {
        if (zipPath.isEmpty()) return "请提供 zip 文件路径。";
        File zipFile = new File(zipPath);
        if (!zipFile.exists()) return "文件不存在: " + zipPath;
        if (!zipFile.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return "不是 zip 文件: " + zipPath;
        }
        try {
            File targetDir = new File(ctx.getCacheDir(), "care_extract_" + System.currentTimeMillis());
            targetDir.mkdirs();
            long[] sizes = {0};
            int[] counts = {0};
            extractZip(zipFile, targetDir, sizes, counts);
            StringBuilder sb = new StringBuilder();
            sb.append("解压完成：共 ").append(counts[0]).append(" 个文件，")
              .append(formatSize(sizes[0])).append("\n\n");
            // 识别模型文件
            List<File> modelJsons = new ArrayList<>();
            collectModelJsons(targetDir, modelJsons);
            if (modelJsons.isEmpty()) {
                sb.append("⚠ 未识别到模型文件（.model3.json / .model.json）。\n");
            } else {
                sb.append("✅ 识别到模型定义文件：\n");
                for (File mj : modelJsons) {
                    sb.append("   - ").append(relPath(targetDir, mj)).append("\n");
                }
            }
            sb.append("\n文件结构：\n");
            sb.append(buildFileTree(targetDir, "", 0, 2));
            return sb.toString();
        } catch (Exception e) {
            return "解压失败: " + e.getMessage();
        }
    }

    private void extractZip(File zipFile, File targetDir, long[] sizes, int[] counts) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                if (entry.getSize() > MAX_ENTRY_SIZE) {
                    throw new Exception("单文件过大: " + entry.getName());
                }
                sizes[0] += entry.getSize();
                if (sizes[0] > MAX_ZIP_SIZE) {
                    throw new Exception("zip 总大小超限");
                }
                File out = new File(targetDir, entry.getName());
                out.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zis.read(buf)) != -1) os.write(buf, 0, n);
                }
                counts[0]++;
            }
        }
    }

    private void collectModelJsons(File dir, List<File> out) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectModelJsons(f, out);
            } else if (f.getName().endsWith(".model3.json") || f.getName().endsWith(".model.json")) {
                out.add(f);
            }
        }
    }

    private String buildFileTree(File dir, String prefix, int depth, int maxDepth) {
        StringBuilder sb = new StringBuilder();
        if (depth > maxDepth) {
            return "    ...\n";
        }
        File[] files = dir.listFiles();
        if (files == null) return "";
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        Collections.sort(sorted, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : sorted) {
            if (f.isDirectory()) {
                sb.append("    ").append("📁 ").append(f.getName()).append("/\n");
                sb.append(buildFileTree(f, prefix + "    ", depth + 1, maxDepth));
            } else {
                sb.append("    ").append("   ").append(f.getName())
                  .append(" (").append(formatSize(f.length())).append(")\n");
            }
        }
        return sb.toString();
    }

    // ============ 模型管理 ============

    private File getModelsDirSafe() {
        if (Live2DNative.getModelsDir() == null) {
            Live2DNative.init(ctx);
        }
        return Live2DNative.getModelsDir();
    }

    private String listModels() {
        File modelsDir = getModelsDirSafe();
        if (modelsDir == null) return "模型目录未初始化。";
        File[] dirs = modelsDir.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) {
            return "尚未安装任何模型。\n可以在护理大脑对话中上传模型 zip 文件，我会自动解压并安装。";
        }
        StringBuilder sb = new StringBuilder("已安装模型：\n");
        for (int i = 0; i < dirs.length; i++) {
            boolean hasJson = false;
            File[] jsons = dirs[i].listFiles((d, n) -> n.endsWith(".model3.json") || n.endsWith(".model.json"));
            hasJson = jsons != null && jsons.length > 0;
            sb.append("  ").append(i + 1).append(". ")
              .append(dirs[i].getName())
              .append(hasJson ? " ✅" : " ⚠ 缺模型定义")
              .append("\n");
        }
        return sb.toString();
    }

    /**
     * 完整分析模型：解析 model3.json 引用的每个文件，报告齐全性 + 全部资源。
     */
    private String analyzeModel(String modelName) {
        if (modelName.isEmpty()) return "请指定模型名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到模型目录: " + modelName;
        }
        StringBuilder report = new StringBuilder("🔍 模型分析: " + modelName + "\n");
        report.append("📁 位置: ").append(modelDir.getAbsolutePath()).append("\n\n");

        // 找 model3.json
        File model3 = firstFile(modelDir, ".model3.json");
        File modelJson = model3 != null ? null : firstFile(modelDir, ".model.json");

        if (model3 != null) {
            report.append("✅ 模型定义: ").append(model3.getName()).append("\n");
            try {
                JSONObject root = new JSONObject(readFile(model3));
                JSONObject fr = root.optJSONObject("FileReferences");
                if (fr != null) {
                    // Moc
                    report.append("   Moc: ").append(checkRef(modelDir, fr.optString("Moc", ""))).append("\n");
                    // Textures
                    JSONArray texs = fr.optJSONArray("Textures");
                    if (texs != null) {
                        report.append("   纹理 (").append(texs.length()).append(" 个):\n");
                        for (int i = 0; i < texs.length(); i++) {
                            String t = texs.optString(i, "");
                            report.append("     - ").append(checkRef(modelDir, t)).append("\n");
                        }
                    }
                    // Physics
                    String phys = fr.optString("Physics", "");
                    if (!phys.isEmpty()) {
                        report.append("   物理: ").append(checkRef(modelDir, phys)).append("\n");
                    }
                    // DisplayInfo
                    String disp = fr.optString("DisplayInfo", "");
                    if (!disp.isEmpty()) {
                        report.append("   显示信息: ").append(checkRef(modelDir, disp)).append("\n");
                    }
                    // Motions
                    JSONObject motions = fr.optJSONObject("Motions");
                    if (motions != null) {
                        report.append("   动作组 (").append(motions.length()).append(" 组):\n");
                        java.util.Iterator<String> it = motions.keys();
                        while (it.hasNext()) {
                            String group = it.next();
                            JSONArray arr = motions.optJSONArray(group);
                            report.append("     - ").append(group).append(" (").append(arr != null ? arr.length() : 0).append(" 个)\n");
                        }
                    }
                }
            } catch (Exception e) {
                report.append("   ⚠ 解析 model3.json 失败: ").append(e.getMessage()).append("\n");
            }
        } else if (modelJson != null) {
            report.append("✅ 模型定义: ").append(modelJson.getName()).append(" (Cubism 2.x)\n");
        } else {
            report.append("⚠ 缺少模型定义文件\n");
        }

        // 统计资源文件
        report.append("\n📦 资源统计:\n");
        int motions = countFiles(modelDir, ".motion3.json");
        int expressions = countFiles(modelDir, ".exp3.json");
        int physics = countFiles(modelDir, ".physics3.json");
        int textures = countFiles(modelDir, ".png") + countFiles(modelDir, ".jpg");
        int moc = countFiles(modelDir, ".moc3");
        report.append("   MOC3: ").append(moc).append(" 个\n");
        report.append("   纹理: ").append(textures).append(" 个\n");
        report.append("   动作: ").append(motions).append(" 个\n");
        report.append("   表情: ").append(expressions).append(" 个\n");
        report.append("   物理: ").append(physics).append(" 个\n");

        // 结论
        report.append("\n📋 结论:\n");
        List<String> problems = new ArrayList<>();
        if (moc == 0) problems.add("缺少 .moc3 骨骼文件，模型无法渲染");
        if (textures == 0) problems.add("缺少纹理图片");
        if (model3 == null && modelJson == null) problems.add("缺少模型定义文件");
        if (problems.isEmpty()) {
            report.append("   模型完整，可以正常使用 ✅\n");
        } else {
            for (String p : problems) {
                report.append("   ⚠ ").append(p).append("\n");
            }
            report.append("   可尝试用 repair_model 修复，或重新安装。\n");
        }
        return report.toString();
    }

    private String checkRef(File modelDir, String ref) {
        if (ref.isEmpty()) return "（未引用）";
        // 尝试相对模型目录解析；有些引用带前导目录
        File f = new File(modelDir, ref);
        if (f.exists()) return ref + " ✅";
        // 尝试去掉前导路径的最后一个目录
        File f2 = new File(modelDir, new File(ref).getName());
        if (f2.exists()) return ref + " ✅ (根目录)";
        return ref + " ❌ 缺失";
    }

    /**
     * 真正解压模型 zip 到模型目录并注册。
     */
    public String installModelFromZip(String zipPath) {
        if (zipPath.isEmpty()) return "请提供 zip 文件路径。";
        File zipFile = new File(zipPath);
        if (!zipFile.exists()) return "文件不存在: " + zipPath;
        if (!zipFile.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return "不是 zip 文件: " + zipPath;
        }
        try {
            // 先解压到临时目录分析
            File tmp = new File(ctx.getCacheDir(), "care_install_" + System.currentTimeMillis());
            tmp.mkdirs();
            long[] sizes = {0};
            int[] counts = {0};
            extractZip(zipFile, tmp, sizes, counts);

            // 找模型根目录（含 model3.json 的目录）
            File modelRoot = findModelRoot(tmp);
            if (modelRoot == null) {
                return "⚠ 压缩包内未找到模型文件（.model3.json / .model.json），无法安装。\n可以用 inspect_zip 先查看内容。";
            }

            // 模型名 = 模型根目录名
            String modelName = modelRoot.getName();
            // 若根目录就是临时目录，用 zip 文件名
            if (modelRoot.equals(tmp)) {
                modelName = zipFile.getName().replace(".zip", "").replaceAll("[^a-zA-Z0-9_\\-\\u4e00-\\u9fa5]", "_");
            }

            File modelsDir = getModelsDirSafe();
            File targetDir = new File(modelsDir, modelName);
            if (targetDir.exists()) {
                deleteRecursive(targetDir);
            }
            targetDir.mkdirs();
            copyRecursive(modelRoot, targetDir);

            // 注册到 native（如果引擎已启动）
            try {
                File model3 = firstFile(targetDir, ".model3.json");
                if (model3 != null) {
                    String base = model3.getName().replace(".model3.json", "");
                    Live2DNative.nativeAddModelDir(modelName, base);
                }
            } catch (Throwable ignored) {
                // 引擎未启动时跳过注册，下次启动 PetService 会重新注册
            }

            deleteRecursive(tmp);
            StringBuilder sb = new StringBuilder("✅ 模型安装成功: " + modelName + "\n");
            sb.append("   文件数: ").append(counts[0]).append("，大小: ").append(formatSize(sizes[0])).append("\n");
            File model3 = firstFile(targetDir, ".model3.json");
            if (model3 == null) {
                sb.append("   ⚠ 请确认模型包含 .model3.json 定义。\n");
            } else {
                sb.append("   定义文件: ").append(model3.getName()).append("\n");
            }
            sb.append("   现在可以在桌宠中切换到这个模型了。\n");
            return sb.toString();
        } catch (Exception e) {
            return "安装失败: " + e.getMessage();
        }
    }

    /** 找到包含模型 json 的目录（向上回溯） */
    private File findModelRoot(File dir) {
        File modelRoot = findModelRootRecursive(dir);
        return modelRoot;
    }

    private File findModelRootRecursive(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return null;
        boolean hasModelJson = false;
        File firstSub = null;
        for (File f : files) {
            if (f.isDirectory()) {
                if (firstSub == null) firstSub = f;
            } else if (f.getName().endsWith(".model3.json") || f.getName().endsWith(".model.json")) {
                hasModelJson = true;
            }
        }
        if (hasModelJson) return dir;
        if (firstSub != null) {
            File r = findModelRootRecursive(firstSub);
            if (r != null) return r;
        }
        return null;
    }

    private String repairModel(String modelName) {
        if (modelName.isEmpty()) return "请指定模型名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到模型目录: " + modelName;
        }
        StringBuilder report = new StringBuilder("🔧 修复模型: " + modelName + "\n");
        File model3 = firstFile(modelDir, ".model3.json");
        File moc = firstFile(modelDir, ".moc3");
        if (model3 == null && moc != null) {
            String base = moc.getName().replace(".moc3", "");
            createMinimalModel3Json(modelDir, base);
            report.append("   ✅ 已生成缺失的 model3.json\n");
        }
        if (model3 == null && moc == null) {
            report.append("   ⚠ 缺少 .moc3 和 .model3.json，无法自动修复，请重新安装。\n");
        }
        int textures = countFiles(modelDir, ".png") + countFiles(modelDir, ".jpg");
        if (textures == 0) {
            report.append("   ⚠ 缺少纹理文件，请补充纹理图片。\n");
        } else {
            report.append("   ✅ 纹理文件正常\n");
        }
        return report.toString();
    }

    // ============ 动作管理 ============

    private String listMotions(String modelName) {
        if (modelName.isEmpty()) return "请指定模型名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到模型目录: " + modelName;
        }
        File[] motions = modelDir.listFiles((d, n) -> n.endsWith(".motion3.json"));
        if (motions == null || motions.length == 0) {
            return "模型「" + modelName + "」暂无动作。可以用 generate_motion 创建。";
        }
        StringBuilder sb = new StringBuilder("🎬 模型「" + modelName + "」的动作列表:\n");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        Arrays.sort(motions, (a, b) -> a.getName().compareTo(b.getName()));
        for (int i = 0; i < motions.length; i++) {
            File m = motions[i];
            String name = m.getName().replace(".motion3.json", "");
            // 读取时长/循环/曲线
            try {
                JSONObject o = new JSONObject(readFile(m));
                JSONObject meta = o.optJSONObject("Meta");
                double dur = meta != null ? meta.optDouble("Duration", 0) : 0;
                boolean loop = meta != null && meta.optBoolean("Loop", false);
                int curves = meta != null ? meta.optInt("CurveCount", 0) : 0;
                sb.append("  ").append(i + 1).append(". ").append(name)
                  .append("  [").append(dur).append("s")
                  .append(loop ? " 循环" : " 单次")
                  .append(" ").append(curves).append("曲线]")
                  .append("\n");
            } catch (Exception e) {
                sb.append("  ").append(i + 1).append(". ").append(name).append("  [解析失败]\n");
            }
        }
        return sb.toString();
    }

    private String getMotionDetail(String modelName, String motionName) {
        if (modelName.isEmpty() || motionName.isEmpty()) return "请指定模型名称和动作名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) return "未找到模型目录: " + modelName;
        File m = resolveMotionFile(modelDir, motionName);
        if (m == null) return "未找到动作: " + motionName;
        try {
            JSONObject o = new JSONObject(readFile(m));
            JSONObject meta = o.optJSONObject("Meta");
            JSONArray curves = o.optJSONArray("Curves");
            StringBuilder sb = new StringBuilder("📄 动作: " + m.getName() + "\n");
            if (meta != null) {
                sb.append("  时长: ").append(meta.optDouble("Duration", 0)).append("s\n");
                sb.append("  FPS: ").append(meta.optDouble("Fps", 30)).append("\n");
                sb.append("  循环: ").append(meta.optBoolean("Loop", false) ? "是" : "否").append("\n");
                sb.append("  曲线数: ").append(meta.optInt("CurveCount", 0)).append("\n");
            }
            if (curves != null && curves.length() > 0) {
                sb.append("\n  参数曲线:\n");
                for (int i = 0; i < curves.length(); i++) {
                    JSONObject c = curves.optJSONObject(i);
                    if (c == null) continue;
                    String target = c.optString("Target", "");
                    String id = c.optString("Id", "");
                    JSONArray segs = c.optJSONArray("Segments");
                    sb.append("    ").append(i + 1).append(". [").append(target).append("] ")
                      .append(id).append("  ").append(segs != null ? segs.length() : 0).append(" 个关键帧\n");
                }
            } else {
                sb.append("\n  （无参数曲线）\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "解析动作失败: " + e.getMessage();
        }
    }

    private String generateMotion(String modelName, String motionName, double duration, String curvesJson) throws Exception {
        if (modelName.isEmpty() || motionName.isEmpty()) return "请指定模型名称和动作名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) return "未找到模型目录: " + modelName;
        String fileName = motionName.endsWith(".motion3.json") ? motionName : motionName + ".motion3.json";
        File motionFile = new File(modelDir, fileName);
        if (motionFile.exists()) {
            return "动作已存在: " + fileName + "，如需修改用 edit_motion。";
        }
        JSONObject motion = new JSONObject();
        motion.put("Version", 3);
        JSONObject meta = new JSONObject();
        meta.put("Duration", duration);
        meta.put("Fps", 30.0);
        meta.put("Loop", false);
        meta.put("AreBeziersRestricted", true);
        // 解析曲线
        JSONArray curves = new JSONArray();
        try {
            JSONArray input = new JSONArray(curvesJson);
            for (int i = 0; i < input.length(); i++) {
                JSONObject cd = input.optJSONObject(i);
                if (cd == null) continue;
                JSONObject curve = new JSONObject();
                curve.put("Target", cd.optString("target", "Parameter"));
                curve.put("Id", cd.optString("id", "ParamAngleX"));
                JSONArray segs = new JSONArray();
                segs.put(0.0);
                segs.put(cd.optDouble("startValue", 0.0));
                segs.put(cd.optDouble("midTime", duration / 2));
                segs.put(cd.optDouble("midValue", 0.0));
                segs.put(duration);
                segs.put(cd.optDouble("endValue", 0.0));
                curve.put("Segments", segs);
                curves.put(curve);
            }
        } catch (Exception ignored) {
        }
        int segCount = 0;
        int pointCount = 0;
        for (int i = 0; i < curves.length(); i++) {
            JSONArray segs = curves.optJSONObject(i).optJSONArray("Segments");
            segCount += segs.length() / 6;
            pointCount += segs.length() / 3;
        }
        meta.put("CurveCount", curves.length());
        meta.put("TotalSegmentCount", segCount);
        meta.put("TotalPointCount", pointCount);
        meta.put("UserDataCount", 0);
        meta.put("TotalUserDataSize", 0);
        motion.put("Meta", meta);
        motion.put("Curves", curves);
        motion.put("UserData", new JSONArray());
        try (OutputStream os = new FileOutputStream(motionFile)) {
            os.write(motion.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        return "✅ 已创建动作: " + fileName + " (" + duration + "s, " + curves.length() + " 条曲线)\n"
                + "可以在桌宠中用 nativeStartMotion 触发。";
    }

    private String editMotion(String modelName, String motionName, String editsJson) throws Exception {
        if (modelName.isEmpty() || motionName.isEmpty()) return "请指定模型名称和动作名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) return "未找到模型目录: " + modelName;
        File m = resolveMotionFile(modelDir, motionName);
        if (m == null) return "未找到动作: " + motionName;
        JSONObject motion = new JSONObject(readFile(m));
        JSONObject edits = new JSONObject(editsJson);
        if (edits.has("duration")) {
            motion.getJSONObject("Meta").put("Duration", edits.getDouble("duration"));
        }
        if (edits.has("loop")) {
            motion.getJSONObject("Meta").put("Loop", edits.getBoolean("loop"));
        }
        if (edits.has("addCurve")) {
            JSONObject cd = edits.getJSONObject("addCurve");
            JSONObject curve = new JSONObject();
            curve.put("Target", cd.optString("target", "Parameter"));
            curve.put("Id", cd.optString("id", "ParamAngleX"));
            JSONArray segs = new JSONArray();
            segs.put(0.0);
            segs.put(cd.optDouble("startValue", 0.0));
            segs.put(edits.optDouble("duration", motion.getJSONObject("Meta").optDouble("Duration", 4.0)));
            segs.put(cd.optDouble("endValue", 0.0));
            curve.put("Segments", segs);
            motion.getJSONArray("Curves").put(curve);
            JSONObject meta = motion.getJSONObject("Meta");
            meta.put("CurveCount", motion.getJSONArray("Curves").length());
        }
        if (edits.has("removeCurveId")) {
            String rmId = edits.getString("removeCurveId");
            JSONArray curves = motion.getJSONArray("Curves");
            JSONArray keep = new JSONArray();
            for (int i = 0; i < curves.length(); i++) {
                if (!curves.optJSONObject(i).optString("Id", "").equals(rmId)) {
                    keep.put(curves.get(i));
                }
            }
            motion.put("Curves", keep);
            motion.getJSONObject("Meta").put("CurveCount", keep.length());
        }
        try (OutputStream os = new FileOutputStream(m)) {
            os.write(motion.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        JSONObject meta = motion.getJSONObject("Meta");
        return "✅ 已更新动作: " + m.getName() + "\n"
                + "  时长: " + meta.optDouble("Duration", 0) + "s\n"
                + "  循环: " + meta.optBoolean("Loop", false) + "\n"
                + "  曲线数: " + meta.optInt("CurveCount", 0);
    }

    private String deleteMotion(String modelName, String motionName) {
        if (modelName.isEmpty() || motionName.isEmpty()) return "请指定模型名称和动作名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) return "未找到模型目录: " + modelName;
        File m = resolveMotionFile(modelDir, motionName);
        if (m == null) return "未找到动作: " + motionName;
        if (m.delete()) return "✅ 已删除动作: " + m.getName();
        return "删除失败: " + m.getName();
    }

    private File resolveMotionFile(File modelDir, String motionName) {
        String name = motionName.endsWith(".motion3.json") ? motionName : motionName + ".motion3.json";
        File f = new File(modelDir, name);
        if (f.exists()) return f;
        // 模糊匹配
        File[] motions = modelDir.listFiles((d, n) -> n.endsWith(".motion3.json"));
        if (motions != null) {
            for (File m : motions) {
                if (m.getName().toLowerCase(Locale.ROOT).contains(motionName.toLowerCase(Locale.ROOT))) {
                    return m;
                }
            }
        }
        return null;
    }

    // ============ 工作流管理 ============

    private String createWorkflow(String name, String stepsJson) throws Exception {
        if (name.isEmpty()) return "请指定工作流名称。";
        JSONArray steps = new JSONArray(stepsJson);
        JSONObject wf = new JSONObject();
        wf.put("name", name);
        wf.put("steps", steps);
        wf.put("createdAt", System.currentTimeMillis());
        workflowPrefs.edit().putString(name, wf.toString()).apply();
        return "✅ 已创建工作流「" + name + "」(" + steps.length() + " 步):\n" + steps.toString(2);
    }

    private String listWorkflows() {
        java.util.Map<String, ?> all = workflowPrefs.getAll();
        if (all.isEmpty()) return "暂无工作流。";
        StringBuilder sb = new StringBuilder("📋 已保存的工作流:\n");
        for (String key : all.keySet()) {
            try {
                JSONObject wf = new JSONObject((String) all.get(key));
                int stepCount = wf.optJSONArray("steps") != null ? wf.getJSONArray("steps").length() : 0;
                sb.append("  ✦ ").append(key).append(" (").append(stepCount).append(" 步)\n");
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    private String deleteWorkflow(String name) {
        if (name.isEmpty()) return "请指定工作流名称。";
        if (!workflowPrefs.contains(name)) return "未找到工作流: " + name;
        workflowPrefs.edit().remove(name).apply();
        return "✅ 已删除工作流: " + name;
    }

    private String runWorkflow(String name) throws Exception {
        if (name.isEmpty()) return "请指定工作流名称。";
        String raw = workflowPrefs.getString(name, null);
        if (raw == null) return "未找到工作流: " + name;
        JSONObject wf = new JSONObject(raw);
        JSONArray steps = wf.optJSONArray("steps");
        if (steps == null || steps.length() == 0) return "工作流「" + name + "」没有步骤。";
        StringBuilder result = new StringBuilder("▶ 执行工作流「" + name + "」:\n");
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            String tool = step.optString("tool", "");
            JSONObject args = step.optJSONObject("args");
            if (args == null) args = new JSONObject();
            result.append("  步骤 ").append(i + 1).append(": ").append(tool).append(" → ");
            try {
                String r = execute(tool, args);
                result.append("✅ 成功\n").append(r).append("\n");
            } catch (Exception e) {
                result.append("❌ 失败: ").append(e.getMessage()).append("\n");
                return result.toString();
            }
        }
        return result.toString();
    }

    // ============ 定时任务管理 ============

    private String addScheduledTask(String name, String cronExpr, String workflowName) {
        if (name.isEmpty() || cronExpr.isEmpty() || workflowName.isEmpty()) {
            return "请填写任务名称、cron 表达式和工作流名称。";
        }
        try {
            JSONObject task = new JSONObject();
            task.put("name", name);
            task.put("cronExpr", cronExpr);
            task.put("workflowName", workflowName);
            task.put("createdAt", System.currentTimeMillis());
            schedulePrefs.edit().putString(name, task.toString()).apply();
            return "✅ 已添加定时任务「" + name + "」\n  cron: " + cronExpr + "\n  执行工作流: " + workflowName;
        } catch (Exception e) {
            return "添加定时任务失败: " + e.getMessage();
        }
    }

    private String listScheduledTasks() {
        java.util.Map<String, ?> all = schedulePrefs.getAll();
        if (all.isEmpty()) return "暂无定时任务。";
        StringBuilder sb = new StringBuilder("⏰ 定时任务列表:\n");
        for (String key : all.keySet()) {
            try {
                JSONObject t = new JSONObject((String) all.get(key));
                sb.append("  ✦ ").append(key)
                  .append("  [cron: ").append(t.optString("cronExpr", "?"))
                  .append("] → ").append(t.optString("workflowName", "?"))
                  .append("\n");
            } catch (Exception ignored) {
            }
        }
        return sb.toString();
    }

    private String removeScheduledTask(String name) {
        if (name.isEmpty()) return "请指定任务名称。";
        if (!schedulePrefs.contains(name)) return "未找到定时任务: " + name;
        schedulePrefs.edit().remove(name).apply();
        return "✅ 已删除定时任务: " + name;
    }

    // ============ 工具方法 ============

    private File findModelDir(String modelName) {
        File modelsDir = getModelsDirSafe();
        if (modelsDir == null) return null;
        File dir = new File(modelsDir, modelName);
        if (dir.exists() && dir.isDirectory()) return dir;
        File[] dirs = modelsDir.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                if (d.getName().toLowerCase(Locale.ROOT).contains(modelName.toLowerCase(Locale.ROOT))) {
                    return d;
                }
            }
        }
        return null;
    }

    private static File firstFile(File dir, String suffix) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(suffix));
        if (files != null && files.length > 0) return files[0];
        return null;
    }

    private static int countFiles(File dir, String suffix) {
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

    private static void createMinimalModel3Json(File modelDir, String baseName) {
        try {
            JSONObject model3 = new JSONObject();
            model3.put("Version", 3);
            JSONObject fr = new JSONObject();
            fr.put("Moc", baseName + ".moc3");
            JSONArray textures = new JSONArray();
            textures.put(baseName + ".2048/texture_00.png");
            fr.put("Textures", textures);
            model3.put("FileReferences", fr);
            model3.put("Groups", new JSONArray());
            File out = new File(modelDir, baseName + ".model3.json");
            try (OutputStream os = new FileOutputStream(out)) {
                os.write(model3.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private static String readFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private static void copyRecursive(File src, File dst) throws Exception {
        if (src.isDirectory()) {
            if (!dst.exists()) dst.mkdirs();
            File[] files = src.listFiles();
            if (files != null) {
                for (File f : files) {
                    copyRecursive(f, new File(dst, f.getName()));
                }
            }
        } else {
            try (FileInputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
    }

    private static void deleteRecursive(File dir) {
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

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String relPath(File root, File f) {
        return f.getAbsolutePath().substring(root.getAbsolutePath().length())
                .replaceAll("^[/\\\\]", "");
    }
}