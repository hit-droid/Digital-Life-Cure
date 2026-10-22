package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Agent 工作台「删除」闭环依赖的两条 CareTools 工具契约（v1.167.0）。
 *
 * 工作台行上的「删除」不自己写 prefs，而是复用 {@code delete_workflow} /
 * {@code remove_scheduled_task}——与对话里同一条语义。这里锁住：删已存在的会真的从
 * prefs 移除、删不存在的给出可读提示，避免界面「点了没反应」或「删了还在」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AgentWorkbenchToolsTest {

    private static final String PREFS_WORKFLOW = "care_workflows";
    private static final String PREFS_SCHEDULE = "care_schedules";

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.getSharedPreferences(PREFS_WORKFLOW, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE).edit().clear().commit();
        ctx.getSharedPreferences("care_runs", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void deleteWorkflowRemovesItFromPrefs() throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_WORKFLOW, Context.MODE_PRIVATE);
        JSONArray steps = new JSONArray();
        steps.put(new JSONObject().put("tool", "play_motion"));
        sp.edit().putString("晨间简报", new JSONObject().put("steps", steps).toString()).commit();

        String res = new CareTools(ctx).execute("delete_workflow",
                new JSONObject().put("name", "晨间简报"));

        assertTrue("应提示已删除，实际：" + res, res.contains("已删除"));
        assertFalse("删除后不应再存在于 prefs", sp.contains("晨间简报"));
    }

    @Test
    public void deleteMissingWorkflowReportsNotFound() throws Exception {
        String res = new CareTools(ctx).execute("delete_workflow",
                new JSONObject().put("name", "不存在的工作流"));
        assertTrue("删不存在的应给出可读提示，实际：" + res, res.contains("未找到"));
    }

    @Test
    public void removeScheduledTaskRemovesItFromPrefs() throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE);
        JSONObject task = new JSONObject()
                .put("cronExpr", "0 9 * * *")
                .put("workflowName", "晨间简报");
        sp.edit().putString("每天九点", task.toString()).commit();

        String res = new CareTools(ctx).execute("remove_scheduled_task",
                new JSONObject().put("name", "每天九点"));

        assertTrue("应提示已删除，实际：" + res, res.contains("已删除"));
        assertFalse("删除后不应再存在于 prefs", sp.contains("每天九点"));
    }

    @Test
    public void removeMissingScheduledTaskReportsNotFound() throws Exception {
        String res = new CareTools(ctx).execute("remove_scheduled_task",
                new JSONObject().put("name", "不存在的任务"));
        assertTrue("删不存在的应给出可读提示，实际：" + res, res.contains("未找到"));
    }

    @Test
    public void addScheduledTaskWritesCronAndWorkflow() throws Exception {
        String res = new CareTools(ctx).execute("add_scheduled_task",
                new JSONObject().put("name", "每日晨报")
                        .put("cronExpr", "0 9 * * *")
                        .put("workflowName", "晨间简报"));
        assertTrue("应提示已添加，实际：" + res, res.contains("已添加"));

        SharedPreferences sp = ctx.getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE);
        assertTrue("新建后应存在于 prefs", sp.contains("每日晨报"));
        JSONObject saved = new JSONObject(sp.getString("每日晨报", "{}"));
        assertEquals("0 9 * * *", saved.optString("cronExpr"));
        assertEquals("晨间简报", saved.optString("workflowName"));
    }

    @Test
    public void addScheduledTaskMissingFieldsIsRejected() throws Exception {
        String res = new CareTools(ctx).execute("add_scheduled_task",
                new JSONObject().put("name", "缺字段").put("cronExpr", ""));
        assertTrue("缺字段应被拒绝并给出可读提示，实际：" + res, res.contains("请填写"));
        assertFalse("拒绝时不应落盘",
                ctx.getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE).contains("缺字段"));
    }

    // ============ update_scheduled_task（v1.181.0 工作台「编辑」复用） ============

    @Test
    public void updateScheduledTaskChangesCronAndKeepsEnabledAndTarget() throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE);
        // 一条被暂停的「连招」任务
        sp.edit().putString("早安连招", new JSONObject()
                .put("cronExpr", "0 9 * * *")
                .put("kind", "combo")
                .put("modelDir", "chitose")
                .put("comboName", "打招呼")
                .put("enabled", false).toString()).commit();

        String res = new CareTools(ctx).execute("update_scheduled_task",
                new JSONObject().put("name", "早安连招").put("cronExpr", "0 10 * * *"));

        assertTrue("应提示已更新，实际：" + res, res.contains("已更新"));
        JSONObject saved = new JSONObject(sp.getString("早安连招", "{}"));
        assertEquals("0 10 * * *", saved.optString("cronExpr"));
        assertEquals("combo", saved.optString("kind"));          // 目标类型不变
        assertEquals("打招呼", saved.optString("comboName"));     // 目标不变
        assertEquals(false, saved.optBoolean("enabled", true));   // 改计划不该唤醒暂停的任务
    }

    @Test
    public void updateScheduledTaskRenamesAndKeepsTarget() throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE);
        sp.edit().putString("旧名", new JSONObject()
                .put("cronExpr", "0 9 * * *").put("workflowName", "晨间简报").toString()).commit();

        String res = new CareTools(ctx).execute("update_scheduled_task",
                new JSONObject().put("name", "旧名").put("cronExpr", "0 9 * * *").put("newName", "新名"));

        assertTrue("应提示已更新，实际：" + res, res.contains("已更新"));
        assertTrue("新名应存在", sp.contains("新名"));
        assertFalse("旧名应移除", sp.contains("旧名"));
        assertEquals("晨间简报",
                new JSONObject(sp.getString("新名", "{}")).optString("workflowName"));
    }

    @Test
    public void updateScheduledTaskRenameConflictIsRejected() throws Exception {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE);
        sp.edit()
                .putString("A", new JSONObject().put("cronExpr", "0 9 * * *").put("workflowName", "w").toString())
                .putString("B", new JSONObject().put("cronExpr", "0 8 * * *").put("workflowName", "w").toString())
                .commit();

        String res = new CareTools(ctx).execute("update_scheduled_task",
                new JSONObject().put("name", "A").put("cronExpr", "0 7 * * *").put("newName", "B"));

        assertTrue("撞名应被拒绝，实际：" + res, res.contains("已存在"));
        // 两条都保持原样，不能被改坏
        assertEquals("0 9 * * *", new JSONObject(sp.getString("A", "{}")).optString("cronExpr"));
        assertEquals("0 8 * * *", new JSONObject(sp.getString("B", "{}")).optString("cronExpr"));
    }

    @Test
    public void updateMissingScheduledTaskReportsNotFound() throws Exception {
        String res = new CareTools(ctx).execute("update_scheduled_task",
                new JSONObject().put("name", "不存在").put("cronExpr", "0 9 * * *"));
        assertTrue("改不存在的应给出可读提示，实际：" + res, res.contains("未找到"));
    }

    // ============ rename_workflow（v1.182.0 工作台「改名」复用） ============

    private void putWorkflow(String name, String... tools) throws Exception {
        JSONArray steps = new JSONArray();
        for (String t : tools) steps.put(new JSONObject().put("tool", t));
        ctx.getSharedPreferences(PREFS_WORKFLOW, Context.MODE_PRIVATE).edit()
                .putString(name, new JSONObject().put("name", name).put("steps", steps).toString())
                .commit();
    }

    @Test
    public void renameWorkflowMovesKeyAndKeepsSteps() throws Exception {
        putWorkflow("晨间简报", "get_calendar_today", "play_motion");

        String res = new CareTools(ctx).execute("rename_workflow",
                new JSONObject().put("name", "晨间简报").put("newName", "每日晨报"));

        assertTrue("应提示已改名，实际：" + res, res.contains("改名为"));
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_WORKFLOW, Context.MODE_PRIVATE);
        assertFalse("旧名应移除", sp.contains("晨间简报"));
        JSONObject wf = new JSONObject(sp.getString("每日晨报", "{}"));
        assertEquals("每日晨报", wf.optString("name"));
        assertEquals("步骤不能丢", 2, wf.optJSONArray("steps").length());
    }

    @Test
    public void renameWorkflowCascadesToSchedulesAndKeepsTheirConfig() throws Exception {
        putWorkflow("晨间简报", "get_calendar_today");
        SharedPreferences sched = ctx.getSharedPreferences(PREFS_SCHEDULE, Context.MODE_PRIVATE);
        sched.edit()
                .putString("每天九点", new JSONObject()
                        .put("cronExpr", "0 9 * * *").put("workflowName", "晨间简报")
                        .put("enabled", false).toString())
                // 连招任务不引用工作流，不能被误改
                .putString("早安连招", new JSONObject()
                        .put("cronExpr", "0 8 * * *").put("kind", "combo")
                        .put("modelDir", "chitose").put("comboName", "打招呼")
                        .put("workflowName", "晨间简报") // 即便脏数据撞名也不动
                        .toString())
                .commit();

        String res = new CareTools(ctx).execute("rename_workflow",
                new JSONObject().put("name", "晨间简报").put("newName", "每日晨报"));

        assertTrue("应提示同步更新了定时任务，实际：" + res, res.contains("同步更新 1 个定时任务"));
        JSONObject wfTask = new JSONObject(sched.getString("每天九点", "{}"));
        assertEquals("每日晨报", wfTask.optString("workflowName"));
        assertEquals("0 9 * * *", wfTask.optString("cronExpr"));
        assertEquals("暂停状态不能被改名唤醒", false, wfTask.optBoolean("enabled", true));
        assertEquals("连招任务应原样保留", "晨间简报",
                new JSONObject(sched.getString("早安连招", "{}")).optString("workflowName"));
    }

    @Test
    public void renameWorkflowMigratesRunLog() throws Exception {
        putWorkflow("晨间简报", "get_calendar_today");
        RunLog.record(ctx, RunLog.workflowKey("晨间简报"), true, "ok");

        new CareTools(ctx).execute("rename_workflow",
                new JSONObject().put("name", "晨间简报").put("newName", "每日晨报"));

        assertTrue("新名下应能看到历史", RunLog.last(ctx, RunLog.workflowKey("每日晨报")) != null);
        assertEquals("旧名下不应残留",
                null, RunLog.last(ctx, RunLog.workflowKey("晨间简报")));
    }

    @Test
    public void renameWorkflowConflictRejectedAndNothingChanges() throws Exception {
        putWorkflow("A", "tool_x");
        putWorkflow("B", "tool_y");

        String res = new CareTools(ctx).execute("rename_workflow",
                new JSONObject().put("name", "A").put("newName", "B"));

        assertTrue("撞名应被拒绝，实际：" + res, res.contains("已存在"));
        SharedPreferences sp = ctx.getSharedPreferences(PREFS_WORKFLOW, Context.MODE_PRIVATE);
        assertTrue("A 应仍在", sp.contains("A"));
        assertEquals("B 不能被覆盖", 1,
                new JSONObject(sp.getString("B", "{}")).optJSONArray("steps").length());
    }

    @Test
    public void renameMissingWorkflowReportsNotFound() throws Exception {
        String res = new CareTools(ctx).execute("rename_workflow",
                new JSONObject().put("name", "不存在").put("newName", "新名"));
        assertTrue("改不存在的应给出可读提示，实际：" + res, res.contains("未找到"));
    }

    @Test
    public void renameWorkflowRequiresNewName() throws Exception {
        putWorkflow("晨间简报", "tool_x");
        String res = new CareTools(ctx).execute("rename_workflow",
                new JSONObject().put("name", "晨间简报").put("newName", "  "));
        assertTrue("缺新名应给出可读提示，实际：" + res, res.contains("新的工作流名称"));
        assertTrue("原工作流不能被删",
                ctx.getSharedPreferences(PREFS_WORKFLOW, Context.MODE_PRIVATE).contains("晨间简报"));
    }
}
