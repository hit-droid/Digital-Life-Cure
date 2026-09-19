package com.digitallife.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProfileTest {

    @Test
    public void chatProfileStacksElevenPlugins() {
        Profile p = Profile.chat(null);
        assertEquals(Profile.CHAT, p.name);
        assertEquals(11, p.plugins().size());
        assertEquals("dsh-session", p.plugins().get(0).id());
        assertEquals("dsh-guard", p.plugins().get(p.plugins().size() - 1).id());
    }

    @Test
    public void isolatedProfileOmitsPersonaMemorySkills() {
        Profile p = Profile.isolated(null);
        assertEquals(Profile.ISOLATED, p.name);
        assertEquals(8, p.plugins().size());
        for (Plugin plugin : p.plugins()) {
            assertFalse(plugin.id().equals("dsh-persona"));
            assertFalse(plugin.id().equals("dsh-memory"));
            assertFalse(plugin.id().equals("dsh-skills"));
        }
    }

    @Test
    public void bootIsolatedDoesNotBecomeCurrent() {
        DeepSeekHarness.boot(null);
        DeepSeekHarness iso = DeepSeekHarness.bootIsolated(null);
        assertTrue(DeepSeekHarness.current() != iso);
    }

    @Test
    public void agentsRegistryTracksLiveHandleThenClears() {
        DeepSeekHarness h = DeepSeekHarness.boot(null);
        ScriptedLlm llm = new ScriptedLlm();
        llm.enqueue(ScriptedLlm.text("好"));
        AgentHandle handle = h.startTurn(llm, null, "人设",
                java.util.Collections.singletonList(
                        new com.digitallife.brain.LLMClient.ChatMessage("user", "hi")),
                new AgentHandle.Listener() {
                    @Override public void onDelta(String text) {}
                    @Override public void onToolCall(String name, org.json.JSONObject args, String toolCallId) {}
                    @Override public void onToolResult(String name, boolean ok, String result) {}
                    @Override public void onDone(String fullText) {}
                    @Override public void onError(String error) {}
                    @Override public void onTurn(String phase) {}
                });
        assertNotNull(handle);
        assertEquals(0, h.agents().size());
        assertNull(h.agents().get(handle.id));
    }

    @Test
    public void disposeClearsCurrent() {
        DeepSeekHarness h = DeepSeekHarness.boot(null);
        assertTrue(h == DeepSeekHarness.current());
        h.dispose();
        assertNull(DeepSeekHarness.current());
        assertNull(h.session());
    }
}
