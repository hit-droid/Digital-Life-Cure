package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * 执行记录持久化单测（v1.173.0）：锁「最新在前」「每个 key 只留 20 条」「不同 key 隔离」
 * 「清空」「坏数据不抛」「摘要写入时已折叠」几个契约。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class RunLogStoreTest {

    private static final long T0 = 1_700_000_000_000L;

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void reset() {
        ctx().getSharedPreferences("care_runs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void record_latestFirstAndSummarized() {
        String key = RunLog.workflowKey("晨报");
        RunLog.record(ctx(), key, true, "第一次\n成功", T0);
        RunLog.record(ctx(), key, false, "第二次\n失败", T0 + 1000);

        RunLog.Record last = RunLog.last(ctx(), key);
        assertNotNull(last);
        assertTrue(last.at == T0 + 1000);
        assertEquals(false, last.ok);
        assertEquals("第二次 失败", last.summary); // 换行被折叠

        List<RunLog.Record> all = RunLog.recent(ctx(), key);
        assertEquals(2, all.size());
        assertTrue(all.get(1).ok);
    }

    @Test
    public void record_capsToMaxKept() {
        String key = RunLog.scheduleKey("巡检");
        for (int i = 0; i < RunLog.MAX_KEPT + 5; i++) {
            RunLog.record(ctx(), key, true, "第" + i + "次", T0 + i);
        }
        List<RunLog.Record> all = RunLog.recent(ctx(), key);
        assertEquals(RunLog.MAX_KEPT, all.size());
        // 最新在前：最后写入的第 24 次应排在首位
        assertTrue(all.get(0).at == T0 + (RunLog.MAX_KEPT + 4));
    }

    @Test
    public void keysAreIsolated() {
        RunLog.record(ctx(), RunLog.workflowKey("A"), true, "ok", T0);
        RunLog.record(ctx(), RunLog.scheduleKey("A"), false, "fail", T0);

        assertTrue(RunLog.last(ctx(), RunLog.workflowKey("A")).ok);
        assertEquals(false, RunLog.last(ctx(), RunLog.scheduleKey("A")).ok);
        assertNull(RunLog.last(ctx(), RunLog.workflowKey("不存在")));
    }

    @Test
    public void clear_removesKey() {
        String key = RunLog.workflowKey("A");
        RunLog.record(ctx(), key, true, "ok", T0);
        RunLog.clear(ctx(), key);
        assertNull(RunLog.last(ctx(), key));
    }

    @Test
    public void fromJson_skipsGarbage() throws Exception {
        JSONArray arr = new JSONArray()
                .put("not-an-object")
                .put(new JSONObject().put("at", T0).put("ok", true).put("summary", "ok"));
        List<RunLog.Record> out = RunLog.fromJson(arr);
        assertEquals(1, out.size());
        assertEquals("ok", out.get(0).summary);
    }

    @Test
    public void lastLine_nullWhenEmpty_thenFormats() {
        long now = T0 + 60_000;
        assertNull(RunLog.lastLine(ctx(), RunLog.workflowKey("空"), now));
        RunLog.record(ctx(), RunLog.workflowKey("空"), true, "搞定", T0);
        String line = RunLog.lastLine(ctx(), RunLog.workflowKey("空"), now);
        assertTrue(line.startsWith("✅ 上次运行 1 分钟前"));
        assertTrue(line.endsWith("搞定"));
    }
}
