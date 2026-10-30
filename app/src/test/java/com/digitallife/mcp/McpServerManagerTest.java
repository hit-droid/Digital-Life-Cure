package com.digitallife.mcp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.digitallife.tools.Tool;
import com.digitallife.tools.ToolRegistry;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;

/**
 * {@link McpServerManager} 单测（v1.191.0）。
 *
 * <p>本进程里 {@code McpServerManager} 有多个实例（桌宠服务、插件页），
 * 而 MCP 远程工具注册进的是**全局单例** {@code ToolRegistry}。
 * 此前连接表是实例字段 → 「插件页删除服务器」时它自己实例的表是空的，
 * 桌宠侧建立的连接没被断开、工具残留在注册表里，大脑仍能调用一个已删除服务器的工具。
 * 现在连接表改为进程级共享，并按**配置 id**（而非 endpoint）匹配。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class McpServerManagerTest {

    private Context ctx;
    private ToolRegistry registry;

    /** 假 MCP 工具：只登记名字，够用来验证注册/注销 */
    private static final class FakeTool implements Tool {
        private final String name;

        FakeTool(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public JSONObject getSchema() {
            return new JSONObject();
        }

        @Override
        public String execute(JSONObject args, Progress progress) {
            return "ok";
        }
    }

    @Before
    public void setUp() {
        ctx = RuntimeEnvironment.getApplication();
        registry = ToolRegistry.getInstance();
        McpServerManager.connectedTools().clear();
        registry.unregister("ns_alpha");
        registry.unregister("ns_beta");
        ctx.getSharedPreferences("mcp_servers", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @After
    public void tearDown() {
        McpServerManager.connectedTools().clear();
        registry.unregister("ns_alpha");
        registry.unregister("ns_beta");
    }

    @Test
    public void connectedTools_isProcessWideAcrossInstances() {
        McpServerManager a = new McpServerManager(ctx);
        McpServerManager b = new McpServerManager(ctx);

        // a 实例建连：工具注册进全局注册表，连接记进进程级共享表
        registry.register(new FakeTool("ns_alpha"));
        McpServerManager.connectedTools().put("cfg-1", Collections.singletonList("ns_alpha"));
        assertEquals(1, McpServerManager.connectedTools().size());

        // b 实例删除同一个配置 id → 必须能注销 a 建的工具
        McpServerManager.McpServerConfig cfg = new McpServerManager.McpServerConfig(
                "cfg-1", "服务器A", "https://example.com/mcp", "Authorization", "token", "ns");
        b.save(cfg);
        b.remove("cfg-1");

        assertNull("删除后全局注册表里不应残留工具", registry.find("ns_alpha"));
        assertTrue("连接表应清空", McpServerManager.connectedTools().isEmpty());
        assertEquals(0, a.list().size());
    }

    @Test
    public void remove_unregistersOnlyTheTargetConfig() {
        registry.register(new FakeTool("ns_alpha"));
        registry.register(new FakeTool("ns_beta"));
        McpServerManager.connectedTools().put("cfg-1", Collections.singletonList("ns_alpha"));
        McpServerManager.connectedTools().put("cfg-2", Collections.singletonList("ns_beta"));

        McpServerManager m = new McpServerManager(ctx);
        m.save(new McpServerManager.McpServerConfig(
                "cfg-1", "A", "https://a/mcp", null, "", "ns"));
        m.remove("cfg-1");

        assertNull(registry.find("ns_alpha"));
        assertTrue("其它配置的工具不该被动到", registry.find("ns_beta") != null);
        assertEquals(1, McpServerManager.connectedTools().size());
    }

    @Test
    public void disconnectAll_clearsRegistryAndTable() {
        registry.register(new FakeTool("ns_alpha"));
        registry.register(new FakeTool("ns_beta"));
        McpServerManager.connectedTools().put("cfg-1",
                Arrays.asList("ns_alpha", "ns_beta"));

        McpServerManager m = new McpServerManager(ctx);
        m.disconnectAll();

        assertNull(registry.find("ns_alpha"));
        assertNull(registry.find("ns_beta"));
        assertTrue(McpServerManager.connectedTools().isEmpty());
    }

    @Test
    public void saveAndList_roundTripsConfig() {
        McpServerManager m = new McpServerManager(ctx);
        m.save(new McpServerManager.McpServerConfig(
                "cfg-9", "服务器Z", "https://z/mcp", "X-Key", "v", "nsz"));

        assertEquals(1, m.list().size());
        McpServerManager.McpServerConfig got = m.list().get(0);
        assertEquals("cfg-9", got.id);
        assertEquals("服务器Z", got.name);
        assertEquals("https://z/mcp", got.endpoint);
        assertEquals("nsz", got.namespace);
    }
}
