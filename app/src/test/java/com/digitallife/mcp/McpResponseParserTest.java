package com.digitallife.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/**
 * {@link McpResponseParser} 回归。
 *
 * 原实现把所有 SSE {@code data:} 行无分隔拼接再解析：多事件时必成非法 JSON、
 * 多行事件时也会撑坏 JSON。这里锁住"按事件切分 + 优先挑 id 匹配帧"的行为。
 */
public class McpResponseParserTest {

    // ==================== 普通 JSON ====================

    @Test
    public void parse_plainJson() throws Exception {
        JSONObject o = McpResponseParser.parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}", 1);
        assertEquals(1, o.optLong("id"));
        assertTrue(o.getJSONObject("result").optBoolean("ok"));
    }

    @Test
    public void parse_plainJsonToleratesIdMismatch() throws Exception {
        // 非 SSE 只有一帧，且**没有回显 id** → 无法判定归属，容忍
        JSONObject o = McpResponseParser.parse("{\"result\":{\"n\":2}}", 99);
        assertEquals(2, o.getJSONObject("result").optInt("n"));
    }

    /**
     * v1.191.0：普通 JSON 也校验 id——回的是别的请求的应答时必须报错，
     * 不能当成自己的结果用（此前会静默吞掉真实错误）。
     */
    @Test
    public void parse_plainJsonWithMismatchedIdThrows() {
        assertThrowsWithIdMismatch("{\"jsonrpc\":\"2.0\",\"id\":5,\"result\":{\"ok\":true}}", 7);
    }

    @Test
    public void parse_plainJsonWithMatchingIdOk() throws Exception {
        JSONObject o = McpResponseParser.parse("{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"ok\":true}}", 7);
        assertEquals(7, o.optLong("id"));
    }

    // ==================== SSE ====================

    @Test
    public void parse_sseSingleFrame() throws Exception {
        String sse = "event: message\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"ok\":true}}\n\n";
        JSONObject o = McpResponseParser.parse(sse, 3);
        assertEquals(3, o.optLong("id"));
        assertTrue(o.getJSONObject("result").optBoolean("ok"));
    }

    @Test
    public void parse_ssePicksFrameMatchingId() throws Exception {
        // 第一帧是服务端通知（无 id），第二帧才是本次请求的响应
        String sse = "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"n\":1}}\n\n";
        JSONObject o = McpResponseParser.parse(sse, 7);
        assertEquals("应挑中 id=7 的帧而非首个通知帧", 7, o.optLong("id"));
        assertEquals(1, o.getJSONObject("result").optInt("n"));
    }

    @Test
    public void parse_sseMultiLineDataEvent() throws Exception {
        // 同一事件的 data 分多行，按 SSE 规范以 \n 连接
        String sse = "data: {\"jsonrpc\":\"2.0\",\n"
                + "data: \"id\":5,\"result\":{\"a\":1}}\n\n";
        JSONObject o = McpResponseParser.parse(sse, 5);
        assertEquals(5, o.optLong("id"));
        assertEquals(1, o.getJSONObject("result").optInt("a"));
    }

    @Test
    public void parse_sseIgnoresCommentsAndEventLines() throws Exception {
        String sse = ": keep-alive\n"
                + "event: message\n"
                + "id: 42\n"
                + "data: {\"id\":1,\"result\":{\"v\":9}}\n\n";
        JSONObject o = McpResponseParser.parse(sse, 1);
        assertEquals(9, o.getJSONObject("result").optInt("v"));
    }

    @Test
    public void parse_sseCrlfLineEndings() throws Exception {
        String sse = "data: {\"id\":2,\"result\":{\"b\":3}}\r\n\r\n";
        JSONObject o = McpResponseParser.parse(sse, 2);
        assertEquals(3, o.getJSONObject("result").optInt("b"));
    }

    @Test
    public void parse_sseFallsBackToFirstParsableWhenNoIdMatch() throws Exception {
        // 帧没有回显 id → 无法判定归属，容忍回退
        String sse = "data: {\"jsonrpc\":\"2.0\",\"result\":{\"x\":1}}\n\n";
        JSONObject o = McpResponseParser.parse(sse, 99);
        assertEquals(1, o.getJSONObject("result").optInt("x"));
    }

    /**
     * v1.191.0：SSE 里出现了**带 id 但对不上**的帧 → 必须报错。
     * 此前一律回退到「首个可解析帧」，会把别的请求的应答当成自己的结果，真实错误被吞。
     */
    @Test
    public void parse_sseWithMismatchedIdThrows() {
        String sse = "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"x\":1}}\n\n"
                + "data: {\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"x\":2}}\n\n";
        assertThrowsWithIdMismatch(sse, 7);
    }

    @Test
    public void parse_sseIgnoresDoneFrame() throws Exception {
        String sse = "data: [DONE]\n\ndata: {\"id\":8,\"result\":{\"z\":1}}\n\n";
        JSONObject o = McpResponseParser.parse(sse, 8);
        assertEquals(1, o.getJSONObject("result").optInt("z"));
    }

    // ==================== 失败路径 ====================

    @Test
    public void parse_emptyOrNullThrows() {
        assertThrows(null);
        assertThrows("");
        assertThrows("   ");
    }

    @Test
    public void parse_nonJsonThrows() {
        assertThrows("这不是 JSON");
        assertThrows("{oops");
    }

    @Test
    public void parse_sseWithoutAnyFrameThrows() {
        assertThrows("data: [DONE]\n\n");
        assertThrows(": only-comment\n\n");
    }

    // ==================== splitEvents ====================

    @Test
    public void splitEvents_separatesByBlankLine() {
        List<String> ev = McpResponseParser.splitEvents("data: a\n\ndata: b\n\n");
        assertEquals(2, ev.size());
        assertEquals("a", ev.get(0));
        assertEquals("b", ev.get(1));
    }

    @Test
    public void splitEvents_joinsMultiLineDataWithNewline() {
        List<String> ev = McpResponseParser.splitEvents("data: a\ndata: b\n\n");
        assertEquals(1, ev.size());
        assertEquals("a\nb", ev.get(0));
    }

    @Test
    public void splitEvents_stripsSingleLeadingSpaceAfterData() {
        List<String> ev = McpResponseParser.splitEvents("data:  two-spaces\n\n");
        assertEquals(1, ev.size());
        assertEquals(" two-spaces", ev.get(0));
    }

    private static void assertThrows(String input) {
        try {
            McpResponseParser.parse(input, 1);
            fail("应当抛出异常，输入：" + input);
        } catch (Exception expected) {
            // 预期
        }
    }

    /** 应当因 id 不匹配抛错（且错误信息里含「id」） */
    private static void assertThrowsWithIdMismatch(String input, long expectedId) {
        try {
            McpResponseParser.parse(input, expectedId);
            fail("应当因 id 不匹配抛错，输入：" + input);
        } catch (Exception expected) {
            assertTrue("错误信息应说明 id 不匹配，实际：" + expected.getMessage(),
                    String.valueOf(expected.getMessage()).contains("id"));
        }
    }
}
