package com.digitallife.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * HarnessContext 的契约：服务可查、副作用按注册逆序撤销。
 */
public class HarnessContextTest {

    @Test
    public void provideThenGetReturnsSameInstance() {
        HarnessContext ctx = new HarnessContext();
        Object svc = new Object();
        ctx.provide("svc", svc);
        assertNotNull(ctx.get("svc"));
        assertTrue(svc == ctx.get("svc"));
    }

    @Test
    public void getUnknownKeyReturnsNull() {
        HarnessContext ctx = new HarnessContext();
        assertNull(ctx.get("nope"));
        assertNull(ctx.get(null));
    }

    @Test
    public void provideRejectsNullKeyOrService() {
        HarnessContext ctx = new HarnessContext();
        ctx.provide(null, new Object());
        ctx.provide("k", null);
        assertNull(ctx.get("k"));
    }

    @Test
    public void disposeUnwindsEffectsInReverseOrder() {
        HarnessContext ctx = new HarnessContext();
        final List<String> order = new ArrayList<>();
        ctx.effect(() -> order.add("first"));
        ctx.effect(() -> order.add("second"));
        ctx.dispose();
        assertEquals(2, order.size());
        assertEquals("second", order.get(0));
        assertEquals("first", order.get(1));
    }

    @Test
    public void disposeClearsServices() {
        HarnessContext ctx = new HarnessContext();
        ctx.provide("svc", new Object());
        ctx.dispose();
        assertNull(ctx.get("svc"));
    }

    @Test
    public void disposeToleratesThrowingEffect() {
        HarnessContext ctx = new HarnessContext();
        final List<String> order = new ArrayList<>();
        ctx.effect(() -> {
            throw new RuntimeException("boom");
        });
        ctx.effect(() -> order.add("survivor"));
        ctx.dispose();
        assertEquals(1, order.size());
    }

    @Test
    public void effectOfNullIsIgnored() {
        HarnessContext ctx = new HarnessContext();
        ctx.effect(null);
        ctx.dispose();
    }

    @Test
    public void bootRegistersCoreServices() {
        DeepSeekHarness h = DeepSeekHarness.boot(null);
        assertNotNull(h.session());
        assertNotNull(h.prompt());
        assertNotNull(h.tools());
        assertNotNull(h.loop());
        assertNotNull(h.agents());
        assertEquals(Profile.CHAT, h.profileName());
        assertEquals(11, h.pluginIds().size());
        assertTrue(h.pluginIds().contains("dsh-compress"));
        assertTrue(h.pluginIds().contains("dsh-persona"));
        assertTrue(h.pluginIds().contains("dsh-clock"));
    }

    @Test
    public void isolatedProfileOmitsChatOnlyPlugins() {
        DeepSeekHarness h = DeepSeekHarness.bootIsolated(null);
        assertEquals(Profile.ISOLATED, h.profileName());
        assertEquals(8, h.pluginIds().size());
        assertFalse(h.pluginIds().contains("dsh-persona"));
        assertFalse(h.pluginIds().contains("dsh-memory"));
        assertFalse(h.pluginIds().contains("dsh-skills"));
        assertTrue(h.pluginIds().contains("dsh-compress"));
        assertTrue(h.pluginIds().contains("dsh-agents"));
    }

    @Test
    public void unmountRemovesPluginId() {
        DeepSeekHarness h = DeepSeekHarness.boot(null);
        assertTrue(h.unmount("dsh-skills"));
        assertFalse(h.pluginIds().contains("dsh-skills"));
        assertFalse(h.unmount("no-such-plugin"));
    }
}
