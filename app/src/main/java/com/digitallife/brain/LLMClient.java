package com.digitallife.brain;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OpenAI 兼容 LLM 客户端。
 * 支持：普通对话 / 流式输出 / 工具调用 / 结构化 JSON 输出。
 * 可对接 DeepSeek、OpenAI、本地 llama.cpp 等任何 OpenAI 兼容服务。
 */
public class LLMClient {

    public interface StreamListener {
        void onDelta(String text);
        void onToolCall(String name, JSONObject args, String toolCallId);
        void onDone(String fullText);
        void onError(String error);
    }

    public static class ChatMessage {
        public String role;
        public String content;
        /** 工具调用 ID（tool 角色消息需要） */
        public String toolCallId;
        /** 工具调用列表（assistant 角色消息包含工具调用时使用） */
        public JSONArray toolCalls;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        /** 序列化为 API 请求中的 JSON 消息对象 */
        public JSONObject toJson() {
            try {
                JSONObject obj = new JSONObject();
                obj.put("role", role);
                if (content != null) {
                    obj.put("content", content);
                }
                if ("tool".equals(role) && toolCallId != null) {
                    obj.put("tool_call_id", toolCallId);
                }
                if ("assistant".equals(role) && toolCalls != null && toolCalls.length() > 0) {
                    obj.put("tool_calls", toolCalls);
                }
                return obj;
            } catch (Exception e) {
                JSONObject fallback = new JSONObject();
                try { fallback.put("role", role); fallback.put("content", content); } catch (Exception ignored) {}
                return fallback;
            }
        }
    }

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private String baseUrl, apiKey, model;
    private JSONArray tools;
    private volatile boolean cancelled = false;

