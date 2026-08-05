package com.digitallife.care;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.digitallife.brain.LLMClient;
import com.digitallife.util.ApiManager;
import com.digitallife.util.ApiProfile;
import com.digitallife.util.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 护理大脑 AI 引擎。
 * 独立于对话 AI 的 LLM 客户端，使用 care scope 的 API Profile。
 * 维护对话历史，支持 function calling 多轮工具调用。
 */
public class CareAI {

    private static CareAI instance;

    /** 获取单例。首次调用需传入有效 Context。 */
    public static synchronized CareAI getInstance(Context ctx) {
        if (instance == null) {
            instance = new CareAI(ctx.getApplicationContext());
        }
        return instance;
    }

    /** 销毁单例（清空历史） */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.clearHistory();
            instance = null;
        }
    }

    public interface CareListener {
        void onDelta(String text);
        void onToolCall(String toolName, JSONObject args, String toolCallId);
        /** 工具执行完成，反馈结果摘要给 UI（避免干等） */
        void onToolResult(String toolName, boolean ok, String result);
        void onDone(String fullText);
        void onError(String error);
    }

    private static final String SYSTEM_PROMPT =
            "你是数字生命的护理大脑，负责管理桌面上的 Live2D 虚拟角色。\n\n" +
            "你的能力：\n" +
            "1. 模型管理：列出、检查、分析、安装、修复 Live2D 模型\n" +
            "2. 动作管理：列出、创建、修改、删除模型动作（.motion3.json），可指定参数曲线\n" +
            "3. 工作流：创建、列出、执行自动化工作流\n" +
            "4. 定时任务：添加、列出、删除定时任务\n\n" +
            "使用规则：\n" +
            "- 当用户上传模型 zip 或请求检查模型时，必须主动调用 analyze_model 进行完整分析，并主动输出检查结论（通过/缺失文件/修复建议），不要等用户追问\n" +
            "- 安装模型前先检查完整性，缺失关键文件要明确指出\n" +
            "- 创建动作时主动询问动作参数（名称、时长、是否循环），或按用户描述直接创建\n" +
            "- 编辑动作时先查看动作详情再修改\n" +
            "- 工作流可以包含多个步骤，每个步骤调用一个工具\n" +
            "- 定时任务使用 cron 表达式定义触发时间\n" +
            "- 工具执行完成后，用中文主动总结执行结果和下一步建议\n\n" +
            "请用中文回复，每次回答简洁准确。";

    private final Context ctx;
    private final CareExecutor executor;
    private final List<LLMClient.ChatMessage> history;
    private final Handler handler;
    private final ExecutorService pool;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private LLMClient llm;
    private CareListener listener;
    private volatile boolean cancelled;

    public CareAI(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.executor = CareExecutor.getInstance(ctx);
        this.history = new ArrayList<>();
        this.handler = new Handler(Looper.getMainLooper());
        this.pool = Executors.newSingleThreadExecutor();
        initLLM();
    }

    private void initLLM() {
        ApiManager apiManager = new ApiManager(ctx);
        ApiProfile profile = apiManager.getCurrent(ApiManager.SCOPE_CARE);
        if (profile != null) {
            llm = new LLMClient(profile.baseUrl, profile.apiKey, profile.model);
        } else {
            Settings settings = new Settings(ctx);
            llm = new LLMClient(settings.getApiBase(), settings.getApiKey(), settings.getModel());
        }
        JSONArray schemas = executor.getTools().getToolSchemas();
        // 追加「实时播放动作」工具：护理大脑可直接让桌宠播放某个动作
        try {
            JSONObject ps = new JSONObject();
            ps.put("type", "function");
            JSONObject pfn = new JSONObject();
            pfn.put("name", "play_motion");
            pfn.put("description", "立即让桌宠播放某个动作（如挥手、拍手、跳舞、惊讶等），用于实时预览/执行");
            JSONObject pparams = new JSONObject();
            pparams.put("type", "object");
            JSONObject pprops = new JSONObject();
            JSONObject act = new JSONObject();
            act.put("type", "string");
            act.put("description", "动作名");
            pprops.put("action", act);
            pparams.put("properties", pprops);
            pparams.put("required", new JSONArray(new String[]{"action"}));
            pfn.put("parameters", pparams);
            ps.put("function", pfn);
            schemas.put(ps);
        } catch (Exception ignored) {
        }
        llm.setTools(schemas);
    }

    public void setListener(CareListener listener) {
        this.listener = listener;
    }

    public void refreshConfig() {
        initLLM();
    }

    /** 发送用户消息 */
    public void sendMessage(String text) {
        history.add(new LLMClient.ChatMessage("user", text));
        if (running.compareAndSet(false, true)) {
            pool.execute(this::converse);
        }
    }
    public void handleFile(String fileName, String filePath) {
        String msg = "我上传了一个文件: " + fileName + "\n路径: " + filePath;
        if (fileName.endsWith(".zip")) {
            msg += "\n这是一个压缩包，请检查并尝试安装其中的模型。";
        }
        sendMessage(msg);
    }

    /**
     * 上传模型 zip 后全自动处理：真正安装到模型目录 → 注册 → 分析完整性 → 动作列表，
     * 每步结果实时反馈 UI，最后让 AI 主动总结（无需用户追问、不再死循环）。
     */
    public void analyzeUploadedZip(String fileName, String filePath) {
        pool.execute(() -> {
            try {
                if (!running.compareAndSet(false, true)) {
                    // 已有对话在跑：把上传信息排队进历史，由现有循环继续
                    history.add(new LLMClient.ChatMessage("user",
                            "我上传了模型包 " + fileName + "，路径 " + filePath + "，请立即安装并分析。"));
                    return;
                }
                // 第 1 步：真正安装（解压到模型目录并注册），不再只解压到临时目录
                String install = executor.getTools().execute("install_model_from_zip", jsonArgs("zipPath", filePath));
                boolean installOk = install.contains("安装成功") || install.contains("✅");
                postToolResult("install_model_from_zip", installOk, install);
                history.add(new LLMClient.ChatMessage("user",
                        "我上传了模型包 " + fileName + "，请检查安装结果并给出完整分析报告。"));
                history.add(new LLMClient.ChatMessage("assistant", install));

                if (!installOk) {
                    history.add(new LLMClient.ChatMessage("assistant",
                            "⚠ 模型安装未成功，请根据上面的提示检查压缩包内容（是否包含 .model3.json 定义、纹理、moc 骨骼等），并告诉我如何解决。"));
                    doConverse();
                    return;
                }

                // 第 2 步：分析已安装的模型完整性
                String modelName = extractInstalledModelName(install);
                if (modelName == null || modelName.isEmpty()) {
                    modelName = firstInstalledModelName();
                }
                if (modelName != null && !modelName.isEmpty()) {
                    String analysis = executor.getTools().execute("analyze_model", jsonArgs("modelName", modelName));
                    postToolResult("analyze_model", !analysis.contains("⚠") && !analysis.contains("❌"), analysis);
                    history.add(new LLMClient.ChatMessage("assistant", analysis));

                    // 第 3 步：列出动作
                    String motions = executor.getTools().execute("list_motions", jsonArgs("modelName", modelName));
                    postToolResult("list_motions", !motions.contains("未找到") && !motions.contains("失败"), motions);
                    history.add(new LLMClient.ChatMessage("assistant", motions));
                }

                // 第 4 步：AI 主动总结
                history.add(new LLMClient.ChatMessage("user",
                        "请总结这次模型安装和分析的最终结果：模型是否已成功安装可用、完整性问题、可用的动作。"));
                doConverse();
            } catch (Exception e) {
                postError("处理失败: " + e.getMessage());
                postToolResult("install_model_from_zip", false, e.getMessage());
                history.add(new LLMClient.ChatMessage("assistant", "处理失败: " + e.getMessage()));
                running.set(false);
            }
        });
    }

    private JSONObject jsonArgs(String k, String v) {
        JSONObject o = new JSONObject();
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 从安装成功结果文本中提取模型名 */
    private String extractInstalledModelName(String install) {
        for (String line : install.split("\n")) {
            String t = line.trim();
            if (t.startsWith("✅ 模型安装成功") && t.contains(":")) {
                return t.substring(t.indexOf(':') + 1).trim();
            }
        }
        return null;
    }

    /** 取第一个已安装模型名（native 模型列表） */
    private String firstInstalledModelName() {
        try {
            int count = com.digitallife.render.Live2DNative.nativeGetModelCount();
            if (count > 0) {
                String name = com.digitallife.render.Live2DNative.nativeGetModelDirName(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 工具执行成功后落地到执行层（AI-2）：
     *  - 模型健康检查类：执行层可据此更新自身状态
     *  - 动作生成类：执行层记录最新动作
     *  - 模型安装/修复类：执行层注册新模型（引擎已启动时生效）
     */
    private void notifyExecutorResult(String toolName, JSONObject args, String result) {
        try {
            switch (toolName) {
                case "install_model_from_zip":
                    // 安装已由工具本身完成（解压+注册）。若桌宠运行中，让 native 重新注册全部模型，
                    // 使新模型立即可切换；未运行时下次启动 PetService 自动注册。
                    if (com.digitallife.service.PetService.getInstance() != null) {
                        try {
                            com.digitallife.model.ModelManager.registerImportedModels(ctx);
                        } catch (Throwable ignored) {
                        }
                    }
                    break;
                case "repair_model":
                    executor.repairModel(args.optString("modelName", ""));
                    break;
                case "generate_motion":
                case "edit_motion":
                    // 动作文件已写入，执行层无需额外处理（下次读取生效）
                    break;
                default:
                    break;
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 对话循环：通过回调链持续调用 LLM，直到 LLM 返回文本内容。
     * 不在循环中等待，而是每次 LLM 完成后由 onDone/onToolCall 触发下一步。
     */
    private void converse() {
        cancelled = false;
        doConverse();
    }

    private void doConverse() {
        if (cancelled) {
            running.set(false);
            return;
        }

        // 构建消息列表
        List<LLMClient.ChatMessage> messages = new ArrayList<>();
        messages.add(new LLMClient.ChatMessage("system", SYSTEM_PROMPT));
        int start = Math.max(0, history.size() - 30);
        for (int i = start; i < history.size(); i++) {
            messages.add(history.get(i));
        }

        llm.chatStream(messages, null, new LLMClient.StreamListener() {
            @Override
            public void onDelta(String text) {
                postDelta(text);
            }

            @Override
            public void onToolCall(String name, JSONObject args, String toolCallId) {
                postToolCall(name, args, toolCallId);
                try {
                    String result;
                    if ("play_motion".equals(name)) {
                        // 实时播放动作：走执行层，桌宠立即响应
                        String action = args.optString("action", "");
                        executor.playAction(action);
                        result = "已在桌宠上播放动作: " + action;
                    } else {
                        result = executor.getTools().execute(name, args);
                        if (result.length() > 2000) {
                            result = result.substring(0, 2000) + "\n...（结果已截断）";
                        }
                        // 工具执行成功后，通知执行层（AI-2）落地：模型/动作变更实时生效
                        notifyExecutorResult(name, args, result);
                    }
                    // 结果实时反馈到 UI，避免用户干等
                    postToolResult(name, isToolResultOk(result), result);
                    // 添加 assistant 消息（含 tool_calls）到历史
                    JSONArray tcs = new JSONArray();
                    JSONObject tc = new JSONObject();
                    tc.put("id", toolCallId);
                    tc.put("type", "function");
                    JSONObject fn = new JSONObject();
                    fn.put("name", name);
                    fn.put("arguments", args.toString());
                    tc.put("function", fn);
                    tcs.put(tc);
                    LLMClient.ChatMessage asstMsg = new LLMClient.ChatMessage("assistant", null);
                    asstMsg.toolCalls = tcs;
                    history.add(asstMsg);
                    // 添加 tool 结果消息
                    LLMClient.ChatMessage toolMsg = new LLMClient.ChatMessage("tool", result);
                    toolMsg.toolCallId = toolCallId;
                    history.add(toolMsg);
                } catch (Exception e) {
                    postToolResult(name, false, "执行失败: " + e.getMessage());
                    history.add(new LLMClient.ChatMessage("tool", "工具执行失败: " + e.getMessage()));
                }
            }

            @Override
            public void onDone(String fullText) {
                if (!fullText.isEmpty()) {
                    history.add(new LLMClient.ChatMessage("assistant", fullText));
                    postDone(fullText);
                }
                // 判断是否继续对话
                boolean shouldContinue = false;
                if (fullText.isEmpty()) {
                    // 空文本：检查是否有工具调用结果需要继续
                    if (!history.isEmpty() && "tool".equals(history.get(history.size() - 1).role)) {
                        shouldContinue = true;
                    }
                } else {
                    // 有文本回复后，检查是否有未处理的新用户消息
                    for (int i = history.size() - 1; i >= 0; i--) {
                        String role = history.get(i).role;
                        if ("assistant".equals(role) && i != history.size() - 1) break;
                        if ("user".equals(role)) { shouldContinue = true; break; }
                    }
                }
                if (shouldContinue) {
                    doConverse();
                } else {
                    running.set(false);
                }
            }

            @Override
            public void onError(String error) {
                postError(error);
                running.set(false);
            }
        });
    }

    public void cancel() {
        cancelled = true;
        if (llm != null) llm.cancel();
    }

    public void clearHistory() {
        history.clear();
    }

    /**
     * 记录一条自动化事件到对话上下文（不触发 LLM）。
     * 让护理大脑在后续对话中知道刚刚自动做过什么。
     */
    public void logAutomationEvent(String text) {
        history.add(new LLMClient.ChatMessage("assistant", "【自动化记录】" + text));
    }

    // ============ 回调 ============

    private void postDelta(final String text) {
        handler.post(() -> {
            if (listener != null) listener.onDelta(text);
        });
    }

    private void postToolCall(final String name, final JSONObject args, final String toolCallId) {
        handler.post(() -> {
            if (listener != null) listener.onToolCall(name, args, toolCallId);
        });
    }

    private void postToolResult(final String name, final boolean ok, final String result) {
        handler.post(() -> {
            if (listener != null) listener.onToolResult(name, ok, result);
        });
    }

    /** 简单判断工具结果文本是否表示成功 */
    private boolean isToolResultOk(String result) {
        if (result == null) return false;
        String r = result.trim();
        if (r.startsWith("⚠") || r.startsWith("❌") || r.startsWith("✗")) return false;
        if (r.contains("失败") || r.contains("未找到") || r.contains("不存在")
                || r.contains("无法") || r.contains("错误")) return false;
        return r.startsWith("✅") || r.startsWith("✔") || r.startsWith("成功")
                || r.startsWith("已在") || r.startsWith("已") || r.startsWith("▶")
                || r.startsWith("解压完成") || r.startsWith("模型");
    }

    private void postDone(final String fullText) {
        handler.post(() -> {
            if (listener != null) listener.onDone(fullText);
        });
    }

    private void postError(final String error) {
        handler.post(() -> {
            if (listener != null) listener.onError(error);
        });
    }
}