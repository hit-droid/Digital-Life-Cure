package com.digitallife.tools;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Hook Runner：工具调用前后的拦截器链。
 * 用途：权限检查、使用统计、日志记录、上下文注入。
 * 借鉴 Operit AI 的 Hook Runner 设计。
 */
public class HookRunner {

    public interface PreHook {
        /**
         * 工具调用前执行。
         * 返回 null 表示放行，返回非 null 表示拒绝（值为错误信息）。
         */
        String preCall(String toolName, JSONObject args);
    }

    public interface PostHook {
        /**
         * 工具调用后执行。
         * 返回注入到对话上下文的字符串（可为空）。
         */
        String postCall(String toolName, JSONObject args, String result, String error);
    }

    public interface ErrorHook {
        void onError(String toolName, JSONObject args, String error);
    }

    private static final HookRunner INSTANCE = new HookRunner();
    private final List<PreHook> preHooks = new ArrayList<>();
    private final List<PostHook> postHooks = new ArrayList<>();
    private final List<ErrorHook> errorHooks = new ArrayList<>();

    private HookRunner() {}

    public static HookRunner getInstance() { return INSTANCE; }

    public void addPreHook(PreHook h) { if (h != null) preHooks.add(h); }
    public void addPostHook(PostHook h) { if (h != null) postHooks.add(h); }
    public void addErrorHook(ErrorHook h) { if (h != null) errorHooks.add(h); }

    public void clear() {
        preHooks.clear();
        postHooks.clear();
        errorHooks.clear();
    }

    /**
     * 执行 pre 钩子链。
     * 任一返回非 null 即短路拒绝。
     */
    public String runPre(String toolName, JSONObject args) {
        for (PreHook h : preHooks) {
            try {
                String r = h.preCall(toolName, args);
                if (r != null) return r;
            } catch (Exception e) {
                // 钩子异常不阻断
            }
        }
        return null;
    }

    /**
     * 执行 post 钩子链，拼接所有非空返回值（用换行）。
     */
    public String runPost(String toolName, JSONObject args, String result, String error) {
        StringBuilder sb = new StringBuilder();
        for (PostHook h : postHooks) {
            try {
                String r = h.postCall(toolName, args, result, error);
                if (r != null && !r.trim().isEmpty()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(r);
                }
            } catch (Exception e) {
                // 钩子异常不阻断
            }
        }
        return sb.toString();
    }

    public void runError(String toolName, JSONObject args, String error) {
        for (ErrorHook h : errorHooks) {
            try {
                h.onError(toolName, args, error);
            } catch (Exception e) {
                // 钩子异常不阻断
            }
        }
    }
}
