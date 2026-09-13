package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.digitallife.brain.Tools;
import com.digitallife.harness.ToolPipeline;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * CareToolHost 的契约：把护理工具表适配成 harness Tools seam 时，
 * 必须保住原有语义——schema 含 play_motion、结果超长截断、play_motion 走执行层。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CareToolHostTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    private static boolean hasTool(JSONArray schemas, String name) {
        for (int i = 0; i < schemas.length(); i++) {
            JSONObject t = schemas.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            if (fn != null && name.equals(fn.optString("name"))) return true;
        }
        return false;
    }

    @Test
    public void schemaIncludesPlayMotion() {
        CareToolHost host = new CareToolHost(new CareTools(ctx), null);
        assertTrue("play_motion 必须出现在护理工具表里",
                hasTool(host.toJsonArray(), "play_motion"));
    }

    @Test
    public void schemaKeepsCareTools() {
        CareToolHost host = new CareToolHost(new CareTools(ctx), null);
        JSONArray schemas = host.toJsonArray();
        assertTrue(hasTool(schemas, "list_models"));
        assertTrue(hasTool(schemas, "analyze_model"));
        assertTrue("追加 play_motion 不应挤掉原有工具", schemas.length() > 1);
    }

    @Test
    public void describeListsEveryTool() {
        CareToolHost host = new CareToolHost(new CareTools(ctx), null);
        String desc = host.describe();
        assertTrue(desc.contains("play_motion"));
        assertTrue(desc.contains("list_models"));
    }

    @Test
    public void nullCareToolsStillExposesPlayMotion() {
        CareToolHost host = new CareToolHost(null, null);
        assertTrue(hasTool(host.toJsonArray(), "play_motion"));
    }

    @Test
    public void missingCareToolsReportsError() {
        CareToolHost host = new CareToolHost(null, null);
        final String[] err = new String[1];
        host.execute("list_models", new JSONObject(), (n, a, r, e) -> err[0] = e);
        assertNotNull(err[0]);
    }

    @Test
    public void playMotionWithoutExecutorReportsError() {
        CareToolHost host = new CareToolHost(new CareTools(ctx), null);
        JSONObject args = new JSONObject();
        try {
            args.put("action", "wave");
        } catch (Exception ignored) {
        }
        final String[] err = new String[1];
        host.execute("play_motion", args, (n, a, r, e) -> err[0] = e);
        assertNotNull("没有执行层时应报错而不是静默成功", err[0]);
    }

    @Test
    public void unknownToolIsReportedByCareTools() {
        CareToolHost host = new CareToolHost(new CareTools(ctx), null);
        final String[] res = new String[1];
        host.execute("no_such_tool_xyz", new JSONObject(), (n, a, r, e) -> res[0] = r);
        assertNotNull(res[0]);
        assertTrue("未知工具应返回可读提示，实际：" + res[0],
                res[0].contains("未知") || res[0].contains("不支持") || res[0].contains("❌"));
    }

    @Test
    public void longResultFromCareToolIsTruncated() {
        // 截断发生在 CareToolHost.truncate 里：护理工具（如 analyze_model）结果可能极长，
        // 必须截断后才喂给模型，否则撑爆上下文
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) sb.append("0123456789");  // 5000 字符
        String out = CareToolHost.truncateForTest(sb.toString());
        assertTrue("超长结果必须被截断", out.length() < 3000);
        assertTrue("截断后应有提示", out.contains("已截断"));
        assertEquals("截断点应为 2000", 2000, out.indexOf("\n...（结果已截断）"));
    }

    @Test
    public void shortResultIsNotTruncated() {
        assertEquals("短结果应原样返回", "短结果",
                CareToolHost.truncateForTest("短结果"));
        assertNull("null 应保持 null", CareToolHost.truncateForTest(null));
    }

    @Test
    public void exactly2000CharsIsNotTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) sb.append("0123456789");  // 恰好 2000
        String out = CareToolHost.truncateForTest(sb.toString());
        assertEquals("边界值不应截断", 2000, out.length());
    }

    @Test
    public void hostWorksThroughToolPipeline() {
        // 用纯 JVM 可跑的工具验证流水线接线；护理真工具（analyze_model 等）依赖 native 库，
        // 在 JVM 测试环境不可用，由真机验证覆盖
        CareToolHost host = new CareToolHost(null, null);
        ToolPipeline pipeline = new ToolPipeline(new com.digitallife.harness.EventBus());
        pipeline.setHost(host);

        ToolPipeline.Call call = pipeline.execute("play_motion", new JSONObject(), "call_1");
        assertNotNull("流水线应把执行结果带回", call.error);
        assertTrue(call.error.contains("执行层不可用"));
    }

    @Test
    public void pipelineAuditsCareToolCalls() {
        final java.util.List<String> audit = new java.util.ArrayList<>();
        com.digitallife.harness.EventBus bus = new com.digitallife.harness.EventBus();
        bus.addWaterfall("tools/post-execute",
                new com.digitallife.harness.EventBus.Waterfall<ToolPipeline.Call>() {
                    @Override
                    public ToolPipeline.Call handle(ToolPipeline.Call call,
                                                    com.digitallife.harness.EventBus.Next<ToolPipeline.Call> next) {
                        if (call != null) audit.add(call.name);
                        return next.apply(call);
                    }
                });
        ToolPipeline pipeline = new ToolPipeline(bus);
        pipeline.setHost(new CareToolHost(null, null));

        pipeline.execute("play_motion", new JSONObject(), "c1");
        assertEquals(1, audit.size());
        assertEquals("play_motion", audit.get(0));
    }

    @Test
    public void careToolsIsAccessible() {
        CareTools care = new CareTools(ctx);
        CareToolHost host = new CareToolHost(care, null);
        assertTrue(care == host.care());
    }

    @Test
    public void schemaEntriesAreWellFormed() {
        CareToolHost host = new CareToolHost(new CareTools(ctx), null);
        JSONArray schemas = host.toJsonArray();
        assertTrue(schemas.length() > 0);
        for (int i = 0; i < schemas.length(); i++) {
            JSONObject t = schemas.optJSONObject(i);
            assertNotNull(t);
            assertEquals("function", t.optString("type"));
            JSONObject fn = t.optJSONObject("function");
            assertNotNull("每个 schema 必须有 function 字段", fn);
            assertFalse("工具名不可为空", fn.optString("name").isEmpty());
        }
    }
}