    public LLMClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.tools = new JSONArray();
    }

    public void setTools(JSONArray tools) {
        this.tools = tools != null ? tools : new JSONArray();
    }

    public void cancel() {
        cancelled = true;
    }

    public void resetCancel() {
        cancelled = false;
    }

    /** 发起流式对话请求（后台线程），结果通过 listener 回调 */
    public void chatStream(List<ChatMessage> messages, JSONObject extraSystem, StreamListener listener) {
        pool.execute(() -> {
            cancelled = false;
            // 首次带 tools；若端点不支持（HTTP 400/415），自动去掉 tools 重试一次
            chatStreamInner(messages, extraSystem, listener, true);
        });
    }

    private void chatStreamInner(List<ChatMessage> messages, JSONObject extraSystem,
                                 StreamListener listener, boolean withTools) {
        if (cancelled) {
            listener.onDone("");
            return;
        }
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("stream", true);
            if (withTools && tools.length() > 0) {
                body.put("tools", tools);
                body.put("tool_choice", "auto");
            }

            JSONArray msgs = new JSONArray();
            // system prompt
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", buildSystemPrompt(extraSystem));
            msgs.put(sys);
            for (ChatMessage m : messages) {
                msgs.put(m.toJson());
            }
            body.put("messages", msgs);

            URL url = new URL(buildUrl(baseUrl));
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String err = readStream(conn.getErrorStream());
                // 部分 OpenAI 兼容端点拒绝 tools 字段：降级为不带 tools 重试一次
                if (withTools && tools.length() > 0 && (code == 400 || code == 415)) {
                    conn.disconnect();
                    chatStreamInner(messages, extraSystem, listener, false);
                    return;
                }
                // 上下文超长（token 超限）：截断早期历史后重试一次，避免长对话卡死
                if (isContextOverflow(err)) {
                    List<ChatMessage> trimmed = trimHistory(messages);
                    if (trimmed.size() < messages.size()) {
                        conn.disconnect();
                        chatStreamInner(trimmed, extraSystem, listener, withTools);
                        return;
                    }
                }
                listener.onError("HTTP " + code + ": " + err);
                return;
            }

                StringBuilder full = new StringBuilder();
                String toolName = null;
                StringBuilder toolArgsBuf = new StringBuilder();
                JSONObject toolArgs = null;
                String toolCallId = null;
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null && !cancelled) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || data.equals("[DONE]")) continue;
                    try {
                        JSONObject chunk = new JSONObject(data);
                        JSONArray choices = chunk.optJSONArray("choices");
                        if (choices == null || choices.length() == 0) continue;
                        JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                        if (delta == null) continue;

                        if (delta.has("content") && !delta.isNull("content")) {
                            String t = delta.optString("content");
                            if (!t.isEmpty()) {
                                full.append(t);
                                listener.onDelta(t);
                            }
                        }

                        if (delta.has("tool_calls") && !delta.isNull("tool_calls")) {
                            JSONArray tcs = delta.getJSONArray("tool_calls");
                            for (int i = 0; i < tcs.length(); i++) {
                                JSONObject tc = tcs.getJSONObject(i);
                                // 捕获 tool_call_id
                                if (tc.has("id") && !tc.isNull("id")) {
                                    toolCallId = tc.optString("id");
                                }
                                JSONObject fn = tc.optJSONObject("function");
                                if (fn == null) continue;
                                if (fn.has("name") && !fn.isNull("name")) {
                                    toolName = fn.optString("name");
                                    toolArgsBuf = new StringBuilder();
                                }
                                if (fn.has("arguments") && !fn.isNull("arguments")) {
                                    toolArgsBuf.append(fn.optString("arguments"));
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                // 收尾：无论是否发生工具调用，都回调 onDone（含空内容），
                // 否则 AICore 的 busy 状态可能因缺少收尾回调而永久卡死
                if (toolName != null) {
                    try {
                        toolArgs = new JSONObject(toolArgsBuf.toString().isEmpty() ? "{}" : toolArgsBuf.toString());
                    } catch (Exception ignored) {
                        toolArgs = new JSONObject();
                    }
                    listener.onToolCall(toolName, toolArgs, toolCallId != null ? toolCallId : "");
                }
                listener.onDone(full.toString());
            } catch (Exception ex) {
                listener.onError(ex.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
    }

    /** 非流式简单对话（用于工具结果回填后二次请求） */
    public void chatOnce(List<ChatMessage> messages, JSONObject extraSystem, Callback cb) {
        pool.execute(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("stream", false);
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "system").put("content", buildSystemPrompt(extraSystem)));
                for (ChatMessage m : messages) msgs.put(m.toJson());
                body.put("messages", msgs);

                URL url = new URL(buildUrl(baseUrl));
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                if (code != 200) {
                    String err = readStream(conn.getErrorStream());
                    // 上下文超长（token 超限）：截断早期历史后重试一次
                    if (isContextOverflow(err)) {
                        List<ChatMessage> trimmed = trimHistory(messages);
                        if (trimmed.size() < messages.size()) {
                            conn.disconnect();
                            chatOnce(trimmed, extraSystem, cb);
                            return;
                        }
                    }
                    cb.onResult(null, "HTTP " + code + ": " + err);
                    return;
                }
                String resp = readStream(conn.getInputStream());
                JSONObject o = new JSONObject(resp);
                String content = o.optJSONArray("choices").getJSONObject(0)
                        .optJSONObject("message").optString("content");
                cb.onResult(content, null);
            } catch (Exception ex) {
                cb.onResult(null, ex.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    public interface Callback {
        void onResult(String text, String error);
    }

    /** 判断 HTTP 错误是否由上下文超长（token 超限）引起 */
    private boolean isContextOverflow(String err) {
        if (err == null) return false;
        String e = err.toLowerCase(Locale.ROOT);
        return e.contains("context length") || e.contains("maximum context")
                || e.contains("context_length") || e.contains("too many tokens")
                || e.contains("token limit") || e.contains("context overflow")
                || e.contains("prompt is too long") || e.contains("max context")
                || e.contains("exceeded its max tokens");
    }

    /** 截断历史：保留末尾最多 16 条消息，并裁剪掉开头孤立的 tool/带工具调用的 assistant 消息 */
    private List<ChatMessage> trimHistory(List<ChatMessage> messages) {
        if (messages == null || messages.size() <= 16) return messages;
        List<ChatMessage> out = new ArrayList<>(messages.subList(messages.size() - 16, messages.size()));
        while (!out.isEmpty()) {
            ChatMessage first = out.get(0);
            boolean isTool = "tool".equals(first.role);
            boolean isAssistantWithCalls = "assistant".equals(first.role)
                    && first.toolCalls != null && first.toolCalls.length() > 0;
            if (!isTool && !isAssistantWithCalls) break;
            out.remove(0);
        }
        return out;
    }

    private String buildSystemPrompt(JSONObject extra) {
        StringBuilder sb = new StringBuilder();
        if (extra != null && extra.has("system")) {
            sb.append(extra.optString("system"));
        }
        if (extra != null && extra.has("proactive_hint")) {
            sb.append("\n\n## 主动互动\n");
            sb.append("现在是你在自主找话题的时刻：").append(extra.optString("proactive_hint")).append("\n");
            sb.append("请主动说一句应景的话（reply 字段），开启对话。\n");
        }
        if (extra != null && extra.has("memories")) {
            sb.append("\n\n记忆：\n").append(extra.optString("memories"));
        }
        if (extra != null && extra.has("tools_desc")) {
            sb.append("\n\n可用工具：\n").append(extra.optString("tools_desc"));
        }
        return sb.toString();
    }

    private static String readStream(java.io.InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append("\n");
        return sb.toString().trim();
    }

    /** 拼接 chat/completions 完整地址：容忍尾斜杠、容忍用户已填完整 /chat/completions */
    private static String buildUrl(String base) {
        if (base == null) base = "";
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.toLowerCase(Locale.ROOT).endsWith("/chat/completions")) {
            return b;
        }
        return b + "/chat/completions";
    }

    /** 测试 API 连接：最小非流式请求，回调返回耗时与错误详情 */
    public static void testConnection(String base, String key, String model, Callback cb) {
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("stream", false);
                JSONArray msgs = new JSONArray();
                msgs.put(new JSONObject().put("role", "user").put("content", "hi"));
                body.put("messages", msgs);
                URL u = new URL(buildUrl(base));
                conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + key);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(30000);
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int code = conn.getResponseCode();
                long cost = System.currentTimeMillis() - t0;
                if (code == 200) {
                    cb.onResult("连接成功（" + cost + "ms）", null);
                } else {
                    String err = readStream(conn.getErrorStream());
                    cb.onResult(null, "HTTP " + code + "（" + cost + "ms）" + (err.isEmpty() ? "" : "\n" + err));
                }
            } catch (Exception ex) {
                long cost = System.currentTimeMillis() - t0;
                cb.onResult(null, "连接失败（" + cost + "ms）："
                        + ex.getClass().getSimpleName() + " " + ex.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
