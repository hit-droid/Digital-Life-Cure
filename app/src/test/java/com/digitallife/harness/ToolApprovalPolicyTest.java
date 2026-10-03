package com.digitallife.harness;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 危险工具审批策略回归（Issue #40）。
 *
 * <p>三件容错不能出错：只读工具必须零打扰（否则每次都弹窗）、危险工具必须拦、
 * 文案绝不能把密钥/令牌回显到确认卡片上。</p>
 */
public class ToolApprovalPolicyTest {

    // ---------- 危险集合判定 ----------

    @Test
    public void requiresApproval_fixedDangerousTools() {
        assertTrue(ToolApprovalPolicy.requiresApproval("restore_data"));
        assertTrue(ToolApprovalPolicy.requiresApproval("open_app"));
        assertTrue(ToolApprovalPolicy.requiresApproval("clipboard_write"));
        assertTrue(ToolApprovalPolicy.requiresApproval("send_notification"));
        assertTrue(ToolApprovalPolicy.requiresApproval("memory_forget"));
    }

    @Test
    public void requiresApproval_dangerousPrefixes() {
        assertTrue(ToolApprovalPolicy.requiresApproval("delete_note"));
        assertTrue(ToolApprovalPolicy.requiresApproval("delete_anything"));
        assertTrue(ToolApprovalPolicy.requiresApproval("forget_person"));
        assertTrue(ToolApprovalPolicy.requiresApproval("remove_backup"));
    }

    @Test
    public void requiresApproval_readOnlyTools_areNotApproved() {
        // 只读 / 无副作用工具必须零打扰
        assertFalse(ToolApprovalPolicy.requiresApproval("get_battery"));
        assertFalse(ToolApprovalPolicy.requiresApproval("get_weather_hint"));
        assertFalse(ToolApprovalPolicy.requiresApproval("web_search"));
        assertFalse(ToolApprovalPolicy.requiresApproval("memory_search"));
        assertFalse(ToolApprovalPolicy.requiresApproval("list_backups"));
        assertFalse(ToolApprovalPolicy.requiresApproval("unknown_tool"));
    }

    @Test
    public void requiresApproval_nullOrEmpty_isFalse() {
        assertFalse(ToolApprovalPolicy.requiresApproval(null));
        assertFalse(ToolApprovalPolicy.requiresApproval(""));
    }

    // ---------- 会话放行集 ----------

    @Test
    public void allowForSession_onlyAffectsThatTool() {
        ToolApprovalPolicy p = new ToolApprovalPolicy();
        assertFalse(p.isAllowedForSession("open_app"));
        p.allowForSession("open_app");
        assertTrue(p.isAllowedForSession("open_app"));
        assertFalse("不应连带放行其它工具", p.isAllowedForSession("restore_data"));
    }

    // ---------- 本轮拒绝记账 ----------

    @Test
    public void deniedThisTurn_resetOnBeginTurn() {
        ToolApprovalPolicy p = new ToolApprovalPolicy();
        assertFalse(p.isDeniedThisTurn("open_app"));
        p.markDeniedThisTurn("open_app");
        assertTrue(p.isDeniedThisTurn("open_app"));
        p.beginTurn();
        assertFalse(p.isDeniedThisTurn("open_app"));
    }

    @Test
    public void beginTurn_keepsSessionAllowance() {
        ToolApprovalPolicy p = new ToolApprovalPolicy();
        p.allowForSession("open_app");
        p.beginTurn();
        assertTrue("新一轮不应清掉会话放行", p.isAllowedForSession("open_app"));
    }

    // ---------- 脱敏文案 ----------

    @Test
    public void summary_redactsSensitiveValues() throws Exception {
        JSONObject args = new JSONObject();
        args.put("api_key", "sk-super-secret-value");
        args.put("token", "ghp_deadbeef");
        args.put("query", "北京天气");
        String s = ToolApprovalPolicy.summary("restore_data", args);
        assertFalse("密钥值绝不能回显：" + s, s.contains("sk-super-secret-value"));
        assertFalse("令牌值绝不能回显：" + s, s.contains("ghp_deadbeef"));
        assertTrue(s.contains("api_key=***"));
        assertTrue(s.contains("token=***"));
        assertTrue("普通参数照常展示：" + s, s.contains("query=北京天气"));
        assertTrue(s.contains("restore_data"));
    }

    @Test
    public void summary_noArgs_onlyToolName() {
        String s = ToolApprovalPolicy.summary("open_app", new JSONObject());
        assertTrue(s.contains("open_app"));
        assertFalse("无参数时不该出现参数段：" + s, s.contains("参数"));
    }

    @Test
    public void summary_nullArgs_onlyToolName() {
        assertEquals("工具：open_app", ToolApprovalPolicy.summary("open_app", null));
    }

    @Test
    public void summary_clipsLongValue() throws Exception {
        JSONObject args = new JSONObject();
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) longText.append('x');
        args.put("content", longText.toString());
        String s = ToolApprovalPolicy.summary("clipboard_write", args);
        assertTrue("超长值应被截断：" + s.length(), s.contains("\u2026"));
        assertTrue(s.length() < 140);
    }
}
