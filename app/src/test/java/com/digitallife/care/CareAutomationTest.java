package com.digitallife.care;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * CareAutomation 的定时任务启用判定单测（v1.166.0）。
 *
 * <p>工作台新增「暂停 / 启用」后，{@code enabled=false} 的任务必须不被触发；同时要兼容
 * 加开关之前落盘的旧数据（没有 {@code enabled} 字段 → 视为启用），否则历史定时任务会集体
 * 停摆。这里把这两条锁死。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CareAutomationTest {

    @Test
    public void enabled_absent_defaultsTrue_legacyCompat() throws Exception {
        JSONObject task = new JSONObject()
                .put("cronExpr", "0 9 * * *")
                .put("workflowName", "晨间简报");
        assertTrue(CareAutomation.isTaskEnabled(task));
    }

    @Test
    public void enabled_explicitTrue() throws Exception {
        assertTrue(CareAutomation.isTaskEnabled(new JSONObject().put("enabled", true)));
    }

    @Test
    public void enabled_explicitFalse_paused() throws Exception {
        assertFalse(CareAutomation.isTaskEnabled(new JSONObject().put("enabled", false)));
    }

    @Test
    public void enabled_nullTask_treatedEnabled() {
        assertTrue(CareAutomation.isTaskEnabled(null));
    }

    // ==================== 定时连招任务判定（v1.178.0） ====================

    @Test
    public void kind_absent_isWorkflow_legacyCompat() throws Exception {
        JSONObject task = new JSONObject()
                .put("cronExpr", "0 9 * * *")
                .put("workflowName", "晨间简报");
        assertFalse(CareAutomation.isComboTask(task));
    }

    @Test
    public void kind_combo_isComboTask() throws Exception {
        JSONObject task = new JSONObject()
                .put("cronExpr", "0 20 * * *")
                .put("kind", "combo")
                .put("modelDir", "demo")
                .put("comboName", "打招呼");
        assertTrue(CareAutomation.isComboTask(task));
    }

    @Test
    public void kind_unknownValue_fallsBackToWorkflow() throws Exception {
        assertFalse(CareAutomation.isComboTask(new JSONObject().put("kind", "whatever")));
        assertFalse(CareAutomation.isComboTask(null));
    }
}
