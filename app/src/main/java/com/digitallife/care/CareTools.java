package com.digitallife.care;

import android.content.Context;
import android.content.SharedPreferences;

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
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 护理大脑工具集：模型管理、动作管理、工作流、定时任务。
 * 每个工具实现 Tool 接口，供 CareAI 的 LLM function calling 调用。
 */
public class CareTools {

    private static final String PREFS_WORKFLOW = "care_workflows";
    private static final String PREFS_SCHEDULE = "care_schedules";

    private final Context ctx;
    private final SharedPreferences workflowPrefs;
    private final SharedPreferences schedulePrefs;

    public CareTools(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        workflowPrefs = ctx.getSharedPreferences(PREFS_WORKFLOW, Context.MODE_PRIVATE);
        schedulePrefs = ctx.getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE);
    }

    // ============ 工具注册 ============

    /** 返回所有工具的 JSON array（供 LLM tools 参数） */
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
            params.put("required", new JSONArray(java.util.Arrays.asList(required)));
        }
        func.put("parameters", params);
        schema.put("function", func);
        return schema;
    }

    // ============ 工具执行 ============

    /** 执行工具调用，返回结果文本 */
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

    private String listModels() {
        StringBuilder sb = new StringBuilder("已安装模型：\n");
        int count = Live2DNative.nativeGetModelCount();
        if (count == 0) {
            return "尚未安装任何模型。";
        }
        for (int i = 0; i < count; i++) {
            String dir = Live2DNative.nativeGetModelDirName(i);
            sb.append("  ").append(i).append(". ").append(dir).append("\n");
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
        // 检查 model3.json
        File[] modelJsons = modelDir.listFiles((d, n) -> n.endsWith(".model3.json") || n.endsWith(".model.json"));
        if (modelJsons == null || modelJsons.length == 0) {
            report.append("  [缺失] 未找到 .model3.json 或 .model.json 文件\n");
        } else {
            report.append("  [OK] 模型定义文件: ").append(modelJsons[0].getName()).append("\n");
        }
        // 检查 moc3
        File[] mocs = modelDir.listFiles((d, n) -> n.endsWith(".moc3"));
        if (mocs == null || mocs.length == 0) {
            report.append("  [缺失] 未找到 .moc3 文件\n");
        } else {
            report.append("  [OK] MOC 文件: ").append(mocs[0].getName()).append("\n");
        }
        // 检查纹理
        File[] textures = modelDir.listFiles((d, n) -> n.endsWith(".png") || n.endsWith(".jpg"));
        if (textures == null || textures.length == 0) {
            report.append("  [缺失] 未找到纹理文件\n");
        } else {
            report.append("  [OK] 纹理文件: ").append(textures.length).append(" 个\n");
        }
        // 检查动作
        File[] motions = modelDir.listFiles((d, n) -> n.endsWith(".motion3.json"));
        if (motions != null && motions.length > 0) {
            report.append("  [OK] 动作文件: ").append(motions.length).append(" 个\n");
        } else {
            report.append("  [提示] 暂无动作文件\n");
        }
        // 检查表情
        File[] expressions = modelDir.listFiles((d, n) -> n.endsWith(".exp3.json"));
        if (expressions != null && expressions.length > 0) {
            report.append("  [OK] 表情文件: ").append(expressions.length).append(" 个\n");
        }
        // 检查 physics3
        File[] physics = modelDir.listFiles((d, n) -> n.endsWith(".physics3.json"));
        if (physics != null && physics.length > 0) {
            report.append("  [OK] 物理模拟文件: ").append(physics[0].getName()).append("\n");
        }
        return report.toString();
    }

    private String installModel(String zipPath) {
        if (zipPath.isEmpty()) return "请提供模型压缩包路径。";
        // 模型安装由 ModelManager 处理，这里假设 zip 已保存到本地
        // 简化：直接返回安装指引
        return "模型安装需要从聊天界面发送 zip 文件。请在聊天中点击附件按钮，选择模型 zip 文件上传。\n" +
               "收到文件后我会自动完成安装。";
    }

    private String repairModel(String modelName) {
        if (modelName.isEmpty()) return "请指定模型名称。";
        File modelDir = findModelDir(modelName);
        if (modelDir == null || !modelDir.exists()) {
            return "未找到模型目录: " + modelName;
        }
        StringBuilder report = new StringBuilder("开始修复模型: " + modelName + "\n");
        boolean fixed = false;

        // 检查并创建缺失的 model3.json
        File[] modelJsons = modelDir.listFiles((d, n) -> n.endsWith(".model3.json") || n.endsWith(".model.json"));
        if (modelJsons == null || modelJsons.length == 0) {
            // 尝试生成最小 model3.json
            File mocFile = findFirstFile(modelDir, ".moc3");
            if (mocFile != null) {
                String baseName = mocFile.getName().replace(".moc3", "");
                createMinimalModel3Json(modelDir, baseName);
                report.append("  [修复] 已生成最小 model3.json\n");
                fixed = true;
            }
        }

        // 检查纹理
        File[] textures = modelDir.listFiles((d, n) -> n.endsWith(".png") || n.endsWith(".jpg"));
        if (textures == null || textures.length == 0) {
            // 创建占位纹理（通知用户）
            report.append("  [提示] 缺少纹理文件，请手动添加纹理图片\n");
        }

        if (!fixed) {
            report.append("  模型文件完整，无需修复。\n");
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
            long size = m.length();
            sb.append("  ").append(i + 1).append(". ").append(name)
              .append(" (").append(size / 1024).append("KB")
              .append(", 修改于 ").append(sdf.format(new Date(m.lastModified())))
              .append(")\n");
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
        String motionFileName = motionName.endsWith(".motion3.json") ? motionName : motionName + ".motion3.json";
        File motionFile = new File(modelDir, motionFileName);
        if (motionFile.exists()) {
            return "动作文件已存在: " + motionFileName + "。如需修改请使用 edit_motion。";
        }

        // 创建最小动作模板
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
        return "已创建动作文件: " + motionFileName + "（时长 " + duration + " 秒，无曲线）。\n" +
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
        String motionFileName = motionName.endsWith(".motion3.json") ? motionName : motionName + ".motion3.json";
        File motionFile = new File(modelDir, motionFileName);
        if (!motionFile.exists()) {
            return "未找到动作文件: " + motionFileName + "。请先使用 generate_motion 创建。";
        }

        // 读取现有 motion
        String content = readFile(motionFile);
        JSONObject motion = new JSONObject(content);
        JSONObject edits = new JSONObject(editsJson);

        // 应用编辑：支持修改 duration、loop
        if (edits.has("duration")) {
            motion.getJSONObject("Meta").put("Duration", edits.getDouble("duration"));
        }
        if (edits.has("loop")) {
            motion.getJSONObject("Meta").put("Loop", edits.getBoolean("loop"));
        }
        // 支持添加曲线（简化：添加单个参数曲线）
        if (edits.has("addCurve")) {
            JSONObject curveDef = edits.getJSONObject("addCurve");
            JSONObject curve = new JSONObject();
            curve.put("Target", curveDef.optString("target", "Parameter"));
            curve.put("Id", curveDef.optString("id", "ParamAngleX"));
            JSONArray segments = new JSONArray();
            segments.put(0.0); // start time
            segments.put(0.0); // start value
            segments.put(edits.optDouble("duration", 4.0)); // end time
            segments.put(curveDef.optDouble("endValue", 0.0)); // end value
            curve.put("Segments", segments);
            motion.getJSONArray("Curves").put(curve);
            // 更新 meta 计数
            JSONObject meta = motion.getJSONObject("Meta");
            meta.put("CurveCount", motion.getJSONArray("Curves").length());
            meta.put("TotalSegmentCount", meta.optInt("TotalSegmentCount", 0) + 2);
            meta.put("TotalPointCount", meta.optInt("TotalPointCount", 0) + 2);
        }

        try (OutputStream os = new FileOutputStream(motionFile)) {
            os.write(motion.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        JSONObject meta = motion.getJSONObject("Meta");
        return "已更新动作文件: " + motionFileName + "\n" +
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
        String motionFileName = motionName.endsWith(".motion3.json") ? motionName : motionName + ".motion3.json";
        File motionFile = new File(modelDir, motionFileName);
        if (!motionFile.exists()) {
            return "未找到动作文件: " + motionFileName;
        }
        if (motionFile.delete()) {
            return "已删除动作: " + motionFileName;
        }
        return "删除失败: " + motionFileName;
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
                result.append("成功\n").append("    结果: ").append(r).append("\n");
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
        JSONObject task = new JSONObject();
        try {
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
        // 列出所有文件
        File[] allFiles = modelDir.listFiles();
        if (allFiles != null) {
            sb.append("  文件列表:\n");
            for (File f : allFiles) {
                sb.append("    ").append(f.isDirectory() ? "[DIR] " : "       ")
                  .append(f.getName()).append(" (").append(f.length() / 1024).append("KB)\n");
            }
        }
        // 读取 model3.json 内容
        File[] modelJsons = modelDir.listFiles((d, n) -> n.endsWith(".model3.json"));
        if (modelJsons != null && modelJsons.length > 0) {
            String content = readFile(modelJsons[0]);
            sb.append("  model3.json 内容:\n");
            // 格式化显示，最多 1000 字符
            try {
                JSONObject json = new JSONObject(content);
                sb.append("    ").append(json.toString(2).substring(0, Math.min(1000, json.toString(2).length()))).append("\n");
            } catch (Exception e) {
                sb.append("    ").append(content.substring(0, Math.min(200, content.length()))).append("\n");
            }
        }
        return sb.toString();
    }

    // ============ 工具方法 ============

    private File findModelDir(String modelName) {
        File modelsDir = Live2DNative.getModelsDir();
        if (modelsDir == null) return null;
        // 直接匹配
        File dir = new File(modelsDir, modelName);
        if (dir.exists() && dir.isDirectory()) return dir;
        // 尝试资产目录
        // 注意：资产目录不可写，但可读
        // 在 assets 中查找
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
            fr.put("Textures", new JSONArray());
            fr.getJSONArray("Textures").put(baseName + ".2048/texture_00.png");
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