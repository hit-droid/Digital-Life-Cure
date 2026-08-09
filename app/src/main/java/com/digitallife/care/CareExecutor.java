package com.digitallife.care;

import android.content.Context;

import com.digitallife.brain.BehaviorStyle;
import com.digitallife.render.Live2DNative;
import com.digitallife.ui.PetOverlayView;

import org.json.JSONObject;

/**
 * CareExecutor：护理执行层（AI-2 的执行端）。
 *
 * 双层 AI 协作中的「执行层」：
 *  - 大脑（AICore / CareAI）负责对话、情绪、环境分析，产出行为意图
 *  - 本类负责把行为意图落成真实动作：选择动作组、播放动作、设置表情、
 *    管理 Live2D 模型（安装/分析/修复）、实时播放、健康检查
 *
 * 全应用单例：PetService 在桌宠启动时注入 Render 桥，AICore 与 CareAI 共享。
 */
public class CareExecutor {

    /** 渲染桥：把执行结果落到 Live2D 渲染 */
    public interface Render {
        void setParam(String paramId, float value);
        void setExpression(String name);
        void playMotion(String group, int index, int priority);
        boolean isMotionPlaying();
        void bubble(String text, float seconds);
        void error(String msg);
        void thinking(boolean thinking);
        void speak(String text);
        void move(float x, float y);
    }

    private static CareExecutor instance;

    /** 获取单例；首次调用需传入有效 Context */
    public static synchronized CareExecutor getInstance(Context ctx) {
        if (instance == null) {
            instance = new CareExecutor(ctx.getApplicationContext());
        }
        return instance;
    }

    private final Context ctx;
    private final CareTools tools;
    private Render render;

    public CareExecutor(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.tools = new CareTools(ctx);
    }

    /** 桌宠启动后注入渲染桥；无渲染环境（未启动）时各方法自动降级为只改状态 */
    public void setRender(Render r) {
        this.render = r;
    }

    public Render getRender() {
        return render;
    }

    public CareTools getTools() {
        return tools;
    }

    // ============ 行为意图执行（大脑 → 动作） ============

    /**
     * 执行行为包：把大脑分析出的情绪/精力落地为表情 + 动作。
     * 高精力 → 活泼动作组；低精力 → 安静动作组；情绪优先。
     */
    public void executeBehavior(BehaviorStyle style) {
        if (style == null) return;
        // 表情
        if (style.expression != null && !style.expression.isEmpty()) {
            setExpression(style.expression);
        }
        // 根据情绪/精力选择动作
        String action = mapStyleToAction(style);
        if (action != null) {
            playAction(action);
        }
    }

    private String mapStyleToAction(BehaviorStyle s) {
        String posture = s.posture == null ? "" : s.posture;
        if (posture.contains("tired")) return "sleepy";
        if (posture.contains("shy")) return "tilt_head";
        if (posture.contains("alert")) return "point";
        if (posture.contains("curious")) return "wave";
        // 情绪倾向（表情 ID 高/低能量映射）
        if (s.energy > 0.75f) return "excited";
        if (s.energy < 0.35f) return "sleepy";
        if (s.sociability > 0.7f) return "wave";
        return null; // 平静：保持待机微动，不额外播动作
    }

    /** 播放语义动作（wave/clap/bounce/happy/sad/surprised/shy/sleepy/stretch/point…） */
    public void playAction(String action) {
        if (render == null || action == null) return;
        int[] m = PetOverlayView.resolveMotion(action);
        render.playMotion(PetOverlayView.MOTION_GROUPS[m[0]], m[1], 3);
    }

    public void setExpression(String name) {
        if (render != null && name != null) {
            render.setExpression(name);
        }
    }

    public void setParam(String paramId, float value) {
        if (render != null) render.setParam(paramId, value);
    }

    public boolean isMotionPlaying() {
        return render != null && render.isMotionPlaying();
    }

    // ============ 模型管理（委托 CareTools） ============

    public String listModels() {
        try {
            return tools.execute("list_models", new JSONObject());
        } catch (Exception e) {
            return "列出模型失败: " + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    public String analyzeModel(String modelName) {
        try {
            JSONObject args = new JSONObject();
            args.put("modelName", modelName);
            return tools.execute("analyze_model", args);
        } catch (Exception e) {
            return "分析模型失败: " + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    public String installModel(String zipPath) {
        try {
            JSONObject args = new JSONObject();
            args.put("zipPath", zipPath);
            return tools.execute("install_model_from_zip", args);
        } catch (Exception e) {
            return "安装模型失败: " + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    public String repairModel(String modelName) {
        try {
            JSONObject args = new JSONObject();
            args.put("modelName", modelName);
            return tools.execute("repair_model", args);
        } catch (Exception e) {
            return "修复模型失败: " + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    public String listMotions(String modelName) {
        try {
            JSONObject args = new JSONObject();
            args.put("modelName", modelName);
            return tools.execute("list_motions", args);
        } catch (Exception e) {
            return "列出动作失败: " + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    public String generateMotion(String modelName, String motionName, double duration, String curves) {
        try {
            JSONObject args = new JSONObject();
            args.put("modelName", modelName);
            args.put("motionName", motionName);
            args.put("duration", duration);
            args.put("curves", curves);
            return tools.execute("generate_motion", args);
        } catch (Exception e) {
            return "创建动作失败: " + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    public String editMotion(String modelName, String motionName, String edits) {
        try {
            JSONObject args = new JSONObject();
            args.put("modelName", modelName);
            args.put("motionName", motionName);
            args.put("edits", edits);
            return tools.execute("edit_motion", args);
        } catch (Exception e) {
            return "修改动作失败: " + com.digitallife.ui.UiKit.safeMsg(e);
        }
    }

    // ============ 健康检查（主动反馈） ============

    /** 检查当前桌宠正在使用的模型：列出全部，逐个做完整性分析，返回汇总报告 */
    public String healthCheck() {
        int count = 0;
        try {
            count = Live2DNative.nativeGetModelCount();
        } catch (Throwable ignored) {
        }
        if (count <= 0) {
            return "当前没有可用的 Live2D 模型，请先安装或切换模型。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("🩺 桌宠模型健康检查（共 ").append(count).append(" 个模型）:\n");
        for (int i = 0; i < count; i++) {
            String dirName = null;
            try {
                dirName = Live2DNative.nativeGetModelDirName(i);
            } catch (Throwable ignored) {
            }
            if (dirName == null || dirName.isEmpty()) continue;
            sb.append("\n【").append(dirName).append("】\n");
            String report = analyzeModel(dirName);
            // 精简：只保留资源统计 + 结论
            if (report != null) {
                int statIdx = report.indexOf("📦 资源统计");
                if (statIdx >= 0) {
                    sb.append(report, statIdx, report.length()).append("\n");
                } else {
                    sb.append(report).append("\n");
                }
            }
        }
        return sb.toString();
    }
}
