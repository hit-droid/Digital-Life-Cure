package com.digitallife.mcp;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * MCP 响应解析（v1.122.0，纯逻辑，无 Android 依赖，可单测）。
 *
 * Streamable HTTP 的响应有两种形态：
 * <ul>
 *   <li>普通 JSON：直接一个 JSON-RPC 报文；</li>
 *   <li>SSE：可能包含多个事件（如服务端通知 + 本次响应），单个事件的数据还可能分多行
 *       {@code data:} 承载。</li>
 * </ul>
 * 旧实现把所有 {@code data:} 行无分隔拼接成一个字符串再 {@code new JSONObject}——
 * 多事件时必然拼成非法 JSON，多行事件时也会把 JSON 撑坏。这里按 SSE 规范切事件，
 * 并优先挑出与请求 id 匹配的那一帧。
 */
public final class McpResponseParser {

    private McpResponseParser() {}

    /**
     * 解析响应，优先返回 id 匹配的帧。
     *
     * @param text       原始响应体
     * @param expectedId 本次请求的 JSON-RPC id；≤0 表示不校验
     * @throws Exception 响应为空、非 JSON、id 与请求不符，或 SSE 中没有任何可用 JSON 帧
     */
    public static JSONObject parse(String text, long expectedId) throws Exception {
        if (text == null || text.trim().isEmpty()) {
            throw new Exception("MCP 响应为空");
        }
        String t = text.trim();
        if (t.startsWith("data:") || t.startsWith("event:") || t.startsWith(":")) {
            return parseSse(t, expectedId);
        }
        JSONObject o;
        try {
            o = new JSONObject(t);
        } catch (Exception e) {
            throw new Exception("MCP 响应非 JSON: " + e.getMessage());
        }
        // v1.191.0：普通 JSON 也校验 id——回的是别的请求的应答时必须报错，不能当成自己的结果用
        if (expectedId > 0 && hasId(o) && o.optLong("id", Long.MIN_VALUE) != expectedId) {
            throw new Exception("MCP 响应 id 不匹配（期望 " + expectedId
                    + "，实际 " + o.optLong("id", -1) + "）");
        }
        return o;
    }

    private static JSONObject parseSse(String text, long expectedId) throws Exception {
        List<String> frames = splitEvents(text);
        JSONObject firstParsable = null;
        JSONObject mismatched = null;
        for (String frame : frames) {
            JSONObject o = tryParse(frame);
            if (o == null) continue;
            if (firstParsable == null) firstParsable = o;
            if (expectedId <= 0) return o;
            if (!hasId(o)) continue;            // 帧没回显 id（服务端通知）：无法据此判定，留给兜底
            if (o.optLong("id", Long.MIN_VALUE) == expectedId) return o;
            if (mismatched == null) mismatched = o;
        }
        // v1.191.0：帧带了 id 却对不上 = 响应属于别的请求，必须报错；
        // 此前一律回退到「首个可解析帧」，会把别的请求的应答 / 服务端通知当成自己的结果，真实错误被吞。
        if (mismatched != null) {
            throw new Exception("MCP 响应 id 不匹配（期望 " + expectedId
                    + "，实际 " + mismatched.optLong("id", -1) + "）");
        }
        if (firstParsable != null) return firstParsable;
        throw new Exception("MCP SSE 响应中无可用 JSON 帧");
    }

    /** 按 SSE 规范切分事件：空行分隔，同一事件的多个 data: 行以 \n 连接。 */
    static List<String> splitEvents(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String raw : text.split("\n", -1)) {
            String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.isEmpty()) {
                flush(data, out);
                continue;
            }
            if (line.startsWith(":")) continue;            // 注释行
            if (line.startsWith("data:")) {
                String d = line.substring(5);
                if (d.startsWith(" ")) d = d.substring(1);
                if (data.length() > 0) data.append('\n');
                data.append(d);
            }
            // event: / id: / retry: 对本场景无用，忽略
        }
        flush(data, out);
        return out;
    }

    private static void flush(StringBuilder data, List<String> out) {
        if (data.length() == 0) return;
        out.add(data.toString());
        data.setLength(0);
    }

    private static JSONObject tryParse(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty() || "[DONE]".equals(t)) return null;
        try {
            return new JSONObject(t);
        } catch (Exception e) {
            return null;
        }
    }

    /** 帧是否回显了 id（服务端通知帧通常没有 id） */
    private static boolean hasId(JSONObject o) {
        return o != null && o.has("id") && !o.isNull("id");
    }
}
