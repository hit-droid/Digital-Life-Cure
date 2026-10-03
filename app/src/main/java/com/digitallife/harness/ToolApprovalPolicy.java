package com.digitallife.harness;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * 危险工具审批策略（Issue #40，纯逻辑，JVM 可测）。
 *
 * <p>工具循环此前对模型调用没有任何审批：{@code restore_data}（覆盖本地数据）、
 * {@code open_app}、{@code clipboard_write}（写用户剪贴板）、{@code send_notification}
 * （对外弹通知）等，模型能在一次回复里直接触发，用户没有拒绝的机会。</p>
 *
 * <p>这里只做「判定 + 记账 + 文案」三件事，不碰 UI：</p>
 * <ul>
 *   <li>危险工具固定集合默认需要审批，未知工具默认放行（只读工具零打扰）；</li>
 *   <li>会话级允许集：用户选「本会话始终允许」后不再打扰；</li>
 *   <li>拒绝后本轮内不再自动重试（{@link #markDeniedThisTurn}），避免模型死循环；</li>
 *   <li>{@link #summary} 生成确认文案，敏感字段一律脱敏。</li>
 * </ul>
 */
public final class ToolApprovalPolicy {

    /** 用户对一次审批请求的选择 */
    public enum Outcome { ALLOW_ONCE, ALLOW_SESSION, DENY }

    /** 固定需要审批的工具 */
    private static final Set<String> DANGEROUS = new HashSet<>(Arrays.asList(
            "restore_data",     // 覆盖本地数据
            "open_app",         // 拉起其它 App
            "clipboard_write",  // 写用户剪贴板
            "send_notification",// 对外弹系统通知
            "memory_forget"));  // 删除记忆

    /** 危险工具前缀（{@code delete_*} / {@code forget_*} 之类） */
    private static final String[] DANGEROUS_PREFIXES = {"delete_", "forget_", "remove_"};

    /** 参数名命中这些子串时，值一律以 *** 呈现，避免把密钥/令牌回显到确认卡片 */
    private static final String[] SENSITIVE_HINTS = {
            "key", "token", "secret", "password", "passwd", "credential", "authorization", "cookie"};

    /** 单个参数值在文案里的最长显示长度 */
    private static final int MAX_VALUE_LEN = 60;

    private final Set<String> sessionAllowed = new HashSet<>();
    private final Set<String> deniedThisTurn = new HashSet<>();

    /** 该工具是否需要用户确认；未知工具放行（只读工具零打扰） */
    public static boolean requiresApproval(String toolName) {
        if (toolName == null || toolName.isEmpty()) return false;
        if (DANGEROUS.contains(toolName)) return true;
        String lower = toolName.toLowerCase(Locale.ROOT);
        for (String prefix : DANGEROUS_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 本会话内始终允许该工具 */
    public void allowForSession(String toolName) {
        if (toolName != null && !toolName.isEmpty()) sessionAllowed.add(toolName);
    }

    /** 该工具是否已被本会话放行 */
    public boolean isAllowedForSession(String toolName) {
        return toolName != null && sessionAllowed.contains(toolName);
    }

    /** 标记本轮已拒绝，避免模型对同一工具自动重试 */
    public void markDeniedThisTurn(String toolName) {
        if (toolName != null && !toolName.isEmpty()) deniedThisTurn.add(toolName);
    }

    /** 本轮是否已拒绝过该工具 */
    public boolean isDeniedThisTurn(String toolName) {
        return toolName != null && deniedThisTurn.contains(toolName);
    }

    /** 新一轮开始：清空「本轮拒绝」记账（会话放行集不受影响） */
    public void beginTurn() {
        deniedThisTurn.clear();
    }

    /**
     * 生成确认文案：工具名 + 关键参数摘要。
     * <p>敏感字段（key/token/secret/password/...）的值一律替换为 {@code ***}，
     * 绝不回显；过长值截断，避免卡片被撑爆。</p>
     */
    public static String summary(String toolName, JSONObject argsJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("工具：").append(toolName == null ? "" : toolName);
        if (argsJson == null || argsJson.length() == 0) return sb.toString();
        sb.append("\n参数：");
        boolean first = true;
        Iterator<String> it = argsJson.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!first) sb.append("，");
            first = false;
            Object v = argsJson.opt(k);
            sb.append(k).append('=')
                    .append(isSensitiveKey(k) ? "***" : clip(String.valueOf(v)));
        }
        return sb.toString();
    }

    private static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.toLowerCase(Locale.ROOT);
        for (String hint : SENSITIVE_HINTS) {
            if (lower.contains(hint)) return true;
        }
        return false;
    }

    private static String clip(String s) {
        if (s == null) return "";
        String oneLine = s.replace('\n', ' ');
        return oneLine.length() <= MAX_VALUE_LEN
                ? oneLine
                : oneLine.substring(0, MAX_VALUE_LEN - 1) + "\u2026";
    }
}
