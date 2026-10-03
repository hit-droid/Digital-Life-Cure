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
        // 非 SSE 只有一帧，无法歧义，不因 id 不符而失败
        JSONObject o = McpResponseParser.parse("{\"result\":{\"n\":2}}", 99);
        assertEquals(2, o.getJSONObject("result").optInt("n"));
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
        String sse = "data: {\"jsonrpc\":\"2.0\",\"result\":{\"x\":1}}\n\n";
        JSONObject o = McpResponseParser.parse(sse, 99);
        assertEquals(1, o.getJSONObject("result").optInt("x"));
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
}
