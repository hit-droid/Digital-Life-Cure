package com.digitallife.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import com.digitallife.brain.LLMClient;

/**
 * SessionLog 的契约：模型历史必须能从仅追加的事件日志重建。
 */
public class SessionLogTest {

    private static JSONObject data(String... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put(kv[i], kv[i + 1]);
        } catch (Exception ignored) {
        }
        return o;
    }

    @Test
    public void projectsUserAssistantAndToolMessages() {
        SessionLog log = new SessionLog();
        log.append(SessionEvent.USER_MESSAGE, data("text", "你好"));
        log.append(SessionEvent.ASSISTANT_MESSAGE, data("text", "在的"));
        log.append(SessionEvent.TOOL_RESULT, data("text", "结果", "tool_call_id", "call_1"));

        List<LLMClient.ChatMessage> msgs = log.deriveMessages();
        assertEquals(3, msgs.size());
        assertEquals("user", msgs.get(0).role);
        assertEquals("你好", msgs.get(0).content);
        assertEquals("assistant", msgs.get(1).role);
        assertEquals("在的", msgs.get(1).content);
        assertEquals("tool", msgs.get(2).role);
        assertEquals("结果", msgs.get(2).content);
        assertEquals("call_1", msgs.get(2).toolCallId);
    }

    @Test
    public void keepsToolCallsOnAssistantMessage() {
        SessionLog log = new SessionLog();
        JSONArray tcs = new JSONArray();
        JSONObject tc = new JSONObject();
        try {
            tc.put("id", "call_9");
            tc.put("type", "function");
            tc.put("function", new JSONObject().put("name", "echo").put("arguments", "{}"));
            tcs.put(tc);
        } catch (Exception ignored) {
        }
        JSONObject asst = new JSONObject();
        try {
            asst.put("tool_calls", tcs);
        } catch (Exception ignored) {
        }
        log.append(SessionEvent.ASSISTANT_MESSAGE, asst);

        List<LLMClient.ChatMessage> msgs = log.deriveMessages();
        assertEquals(1, msgs.size());
        assertEquals("assistant", msgs.get(0).role);
        assertNull(msgs.get(0).content);
        assertNotNull(msgs.get(0).toolCalls);
        assertEquals(1, msgs.get(0).toolCalls.length());
    }

    @Test
    public void dropsAssistantMessageWithNoTextAndNoToolCalls() {
        SessionLog log = new SessionLog();
        log.append(SessionEvent.ASSISTANT_MESSAGE, new JSONObject());
        assertTrue(log.deriveMessages().isEmpty());
    }

    @Test
    public void dropsEmptyUserMessage() {
        SessionLog log = new SessionLog();
        log.append(SessionEvent.USER_MESSAGE, data("text", ""));
        assertTrue(log.deriveMessages().isEmpty());
    }

    @Test
    public void preservesSystemRoleForTruncationNote() {
        SessionLog log = new SessionLog();
        log.append(SessionEvent.USER_MESSAGE, data("text", "（已省略更早对话）", "role", "system"));
        List<LLMClient.ChatMessage> msgs = log.deriveMessages();
        assertEquals(1, msgs.size());
        assertEquals("system", msgs.get(0).role);
    }

    @Test
    public void ignoresTurnAndStepEventsInProjection() {
        SessionLog log = new SessionLog();
        log.append(SessionEvent.TURN_START, new JSONObject());
        log.append(SessionEvent.STEP_START, new JSONObject());
        log.append(SessionEvent.SYSTEM_MESSAGE, data("text", "人设"));
        log.append(SessionEvent.STEP_END, new JSONObject());
        log.append(SessionEvent.TURN_END, new JSONObject());
        assertTrue(log.deriveMessages().isEmpty());
    }

    @Test
    public void clearBumpsGeneration() {
        SessionLog log = new SessionLog();
        int before = log.generation();
        log.append(SessionEvent.USER_MESSAGE, data("text", "hi"));
        assertEquals(before, log.generation());
        log.clear();
        assertEquals(before + 1, log.generation());
        assertTrue(log.deriveMessages().isEmpty());
    }

    @Test
    public void allReturnsImmutableSnapshot() {
        SessionLog log = new SessionLog();
        log.append(SessionEvent.USER_MESSAGE, data("text", "a"));
        List<SessionEvent> snapshot = log.all();
        log.append(SessionEvent.USER_MESSAGE, data("text", "b"));
        assertEquals(1, snapshot.size());
        assertEquals(2, log.all().size());
    }

    @Test
    public void recentReturnsTail() {
        SessionLog log = new SessionLog();
        for (int i = 0; i < 5; i++) log.append(SessionEvent.USER_MESSAGE, data("text", "m" + i));
        List<SessionEvent> tail = log.recent(2);
        assertEquals(2, tail.size());
        assertEquals("m3", tail.get(0).text());
        assertEquals("m4", tail.get(1).text());
    }

    @Test
    public void appendBroadcastsOnSessionEvent() {
        SessionLog log = new SessionLog();
        EventBus bus = new EventBus();
        log.attach(bus);
        final SessionEvent[] seen = new SessionEvent[1];
        bus.on("session/event", payload -> seen[0] = (SessionEvent) payload);
        SessionEvent e = log.append(SessionEvent.USER_MESSAGE, data("text", "hi"));
        assertSame(e, seen[0]);
    }
}
