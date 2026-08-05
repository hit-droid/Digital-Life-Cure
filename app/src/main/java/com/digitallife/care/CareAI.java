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
     * 上传模型 zip 后主动分析：先解压检查，若发现模型文件则进一步分析完整性和动作列表，
     * 把结果注入对话历史，并让 AI 立即主动总结反馈（无需用户追问）。
     */
    public void analyzeUploadedZip(String fileName, String filePath) {
        if (!running.compareAndSet(false, true)) {
            history.add(new LLMClient.ChatMessage("user", "我上传了模型包 " + fileName + "，请分析。"));
            doConverse();
            return;
        }
        pool.execute(() -> {
            try {
                JSONObject inspectArgs = new JSONObject();
                inspectArgs.put("zipPath", filePath);
                String inspect = executor.getTools().execute("inspect_zip", inspectArgs);
                if (inspect.length() > 2000) {
                    inspect = inspect.substring(0, 2000) + "\n...（结果已截断）";
                }
                history.add(new LLMClient.ChatMessage("user",
                        "我上传了模型包 " + fileName + "，请主动检查并给出完整分析报告。"));
                history.add(new LLMClient.ChatMessage("assistant",
                        "好的，我先检查压缩包内容。\n\n" + inspect));
                // 若识别出模型文件，进一步深入分析，让 AI 主动报告
                String modelName = findModelName(inspect);
                if (modelName != null && !modelName.isEmpty()) {
                    try {
                        JSONObject anaArgs = new JSONObject();
                        anaArgs.put("modelName", modelName);
                        String analysis = executor.getTools().execute("analyze_model", anaArgs);
                        if (analysis.length() > 2000) {
                            analysis = analysis.substring(0, 2000) + "\n...（结果已截断）";
                        }
                        history.add(new LLMClient.ChatMessage("user",
                                "继续分析模型 " + modelName + " 的完整性："));
                        history.add(new LLMClient.ChatMessage("assistant", analysis));
                    } catch (Exception e) {
                        history.add(new LLMClient.ChatMessage("assistant",
                                "深入分析失败: " + e.getMessage()));
                    }
                }
                doConverse();
            } catch (Exception e) {
                postError("解压分析失败: " + e.getMessage());
                history.add(new LLMClient.ChatMessage("assistant", "解压分析失败: " + e.getMessage()));
                running.set(false);
            }
        });
    }

    /** 从 inspect_zip 结果中粗提取模型名（模型文件 .model3.json 所在顶层目录） */
    private String findModelName(String inspectResult) {
        for (String line : inspectResult.split("\n")) {
            String t = line.trim();
            if (t.contains(".model3.json") || t.contains(".model.json")) {
                t = t.replaceAll("^[\\u2713\\u2716!\\s✅⚠]+", "");
                int slash = t.indexOf('/');
                if (slash > 0) return t.substring(0, slash);
            }
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
                    executor.installModel(args.optString("zipPath", ""));
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