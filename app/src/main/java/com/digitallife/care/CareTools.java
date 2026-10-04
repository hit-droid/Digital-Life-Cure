package com.digitallife.care;

import android.content.Context;

import com.digitallife.model.ModelManager;
import com.digitallife.render.Live2DGLView;
import com.digitallife.render.Live2DNative;
import com.digitallife.service.PetService;
import com.digitallife.util.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
            arr.put(makeSchema("install_model_from_zip", "解压模型 zip 到模型目录并注册，零动作模型自动补动作，可直接使用", new String[]{"zipPath"}));
            arr.put(makeSchema("switch_model", "切换到指定模型（内置或已导入），切换后自动成为默认模型", new String[]{"modelName"}));
            arr.put(makeSchema("set_default_model", "把指定模型设为默认模型（下次启动自动加载）", new String[]{"modelName"}));
            arr.put(makeSchema("delete_model", "删除已导入的模型（内置模型不可删除）", new String[]{"modelName"}));
            arr.put(makeSchema("repair_model", "修复模型缺失文件", new String[]{"modelName"}));
            arr.put(makeSchema("list_model_files", "列出模型目录内全部文件（含子目录），排查缺失/多余文件", new String[]{"modelName"}));
            arr.put(makeSchema("read_model_file", "读取模型目录内任意文件内容（json/文本），检查配置错误", new String[]{"modelName", "path"}));
            arr.put(makeSchema("write_model_file", "写入/覆盖模型目录内某个文件（自动备份原文件为 .bak），修复损坏配置", new String[]{"modelName", "path", "content"}));
            arr.put(makeSchema("fix_model_references", "自动修复 model3.json 中的文件引用路径（按实际文件匹配修正），检查每个引用是否有效", new String[]{"modelName"}));
            arr.put(makeSchema("backup_model", "备份整个模型目录到备份区，修改模型前建议先备份", new String[]{"modelName"}));
            arr.put(makeSchema("restore_model", "从最近的备份恢复模型目录", new String[]{"modelName"}));
            arr.put(makeSchema("list_motions", "列出模型所有动作及详情（时长/循环/曲线数）", new String[]{"modelName"}));
            arr.put(makeSchema("get_motion_detail", "查看单个动作的完整参数曲线", new String[]{"modelName", "motionName"}));
            arr.put(makeSchema("generate_motion", "创建新动作，可指定参数曲线", new String[]{"modelName", "motionName", "duration", "curves"}));
            arr.put(makeSchema("edit_motion", "修改动作：时长/循环/参数曲线", new String[]{"modelName", "motionName", "edits"}));
            arr.put(makeSchema("delete_motion", "删除模型动作", new String[]{"modelName", "motionName"}));
            arr.put(makeSchema("repair_motion", "修复损坏或缺少 Meta 字段的动作文件（motion3.json），恢复为可播放的合法结构", new String[]{"modelName", "motionName"}));
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
            p.put("description", paramDescription(r));
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

    /** 参数名 → 中文含义/格式说明，帮助 LLM 正确传参 */
    private static String paramDescription(String name) {
        switch (name) {
            case "modelName": return "模型目录名，先通过 list_models 查看可用模型";
            case "zipPath": return "zip 文件的绝对路径";
            case "path": return "模型目录内的相对路径，如 foo.model3.json 或 sub/bar.png";
            case "content": return "文件内容（JSON 文本或纯文本）";
            case "motionName": return "动作文件名，如 idle.motion3.json 或 Idle（自动补后缀）";
            case "duration": return "动作时长（秒），浮点数如 2.0 或 4.0";
            case "curves": return "参数曲线数组 JSON，格式 [{\"id\":\"ParamAngleX\",\"target\":\"Parameter\",\"startValue\":0,\"endValue\":30}]";
            case "edits": return "编辑操作 JSON，可选字段: duration(浮点数) loop(true/false) addCurve(对象) removeCurveId(字符串)";
            case "name": return "名称（唯一标识）";
            case "steps": return "步骤数组 JSON，格式 [{\"tool\":\"工具名\",\"args\":{...}}]";
            case "cronExpr": return "cron 表达式，如 '0 0 * * *' 每天零点";
            case "workflowName": return "已创建的工作流名称";
            case "action": return "动作名，如 Idle/TapBody/拍手/挥手";
            default: return name;
        }
    }

    // ============ 工具执行 ============

    public String execute(String toolName, JSONObject args) {
        try {
            return dispatch(toolName, args);
        } catch (Exception e) {
            // 兜底：任何工具异常都不上抛给 UI 崩溃，转成友好提示
            return "❌ 工具执行异常: " + com.digitallife.ui.UiKit.safeMsg(e)
                    + "\n请检查参数格式是否正确后重试。";
        }
    }

    private String dispatch(String toolName, JSONObject args) throws Exception {
        switch (toolName) {
            case "inspect_zip": return inspectZip(args.optString("zipPath", ""));
            case "list_models": return listModels();
            case "analyze_model": return analyzeModel(args.optString("modelName", ""));
            case "install_model_from_zip": return installModelFromZip(args.optString("zipPath", ""));
            case "switch_model": return switchModel(args.optString("modelName", ""));
            case "set_default_model": return setDefaultModel(args.optString("modelName", ""));
            case "delete_model": return deleteModel(args.optString("modelName", ""));
            case "repair_model": return repairModel(args.optString("modelName", ""));
            case "list_model_files": return listModelFiles(args.optString("modelName", ""));
            case "read_model_file": return readModelFile(
                    args.optString("modelName", ""), args.optString("path", ""));
            case "write_model_file": return writeModelFile(
                    args.optString("modelName", ""),
                    args.optString("path", ""),
                    args.optString("content", ""));
            case "fix_model_references": return fixModelReferences(args.optString("modelName", ""));
            case "backup_model": return backupModel(args.optString("modelName", ""));
            case "restore_model": return restoreModel(args.optString("modelName", ""));
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
            case "repair_motion": return repairMotion(
                    args.optString("modelName", ""), args.optString("motionName", ""));
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
              .append(CareFileOps.formatSize(sizes[0])).append("\n\n");
            // 识别模型文件
            List<File> modelJsons = new ArrayList<>();
            collectModelJsons(targetDir, modelJsons);
            if (modelJsons.isEmpty()) {
                sb.append("⚠ 未识别到模型文件（.model3.json / .model.json）。\n");
            } else {
                sb.append("✅ 识别到模型定义文件：\n");
                for (File mj : modelJsons) {
                    sb.append("   - ").append(CareFileOps.relPath(targetDir, mj)).append("\n");
                }
            }
            sb.append("\n文件结构：\n");
            sb.append(buildFileTree(targetDir, "", 0, 2));
            return sb.toString();
        } catch (Exception e) {
            return "解压失败: " + com.digitallife.ui.UiKit.safeMsg(e);
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
                // Zip Slip 防护：拒绝绝对路径、父目录穿越，目标必须落在解压目录内
                File out = CareFileOps.safeResolve(targetDir, entry.getName());
                if (out == null) {
                    throw new Exception("zip 包含非法路径: " + entry.getName());
                }
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
                  .append(" (").append(CareFileOps.formatSize(f.length())).append(")\n");
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
        File model3 = CareFileOps.firstFile(modelDir, ".model3.json");
        File modelJson = model3 != null ? null : CareFileOps.firstFile(modelDir, ".model.json");

        if (model3 != null) {
            report.append("✅ 模型定义: ").append(model3.getName()).append("\n");
            try {
                JSONObject root = new JSONObject(CareFileOps.readFile(model3));
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
                report.append("   ⚠ 解析 model3.json 失败: ").append(com.digitallife.ui.UiKit.safeMsg(e)).append("\n");
            }
        } else if (modelJson != null) {
            report.append("✅ 模型定义: ").append(modelJson.getName()).append(" (Cubism 2.x)\n");
        } else {
            report.append("⚠ 缺少模型定义文件\n");
        }

        // 统计资源文件
        report.append("\n📦 资源统计:\n");
        int motions = CareFileOps.countFiles(modelDir, ".motion3.json");
        int expressions = CareFileOps.countFiles(modelDir, ".exp3.json");
        int physics = CareFileOps.countFiles(modelDir, ".physics3.json");
        int textures = CareFileOps.countFiles(modelDir, ".png") + CareFileOps.countFiles(modelDir, ".jpg");
        int moc = CareFileOps.countFiles(modelDir, ".moc3");
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
     * 直接在 zip 内扫描 .model3.json/.model.json，确定模型根目录前缀并精准解压，
     * 不再依赖「解压后逐层找根目录」（原逻辑遇 README/预览图目录会找不到模型）。
     */
    public String installModelFromZip(String zipPath) {
        if (zipPath.isEmpty()) return "请提供 zip 文件路径。";
        File zipFile = new File(zipPath);
        if (!zipFile.exists()) return "文件不存在: " + zipPath;
        if (!zipFile.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return "不是 zip 文件: " + zipPath;
        }
        try {
            // 第一遍：扫描 zip 内的模型定义文件，取最浅的 json 作为主模型
            String jsonPath = null;
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (lower.endsWith(".model3.json") || lower.endsWith(".model.json")) {
                        if (jsonPath == null || name.length() < jsonPath.length()) {
                            jsonPath = name;
                        }
                    }
                }
            }
            if (jsonPath == null) {
                return "⚠ 压缩包内未找到模型定义文件（.model3.json / .model.json），无法安装。\n可以用 inspect_zip 先查看内容。";
            }

            // 模型根目录前缀（json 所在目录）
            String rootPrefix = jsonPath.substring(0, jsonPath.lastIndexOf('/'));
            if (rootPrefix.endsWith("/")) {
                rootPrefix = rootPrefix.substring(0, rootPrefix.length() - 1);
            }

            // 模型目录名 = json 所在最外层目录名；无目录时用 json 文件名 base
            String modelName = extractTopDirName(rootPrefix, jsonPath);

            // json base 名（C++ 自己拼扩展名）
            String jsonBase = CareFileOps.stripModelJsonSuffix(jsonPath.substring(jsonPath.lastIndexOf('/') + 1));

            File modelsDir = getModelsDirSafe();
            if (modelsDir == null) return "模型目录未初始化。";
            File targetDir = new File(modelsDir, CareFileOps.sanitizeDirName(modelName));
            if (targetDir.exists()) {
                CareFileOps.deleteRecursive(targetDir);
            }
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return "无法创建模型目录: " + targetDir.getName();
            }

            // 第二遍：只解压模型根目录下的文件（去掉 rootPrefix 前缀）
            long total = 0;
            int fileCount = 0;
            try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    String name = entry.getName();
                    if (rootPrefix.isEmpty()) {
                        if (name.contains("/")) continue; // 只取顶层文件
                    } else if (!name.startsWith(rootPrefix + "/")) {
                        continue;
                    }
                    String rel = rootPrefix.isEmpty() ? name : name.substring(rootPrefix.length() + 1);
                    if (rel.isEmpty()) continue;
                    File outFile = new File(targetDir, rel);
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    long size = writeZipEntry(zis, outFile);
                    total += size;
                    fileCount++;
                    if (total > MAX_ZIP_SIZE) {
                        throw new Exception("解压内容过大，已取消");
                    }
                }
            }

            if (fileCount == 0) {
                throw new Exception("未解压到任何模型文件");
            }

            // 注册到 native（如果引擎已启动）
            try {
                Live2DNative.nativeAddModelDir(targetDir.getName(), jsonBase);
            } catch (Throwable ignored) {
                // 引擎未启动时跳过注册，下次启动 PetService 会重新注册
            }

            // 自动补动作：零动作模型注册 Idle/TapBody，让新模型立即可表演
            String patch = ModelManager.autoPatchMotions(ctx, targetDir);
            if (patch == null) patch = "模型自带动作";

            StringBuilder sb = new StringBuilder("✅ 模型安装成功: " + targetDir.getName() + "\n");
            sb.append("   文件数: ").append(fileCount).append("，大小: ").append(CareFileOps.formatSize(total)).append("\n");
            sb.append("   定义文件: ").append(jsonPath).append("\n");
            sb.append("   ").append(patch).append("\n");
            sb.append("   现在可以在桌宠中切换到" ).append(targetDir.getName()).append("了。\n");
            return sb.toString();
        } catch (Exception e) {
            return "安装失败: " + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    private long writeZipEntry(ZipInputStream zis, File outFile) throws Exception {
        long size = 0;
        byte[] buf = new byte[8192];
        try (OutputStream os = new FileOutputStream(outFile)) {
            int n;
            while ((n = zis.read(buf)) != -1) {
                size += n;
                if (size > MAX_ENTRY_SIZE) {
                    throw new Exception("单文件过大: " + outFile.getName());
                }
                os.write(buf, 0, n);
            }
        }
        return size;
    }

    private String extractTopDirName(String rootPrefix, String jsonPath) {
        if (rootPrefix != null && !rootPrefix.isEmpty()) {
            String[] parts = rootPrefix.split("/");
            if (parts.length > 0 && !parts[0].isEmpty()) return parts[0];
        }
        String f = jsonPath.substring(jsonPath.lastIndexOf('/') + 1);
        return CareFileOps.stripModelJsonSuffix(f);
    }

    // ============ 模型统一管理（护理大脑） ============

    /** 切换到指定模型（内置或已导入），并记为新默认 */
    private String switchModel(String modelName) {
        if (modelName.isEmpty()) return "请指定模型名称。";
        int count = Live2DNative.nativeGetModelCount();
        String matched = null;
        for (int i = 0; i < count; i++) {
            String n = Live2DNative.nativeGetModelDirName(i);
            if (n == null) continue;
            if (n.equalsIgnoreCase(modelName)
                    || n.toLowerCase(Locale.ROOT).contains(modelName.toLowerCase(Locale.ROOT))) {
                matched = n;
                break;
            }
        }
        if (matched == null) {
            return "未找到模型: " + modelName + "。可用 list_models 查看全部模型。";
        }
        PetService svc = PetService.getInstance();
        if (svc == null) {
            return "桌宠未启动，无法切换。请先启动桌宠再让护理大脑切换模型。";
        }
        svc.switchToModelByName(matched);
        return "已切换模型至: " + matched + "（已设为默认，下次启动自动加载）";
    }

    /** 设置默认模型：下次启动自动加载 */
    private String setDefaultModel(String modelName) {
        if (modelName.isEmpty()) return "请指定模型名称。";
        int count = Live2DNative.nativeGetModelCount();
        String matched = null;
        for (int i = 0; i < count; i++) {
            String n = Live2DNative.nativeGetModelDirName(i);
            if (n == null) continue;
            if (n.equalsIgnoreCase(modelName)
                    || n.toLowerCase(Locale.ROOT).contains(modelName.toLowerCase(Locale.ROOT))) {
                matched = n;
                break;
            }
        }
        if (matched == null) {
            return "未找到模型: " + modelName + "。可用 list_models 查看全部模型。";
        }
        new Settings(ctx).setDefaultModelDir(matched);
        return "已将「" + matched + "」设为默认模型，下次启动自动加载。";
    }

    /** 删除已导入的模型（内置 assets 模型不可删除） */
    private String deleteModel(String modelName) {
        if (modelName.isEmpty()) return "请指定模型名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到已导入的模型: " + modelName + "（内置模型不可删除）";
        }
        String name = modelDir.getName();
        CareFileOps.deleteRecursive(modelDir);
        // 若默认模型被删除，清除默认记录
        Settings settings = new Settings(ctx);
        if (name.equals(settings.getDefaultModelDir())) {
            settings.setDefaultModelDir("");
        }
        return "已删除模型: " + name + "。\n下次启动后将从可用列表消失。";
    }

    private String repairModel(String modelName) {
        if (modelName.isEmpty()) return "请指定模型名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到模型目录: " + modelName;
        }
        StringBuilder report = new StringBuilder("🔧 修复模型: " + modelName + "\n");
        File model3 = CareFileOps.firstFile(modelDir, ".model3.json");
        File moc = CareFileOps.firstFile(modelDir, ".moc3");
        if (model3 == null && moc != null) {
            String base = moc.getName().replace(".moc3", "");
            createMinimalModel3Json(modelDir, base);
            report.append("   ✅ 已生成缺失的 model3.json\n");
        }
        if (model3 == null && moc == null) {
            report.append("   ⚠ 缺少 .moc3 和 .model3.json，无法自动修复，请重新安装。\n");
        }
        int textures = CareFileOps.countFiles(modelDir, ".png") + CareFileOps.countFiles(modelDir, ".jpg");
        if (textures == 0) {
            report.append("   ⚠ 缺少纹理文件，请补充纹理图片。\n");
        } else {
            report.append("   ✅ 纹理文件正常\n");
        }
        return report.toString();
    }

    // ============ 模型文件级工具（修复/排查） ============

    private String listModelFiles(String modelName) {
        File dir = findModelDir(modelName);
        if (dir == null || !dir.exists()) return "未找到模型目录: " + modelName;
        StringBuilder sb = new StringBuilder("📁 模型「" + modelName + "」文件结构:\n");
        appendFileTree(dir, sb, 0, 3);
        return sb.toString();
    }

    private void appendFileTree(File dir, StringBuilder sb, int depth, int maxDepth) {
        File[] files = dir.listFiles();
        if (files == null) return;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        String pad = depth == 0 ? "  " : "     ".repeat(Math.max(0, depth));
        for (File f : files) {
            if (f.isDirectory()) {
                sb.append(pad).append("📁 ").append(f.getName()).append("/\n");
                if (depth < maxDepth) appendFileTree(f, sb, depth + 1, maxDepth);
            } else {
                sb.append(pad).append("   ").append(f.getName())
                  .append(" (").append(CareFileOps.formatSize(f.length())).append(")\n");
            }
        }
    }

    private String readModelFile(String modelName, String path) {
        File dir = findModelDir(modelName);
        if (dir == null || !dir.exists()) return "未找到模型目录: " + modelName;
        if (path == null || path.trim().isEmpty()) return "请指定相对路径（如 foo.model3.json）。";
        File f = CareFileOps.safeResolve(dir, path);
        if (f == null) return "路径非法（不允许访问模型目录之外）: " + path;
        if (!f.exists()) return "文件不存在: " + path;
        if (f.isDirectory()) return "「" + path + "」是目录，请指定文件路径。";
        if (f.length() > 512 * 1024) return "文件过大（>512KB），拒绝读取: " + path;
        try {
            String content = CareFileOps.readFile(f);
            if (content.length() > 8000) {
                content = content.substring(0, 8000) + "\n...（内容过长已截断）";
            }
            return "📄 " + path + " (" + CareFileOps.formatSize(f.length()) + "):\n" + content;
        } catch (Exception e) {
            return "读取失败: " + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    private String writeModelFile(String modelName, String path, String content) throws Exception {
        File dir = findModelDir(modelName);
        if (dir == null || !dir.exists()) return "未找到模型目录: " + modelName;
        if (path == null || path.trim().isEmpty()) return "请指定相对路径。";
        if (content == null || content.trim().isEmpty()) return "写入内容为空。";
        File f = CareFileOps.safeResolve(dir, path);
        if (f == null) return "路径非法（不允许写入模型目录之外）: " + path;
        // json 文件校验合法性，避免写入损坏配置
        String low = path.toLowerCase(Locale.ROOT);
        if (low.endsWith(".json")) {
            try {
                String c = content.trim();
                if (c.startsWith("[")) {
                    new JSONArray(c);
                } else {
                    new JSONObject(c);
                }
            } catch (Exception e) {
                return "❌ JSON 格式不合法，未写入: " + com.digitallife.ui.UiKit.safeMsg(e);
            }
        }
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        if (f.exists()) {
            File bak = new File(f.getAbsolutePath() + ".bak");
            CareFileOps.copyFile(f, bak);
        }
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return "✅ 已写入 " + path + "（原文件已备份为 .bak）";
    }

    /** 修复 model3.json 引用路径：按实际文件匹配修正大小写/目录差异 */
    private String fixModelReferences(String modelName) throws Exception {
        File dir = findModelDir(modelName);
        if (dir == null || !dir.exists()) return "未找到模型目录: " + modelName;
        File model3 = CareFileOps.firstFile(dir, ".model3.json");
        if (model3 == null) {
            File moc = CareFileOps.firstFile(dir, ".moc3");
            if (moc != null) {
                String base = moc.getName().replace(".moc3", "");
                createMinimalModel3Json(dir, base);
                return "✅ 缺少 model3.json，已根据 " + moc.getName() + " 自动生成最小定义。";
            }
            return "⚠ 缺少 model3.json 与 .moc3，无法自动修复，请重新安装。";
        }
        JSONObject root = new JSONObject(CareFileOps.readFile(model3));
        JSONObject fr = root.optJSONObject("FileReferences");
        if (fr == null) return "⚠ model3.json 缺少 FileReferences 字段，无法修复引用。";
        StringBuilder sb = new StringBuilder("🔧 引用修复结果:\n");
        int fixed = 0;
        fixed += fixSingleRef(dir, fr, "Moc", sb);
        fixed += fixSingleRef(dir, fr, "Physics", sb);
        fixed += fixSingleRef(dir, fr, "DisplayInfo", sb);
        fixed += fixSingleRef(dir, fr, "Pose", sb);
        // 纹理数组
        JSONArray texs = fr.optJSONArray("Textures");
        if (texs != null) {
            for (int i = 0; i < texs.length(); i++) {
                String ref = texs.optString(i, "");
                String match = resolveRef(dir, ref);
                if (match == null) {
                    sb.append("   ⚠ 纹理缺失且未找到匹配文件: ").append(ref).append("\n");
                } else if (!match.equals(ref)) {
                    texs.put(i, match);
                    fixed++;
                    sb.append("   ✅ 纹理: ").append(ref).append(" → ").append(match).append("\n");
                }
            }
            fr.put("Textures", texs);
        }
        // 动作组
        JSONObject motions = fr.optJSONObject("Motions");
        if (motions != null) {
            java.util.Iterator<String> it = motions.keys();
            while (it.hasNext()) {
                String group = it.next();
                JSONArray arr = motions.optJSONArray(group);
                if (arr == null) continue;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    if (m == null || !m.has("File")) continue;
                    String ref = m.optString("File", "");
                    String match = resolveRef(dir, ref);
                    if (match == null) {
                        sb.append("   ⚠ 动作缺失且未找到匹配文件: ").append(ref).append("\n");
                    } else if (!match.equals(ref)) {
                        m.put("File", match);
                        fixed++;
                        sb.append("   ✅ 动作: ").append(ref).append(" → ").append(match).append("\n");
                    }
                }
            }
            fr.put("Motions", motions);
        }
        if (fixed == 0) {
            sb.append("   所有引用均有效，无需修复 ✅\n");
        } else {
            root.put("FileReferences", fr);
            File bak = new File(model3.getAbsolutePath() + ".bak");
            CareFileOps.copyFile(model3, bak);
            try (OutputStream os = new FileOutputStream(model3)) {
                os.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            sb.append("   ⚠ 共修正 ").append(fixed).append(" 处引用并写回（原文件备份为 .bak）。\n");
            sb.append("   若桌宠正在运行，可重新启动桌宠让修复生效。");
        }
        return sb.toString();
    }

    private int fixSingleRef(File dir, JSONObject fr, String key, StringBuilder sb) throws Exception {
        if (!fr.has(key)) return 0;
        String ref = fr.optString(key, "");
        if (ref.isEmpty()) return 0;
        String match = resolveRef(dir, ref);
        if (match == null) {
            sb.append("   ⚠ ").append(key).append(" 缺失且未找到匹配文件: ").append(ref).append("\n");
            return 0;
        }
        if (!match.equals(ref)) {
            fr.put(key, match);
            sb.append("   ✅ ").append(key).append(": ").append(ref).append(" → ").append(match).append("\n");
            return 1;
        }
        return 0;
    }

    /** 解析引用：优先按原路径；不存在则遍历目录按文件名（忽略大小写）匹配实际文件 */
    private String resolveRef(File dir, String ref) {
        if (ref == null || ref.isEmpty()) return null;
        String norm = ref.replace("\\", "/");
        File direct = new File(dir, norm);
        if (direct.exists()) return norm;
        String fileName = new File(norm).getName();
        List<File> stack = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all != null) Collections.addAll(stack, all);
        while (!stack.isEmpty()) {
            File f = stack.remove(stack.size() - 1);
            if (f.isDirectory()) {
                File[] sub = f.listFiles();
                if (sub != null) Collections.addAll(stack, sub);
            } else if (f.getName().equalsIgnoreCase(fileName)) {
                return f.getAbsolutePath().substring(dir.getAbsolutePath().length() + 1)
                        .replace("\\", "/");
            }
        }
        return null;
    }

    /** 备份模型目录到备份区，并记录最近一次备份路径 */
    private String backupModel(String modelName) {
        File dir = findModelDir(modelName);
        if (dir == null || !dir.exists()) return "未找到模型目录: " + modelName;
        File backupsRoot = new File(ctx.getCacheDir(), "care_backups");
        if (!backupsRoot.exists() && !backupsRoot.mkdirs()) return "无法创建备份目录。";
        String dirName = CareFileOps.sanitizeDirName(dir.getName());
        File target = new File(backupsRoot, dirName + "_" + System.currentTimeMillis());
        try {
            CareFileOps.copyRecursive(dir, target);
        } catch (Exception e) {
            return "备份失败: " + com.digitallife.ui.UiKit.safeMsg(e);
        }
        ctx.getSharedPreferences("care_backups", Context.MODE_PRIVATE).edit()
                .putString(dir.getName(), target.getAbsolutePath()).apply();
        return "✅ 已备份「" + dir.getName() + "」→ " + target.getName() + "\n修改模型前先备份是好习惯。";
    }

    /** 从最近备份恢复模型目录 */
    private String restoreModel(String modelName) {
        File dir = findModelDir(modelName);
        if (dir == null || !dir.exists()) return "未找到模型目录: " + modelName;
        String backupPath = ctx.getSharedPreferences("care_backups", Context.MODE_PRIVATE)
                .getString(dir.getName(), null);
        if (backupPath == null) {
            File backupsRoot = new File(ctx.getCacheDir(), "care_backups");
            File[] bks = backupsRoot.listFiles((d, n) -> n.toLowerCase(Locale.ROOT)
                    .startsWith(dir.getName().toLowerCase(Locale.ROOT)));
            if (bks != null && bks.length > 0) {
                // 按修改时间降序（最新在前）
                Arrays.sort(bks, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                backupPath = bks[0].getAbsolutePath();
            }
        }
        if (backupPath == null) {
            return "未找到「" + dir.getName() + "」的备份，请先用 backup_model 备份。";
        }
        File backup = new File(backupPath);
        if (!backup.exists()) return "备份不存在: " + backupPath;
        try {
            CareFileOps.deleteRecursive(dir);
            if (!dir.mkdirs()) throw new Exception("无法创建模型目录");
            CareFileOps.copyRecursive(backup, dir);
        } catch (Exception e) {
            return "恢复失败: " + com.digitallife.ui.UiKit.safeMsg(e);
        }
        return "✅ 已从备份恢复模型「" + dir.getName() + "」。\n若桌宠正在运行，可重新启动桌宠让恢复生效。";
    }

    /** 修复动作文件：损坏则重建最小结构，缺 Meta 则补全 */
    private String repairMotion(String modelName, String motionName) throws Exception {
        File dir = findModelDir(modelName);
        if (dir == null || !dir.exists()) return "未找到模型目录: " + modelName;
        File m = resolveMotionFile(dir, motionName);
        if (m == null) return "未找到动作: " + motionName;
        JSONObject motion;
        try {
            motion = new JSONObject(CareFileOps.readFile(m));
        } catch (Exception e) {
            // 完全损坏：备份后重建最小合法结构
            File bak = new File(m.getAbsolutePath() + ".bak");
            CareFileOps.copyFile(m, bak);
            JSONObject rebuilt = new JSONObject();
            rebuilt.put("Version", 3);
            JSONObject meta = new JSONObject();
            meta.put("Duration", 4.0);
            meta.put("Fps", 30.0);
            meta.put("Loop", false);
            meta.put("AreBeziersRestricted", true);
            meta.put("CurveCount", 0);
            meta.put("TotalSegmentCount", 0);
            meta.put("TotalPointCount", 0);
            meta.put("UserDataCount", 0);
            meta.put("TotalUserDataSize", 0);
            rebuilt.put("Meta", meta);
            rebuilt.put("Curves", new JSONArray());
            rebuilt.put("UserData", new JSONArray());
            try (OutputStream os = new FileOutputStream(m)) {
                os.write(rebuilt.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return "✅ 动作文件损坏，已重建为最小合法结构（4s 单次）: " + m.getName() + "\n原文件已备份为 .bak";
        }
        StringBuilder report = new StringBuilder("🔧 修复动作: " + m.getName() + "\n");
        boolean changed = false;
        JSONObject meta = motion.optJSONObject("Meta");
        if (meta == null) {
            meta = new JSONObject();
            meta.put("Duration", 4.0);
            meta.put("Fps", 30.0);
            meta.put("Loop", false);
            meta.put("AreBeziersRestricted", true);
            meta.put("CurveCount", 0);
            meta.put("TotalSegmentCount", 0);
            meta.put("TotalPointCount", 0);
            meta.put("UserDataCount", 0);
            meta.put("TotalUserDataSize", 0);
            motion.put("Meta", meta);
            changed = true;
            report.append("   ✅ 补全缺失的 Meta 字段\n");
        }
        if (!motion.has("Version")) {
            motion.put("Version", 3);
            changed = true;
        }
        if (motion.opt("Curves") == null || !(motion.opt("Curves") instanceof JSONArray)) {
            motion.put("Curves", new JSONArray());
            meta.put("CurveCount", 0);
            changed = true;
        }
        if (motion.opt("UserData") == null) {
            motion.put("UserData", new JSONArray());
            changed = true;
        }
        if (changed) {
            File bak = new File(m.getAbsolutePath() + ".bak");
            CareFileOps.copyFile(m, bak);
            try (OutputStream os = new FileOutputStream(m)) {
                os.write(motion.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            report.append("   ✅ 已修复并写回（原文件备份为 .bak）\n");
        } else {
            report.append("   动作文件结构正常，无需修复 ✅\n");
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
                JSONObject o = new JSONObject(CareFileOps.readFile(m));
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
            JSONObject o = new JSONObject(CareFileOps.readFile(m));
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
            return "解析动作失败: " + com.digitallife.ui.UiKit.safeMsg(e);
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
        JSONObject motion = new JSONObject(CareFileOps.readFile(m));
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
                result.append("❌ 失败: ").append(com.digitallife.ui.UiKit.safeMsg(e)).append("\n");
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
            return "添加定时任务失败: " + com.digitallife.ui.UiKit.safeMsg(e);
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
}