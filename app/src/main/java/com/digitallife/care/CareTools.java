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
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/**
 * 护理大脑工具集：模型管理、动作管理、工作流、定时任务。
 * 纯文件系统操作，不依赖 native 引擎是否启动。
 */
public class CareTools {

    private static final String PREFS_WORKFLOW = "care_workflows";
    private static final String PREFS_SCHEDULE = "care_schedules";

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
            arr.put(makeSchema("list_models", "列出所有已安装的 Live2D 模型"));
            arr.put(makeSchema("check_model", "检查模型完整性，报告缺失文件", new String[]{"modelName"}));
            arr.put(makeSchema("install_model", "安装模型压缩包到模型目录", new String[]{"zipPath"}));
            arr.put(makeSchema("repair_model", "修复模型问题（如缺失纹理创建占位图）", new String[]{"modelName"}));
            arr.put(makeSchema("list_motions", "列出模型的所有动作文件", new String[]{"modelName"}));
            arr.put(makeSchema("generate_motion", "为模型生成新动作", new String[]{"modelName", "motionName", "duration"}));
            arr.put(makeSchema("edit_motion", "修改已有动作的参数", new String[]{"modelName", "motionName", "edits"}));
            arr.put(makeSchema("delete_motion", "删除模型的一个动作", new String[]{"modelName", "motionName"}));
            arr.put(makeSchema("create_workflow", "创建新工作流", new String[]{"name", "steps"}));
            arr.put(makeSchema("list_workflows", "列出所有已保存的工作流"));
            arr.put(makeSchema("delete_workflow", "删除一个工作流", new String[]{"name"}));
            arr.put(makeSchema("run_workflow", "执行一个工作流", new String[]{"name"}));
            arr.put(makeSchema("add_scheduled_task", "添加定时任务", new String[]{"name", "cronExpr", "workflowName"}));
            arr.put(makeSchema("list_scheduled_tasks", "列出所有定时任务"));
            arr.put(makeSchema("remove_scheduled_task", "删除一个定时任务", new String[]{"name"}));
            arr.put(makeSchema("get_model_info", "获取模型详细信息", new String[]{"modelName"}));
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
            case "list_models": return listModels();
            case "check_model": return checkModel(args.optString("modelName", ""));
            case "install_model": return installModel(args.optString("zipPath", ""));
            case "repair_model": return repairModel(args.optString("modelName", ""));
            case "list_motions": return listMotions(args.optString("modelName", ""));
            case "generate_motion": return generateMotion(
                    args.optString("modelName", ""),
                    args.optString("motionName", ""),
                    args.optDouble("duration", 4.0));
            case "edit_motion": return editMotion(
                    args.optString("modelName", ""),
                    args.optString("motionName", ""),
                    args.optString("edits", "{}"));
            case "delete_motion": return deleteMotion(
                    args.optString("modelName", ""),
                    args.optString("motionName", ""));
            case "create_workflow": return createWorkflow(
                    args.optString("name", ""),
                    args.optString("steps", "[]"));
            case "list_workflows": return listWorkflows();
            case "delete_workflow": return deleteWorkflow(args.optString("name", ""));
            case "run_workflow": return runWorkflow(args.optString("name", ""));
            case "add_scheduled_task": return addScheduledTask(
                    args.optString("name", ""),
                    args.optString("cronExpr", ""),
                    args.optString("workflowName", ""));
            case "list_scheduled_tasks": return listScheduledTasks();
            case "remove_scheduled_task": return removeScheduledTask(args.optString("name", ""));
            case "get_model_info": return getModelInfo(args.optString("modelName", ""));
            default: return "未知工具: " + toolName;
        }
    }

    // ============ 模型管理 ============

    /** 获取模型根目录，确保已初始化 */
    private File getModelsDirSafe() {
        if (Live2DNative.getModelsDir() == null) {
            Live2DNative.init(ctx);
        }
        return Live2DNative.getModelsDir();
    }

    /** 扫描模型目录（文件系统），不依赖 native 引擎 */
    private String listModels() {
        File modelsDir = getModelsDirSafe();
        if (modelsDir == null) return "模型目录未初始化。";

        File[] dirs = modelsDir.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) {
            // 也检查 assets 中有无模型
            return "尚未安装任何模型。\n提示：可通过护理大脑聊天界面" +
                   "上传模型 zip 文件，或先启动桌宠确保模型已注册。";
        }
        StringBuilder sb = new StringBuilder("已安装模型：\n");
        for (int i = 0; i < dirs.length; i++) {
            sb.append("  ").append(i + 1).append(". ").append(dirs[i].getName()).append("\n");
        }
        return sb.toString();
    }

    private String checkModel(String modelName) {
        if (modelName.isEmpty()) return "请指定模型名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到模型目录: " + modelName;
        }
        StringBuilder report = new StringBuilder("模型检查报告: " + modelName + "\n");
        File[] modelJsons = modelDir.listFiles((d, n) -> n.endsWith(".model3.json") || n.endsWith(".model.json"));
        if (modelJsons == null || modelJsons.length == 0) {
            report.append("  [缺失] 未找到 .model3.json 或 .model.json\n");
        } else {
            report.append("  [OK] 模型定义: ").append(modelJsons[0].getName()).append("\n");
        }
        File[] mocs = modelDir.listFiles((d, n) -> n.endsWith(".moc3"));
        if (mocs == null || mocs.length == 0) {
            report.append("  [缺失] 未找到 .moc3 文件\n");
        } else {
            report.append("  [OK] MOC: ").append(mocs[0].getName()).append("\n");
        }
        File[] textures = modelDir.listFiles((d, n) -> n.endsWith(".png") || n.endsWith(".jpg"));
        report.append("  ").append(textures != null && textures.length > 0 ? "[OK]" : "[缺失]")
              .append(" 纹理: ").append(textures != null ? textures.length : 0).append(" 个\n");
        File[] motions = modelDir.listFiles((d, n) -> n.endsWith(".motion3.json"));
        report.append("  ").append(motions != null && motions.length > 0 ? "[OK]" : "[提示]")
              .append(" 动作: ").append(motions != null ? motions.length : 0).append(" 个\n");
        File[] expressions = modelDir.listFiles((d, n) -> n.endsWith(".exp3.json"));
        report.append("  ").append(expressions != null && expressions.length > 0 ? "[OK]" : "[提示]")
              .append(" 表情: ").append(expressions != null ? expressions.length : 0).append(" 个\n");
        File[] physics = modelDir.listFiles((d, n) -> n.endsWith(".physics3.json"));
        report.append("  ").append(physics != null && physics.length > 0 ? "[OK]" : "[提示]")
              .append(" 物理: ").append(physics != null && physics.length > 0 ? physics[0].getName() : "无").append("\n");
        return report.toString();
    }

    private String installModel(String zipPath) {
        if (zipPath.isEmpty()) return "请提供模型压缩包路径。";
        File zipFile = new File(zipPath);
        if (!zipFile.exists()) return "文件不存在: " + zipPath;
        // 委托给 ModelManager 安装
        android.net.Uri uri = android.net.Uri.fromFile(zipFile);
        com.digitallife.model.ModelManager.ImportResult result =
                com.digitallife.model.ModelManager.importFromUri(ctx, uri);
        if (result.ok) {
            return "模型安装成功: " + result.modelDir + "\n" + result.message;
        }
        return "安装失败: " + result.message;
    }

    private String repairModel(String modelName) {
        if (modelName.isEmpty()) return "请指定模型名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到模型目录: " + modelName;
        }
        StringBuilder report = new StringBuilder("开始修复模型: " + modelName + "\n");
        File[] modelJsons = modelDir.listFiles((d, n) -> n.endsWith(".model3.json") || n.endsWith(".model.json"));
        if (modelJsons == null || modelJsons.length == 0) {
            File mocFile = findFirstFile(modelDir, ".moc3");
            if (mocFile != null) {
                String baseName = mocFile.getName().replace(".moc3", "");
                createMinimalModel3Json(modelDir, baseName);
                report.append("  [修复] 已生成最小 model3.json\n");
            } else {
                report.append("  [失败] 未找到 .moc3 文件，无法生成模型定义\n");
            }
        }
        File[] textures = modelDir.listFiles((d, n) -> n.endsWith(".png") || n.endsWith(".jpg"));
        if (textures == null || textures.length == 0) {
            report.append("  [提示] 缺少纹理文件，请手动添加纹理图片\n");
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
            return "模型「" + modelName + "」暂无动作文件。";
        }
        StringBuilder sb = new StringBuilder("模型「" + modelName + "」的动作列表：\n");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (int i = 0; i < motions.length; i++) {
            File m = motions[i];
            String name = m.getName().replace(".motion3.json", "");
            sb.append("  ").append(i + 1).append(". ").append(name)
              .append(" (").append(m.length() / 1024).append("KB")
              .append(", ").append(sdf.format(new Date(m.lastModified()))).append(")\n");
        }
        return sb.toString();
    }

    private String generateMotion(String modelName, String motionName, double duration) throws Exception {
        if (modelName.isEmpty() || motionName.isEmpty()) {
            return "请指定模型名称和动作名称。";
        }
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到模型目录: " + modelName;
        }
        String fileName = motionName.endsWith(".motion3.json") ? motionName : motionName + ".motion3.json";
        File motionFile = new File(modelDir, fileName);
        if (motionFile.exists()) {
            return "动作文件已存在: " + fileName + "，如需修改请使用 edit_motion。";
        }
        JSONObject motion = new JSONObject();
        motion.put("Version", 3);
        JSONObject meta = new JSONObject();
        meta.put("Duration", duration);
        meta.put("Fps", 30.0);
        meta.put("Loop", false);
        meta.put("AreBeziersRestricted", true);
        meta.put("CurveCount", 0);
        meta.put("TotalSegmentCount", 0);
        meta.put("TotalPointCount", 0);
        meta.put("UserDataCount", 0);
        meta.put("TotalUserDataSize", 0);
        motion.put("Meta", meta);
        motion.put("Curves", new JSONArray());
        motion.put("UserData", new JSONArray());
        try (OutputStream os = new FileOutputStream(motionFile)) {
            os.write(motion.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        return "已创建动作文件: " + fileName + "（时长 " + duration + " 秒）\n" +
               "可使用 edit_motion 添加参数曲线。";
    }

    private String editMotion(String modelName, String motionName, String editsJson) throws Exception {
        if (modelName.isEmpty() || motionName.isEmpty()) {
            return "请指定模型名称和动作名称。";
        }
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到模型目录: " + modelName;
        }
        String fileName = motionName.endsWith(".motion3.json") ? motionName : motionName + ".motion3.json";
        File motionFile = new File(modelDir, fileName);
        if (!motionFile.exists()) {
            return "未找到动作文件: " + fileName + "。请先使用 generate_motion 创建。";
        }
        String content = readFile(motionFile);
        JSONObject motion = new JSONObject(content);
        JSONObject edits = new JSONObject(editsJson);
        if (edits.has("duration")) {
            motion.getJSONObject("Meta").put("Duration", edits.getDouble("duration"));
        }
        if (edits.has("loop")) {
            motion.getJSONObject("Meta").put("Loop", edits.getBoolean("loop"));
        }
        if (edits.has("addCurve")) {
            JSONObject curveDef = edits.getJSONObject("addCurve");
            JSONObject curve = new JSONObject();
            curve.put("Target", curveDef.optString("target", "Parameter"));
            curve.put("Id", curveDef.optString("id", "ParamAngleX"));
            JSONArray segments = new JSONArray();
            segments.put(0.0);
            segments.put(0.0);
            segments.put(edits.optDouble("duration", 4.0));
            segments.put(curveDef.optDouble("endValue", 0.0));
            curve.put("Segments", segments);
            motion.getJSONArray("Curves").put(curve);
            JSONObject meta = motion.getJSONObject("Meta");
            meta.put("CurveCount", motion.getJSONArray("Curves").length());
            meta.put("TotalSegmentCount", meta.optInt("TotalSegmentCount", 0) + 2);
            meta.put("TotalPointCount", meta.optInt("TotalPointCount", 0) + 2);
        }
        try (OutputStream os = new FileOutputStream(motionFile)) {
            os.write(motion.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        JSONObject meta = motion.getJSONObject("Meta");
        return "已更新动作文件: " + fileName + "\n" +
               "  时长: " + meta.optDouble("Duration", 0) + "s\n" +
               "  循环: " + meta.optBoolean("Loop", false) + "\n" +
               "  曲线数: " + meta.optInt("CurveCount", 0);
    }

    private String deleteMotion(String modelName, String motionName) {
        if (modelName.isEmpty() || motionName.isEmpty()) {
            return "请指定模型名称和动作名称。";
        }
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到模型目录: " + modelName;
        }
        String fileName = motionName.endsWith(".motion3.json") ? motionName : motionName + ".motion3.json";
        File motionFile = new File(modelDir, fileName);
        if (!motionFile.exists()) {
            return "未找到动作文件: " + fileName;
        }
        if (motionFile.delete()) {
            return "已删除动作: " + fileName;
        }
        return "删除失败: " + fileName;
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
        return "已创建工作流「" + name + "」(" + steps.length() + " 步)：\n" + steps.toString(2);
    }

    private String listWorkflows() {
        java.util.Map<String, ?> all = workflowPrefs.getAll();
        if (all.isEmpty()) return "暂无工作流。";
        StringBuilder sb = new StringBuilder("已保存的工作流：\n");
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
        return "已删除工作流: " + name;
    }

    private String runWorkflow(String name) throws Exception {
        if (name.isEmpty()) return "请指定工作流名称。";
        String raw = workflowPrefs.getString(name, null);
        if (raw == null) return "未找到工作流: " + name;
        JSONObject wf = new JSONObject(raw);
        JSONArray steps = wf.optJSONArray("steps");
        if (steps == null || steps.length() == 0) return "工作流「" + name + "」没有步骤。";
        StringBuilder result = new StringBuilder("执行工作流「" + name + "」：\n");
        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            String tool = step.optString("tool", "");
            JSONObject args = step.optJSONObject("args");
            if (args == null) args = new JSONObject();
            result.append("  步骤 ").append(i + 1).append(": ").append(tool).append(" → ");
            try {
                String r = execute(tool, args);
                result.append("成功\n    结果: ").append(r).append("\n");
            } catch (Exception e) {
                result.append("失败: ").append(e.getMessage()).append("\n");
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
            return "已添加定时任务「" + name + "」\n  cron: " + cronExpr + "\n  执行工作流: " + workflowName;
        } catch (Exception e) {
            return "添加定时任务失败: " + e.getMessage();
        }
    }

    private String listScheduledTasks() {
        java.util.Map<String, ?> all = schedulePrefs.getAll();
        if (all.isEmpty()) return "暂无定时任务。";
        StringBuilder sb = new StringBuilder("定时任务列表：\n");
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
        return "已删除定时任务: " + name;
    }

    // ============ 模型信息 ============

    private String getModelInfo(String modelName) throws Exception {
        if (modelName.isEmpty()) return "请指定模型名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到模型目录: " + modelName;
        }
        StringBuilder sb = new StringBuilder("模型信息: " + modelName + "\n");
        sb.append("  路径: ").append(modelDir.getAbsolutePath()).append("\n");
        File[] allFiles = modelDir.listFiles();
        if (allFiles != null) {
            sb.append("  文件列表:\n");
            for (File f : allFiles) {
                sb.append("    ").append(f.isDirectory() ? "[DIR] " : "       ")
                  .append(f.getName()).append(" (").append(f.length() / 1024).append("KB)\n");
            }
        }
        File[] modelJsons = modelDir.listFiles((d, n) -> n.endsWith(".model3.json"));
        if (modelJsons != null && modelJsons.length > 0) {
            String content = readFile(modelJsons[0]);
            try {
                JSONObject json = new JSONObject(content);
                String pretty = json.toString(2);
                sb.append("  model3.json 内容:\n    ")
                  .append(pretty.substring(0, Math.min(1000, pretty.length()))).append("\n");
            } catch (Exception e) {
                sb.append("  model3.json 内容:\n    ")
                  .append(content.substring(0, Math.min(200, content.length()))).append("\n");
            }
        }
        return sb.toString();
    }

    // ============ 工具方法 ============

    private File findModelDir(String modelName) {
        File modelsDir = getModelsDirSafe();
        if (modelsDir == null) return null;
        File dir = new File(modelsDir, modelName);
        if (dir.exists() && dir.isDirectory()) return dir;
        // 尝试模糊匹配
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

    private static File findFirstFile(File dir, String suffix) {
        File[] files = dir.listFiles((d, n) -> n.endsWith(suffix));
        if (files != null && files.length > 0) return files[0];
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

    private static String readFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }
}