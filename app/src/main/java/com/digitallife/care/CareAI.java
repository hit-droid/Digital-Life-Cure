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

    public interface CareListener {
        void onDelta(String text);
        void onToolCall(String toolName, JSONObject args);
        void onDone(String fullText);
        void onError(String error);
    }

    private static final String SYSTEM_PROMPT =
            "你是数字生命的护理大脑，负责管理桌面上的 Live2D 虚拟角色。\n\n" +
            "你的能力：\n" +
            "1. 模型管理：列出、检查、安装、修复 Live2D 模型\n" +
            "2. 动作管理：列出、创建、修改、删除模型动作（.motion3.json）\n" +
            "3. 工作流：创建、列出、执行自动化工作流\n" +
            "4. 定时任务：添加、列出、删除定时任务\n\n" +
            "使用规则：\n" +
            "- 当用户发送模型 zip 文件时，先调用 install_model 安装，再检查完整性\n" +
            "- 模型检查时详细列出缺失文件，并给出修复建议\n" +
            "- 动作创建时，询问用户需要的动作参数（时长、循环、参数曲线等）\n" +
            "- 工作流可以包含多个步骤，每个步骤调用一个工具\n" +
            "- 定时任务使用 cron 表达式定义触发时间\n\n" +
            "请用中文回复，每次回答简洁准确。";

    private final Context ctx;
    private final CareTools tools;
    private final List<LLMClient.ChatMessage> history;
    private final Handler handler;
    private final ExecutorService pool;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private LLMClient llm;
    private CareListener listener;
    private volatile boolean cancelled;

    public CareAI(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.tools = new CareTools(ctx);
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
        llm.setTools(tools.getToolSchemas());
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
            public void onToolCall(String name, JSONObject args) {
                postToolCall(name, args);
                try {
                    String result = tools.execute(name, args);
                    if (result.length() > 2000) {
                        result = result.substring(0, 2000) + "\n...（结果已截断）";
                    }
                    history.add(new LLMClient.ChatMessage("tool", result));
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

    // ============ 回调 ============

    private void postDelta(final String text) {
        handler.post(() -> {
            if (listener != null) listener.onDelta(text);
        });
    }

    private void postToolCall(final String name, final JSONObject args) {
        handler.post(() -> {
            if (listener != null) listener.onToolCall(name, args);
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