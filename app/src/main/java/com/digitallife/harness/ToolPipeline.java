package com.digitallife.harness;

import com.digitallife.brain.Tools;

import org.json.JSONObject;

public final class ToolPipeline {

    public static final class Call {
        public String name;
        public JSONObject args;
        public String toolCallId;
        public String result;
        public String error;
        public boolean rejected;

        public Call(String name, JSONObject args, String toolCallId) {
            this.name = name;
            this.args = args != null ? args : new JSONObject();
            this.toolCallId = toolCallId;
        }
    }

    /**
     * v1.141.0（#40）：危险工具的审批回调。
     * <p>在工具执行入口（{@link #execute}）被调用，**运行在工具循环的后台线程上**，
     * 允许阻塞等待用户决定；实现方需自行把 UI 交互 post 到主线程。</p>
     */
    public interface Approver {
        /**
         * @param toolName 工具名
         * @param args     原始参数（仅供执行方使用；展示文案请用 summary）
         * @param summary  已脱敏的确认文案
         * @return 用户选择；返回 null 视为拒绝
         */
        ToolApprovalPolicy.Outcome request(String toolName, JSONObject args, String summary);
    }

    private final EventBus events;
    private Tools host;
    /** v1.141.0（#40）：null 时不做审批（旧行为不变） */
    private ToolApprovalPolicy approvalPolicy;
    private Approver approver;

    public ToolPipeline(EventBus events) {
        this.events = events;
    }

    public void setHost(Tools host) {
        this.host = host;
    }

    public Tools host() {
        return host;
    }

    /** v1.141.0（#40）：装配危险工具审批；policy 为 null 时整条链路停用 */
    public void setApproval(ToolApprovalPolicy policy, Approver pApprover) {
        this.approvalPolicy = policy;
        this.approver = pApprover;
    }

    /** v1.141.0（#40）：供 AgentLoop 在新一轮开始时清空「本轮拒绝」记账 */
    public ToolApprovalPolicy approvalPolicy() {
        return approvalPolicy;
    }

    public Call execute(String name, JSONObject args, String toolCallId) {
        Call call = new Call(name, args, toolCallId);
        if (events != null) {
            call = events.waterfall("tools/pre-execute", call);
        }
        // v1.141.0（#40）：唯一的审批埋点。禁用策略（waterfall）已先短路，
        // 这里只处理「需要用户点头」的危险工具。
        applyApproval(call);
        if (call.rejected) {
            if (call.error == null) call.error = "工具调用被拒绝";
            if (events != null) events.waterfall("tools/post-execute", call);
            return call;
        }
        if (host == null) {
            call.error = "工具宿主未就绪";
            if (events != null) events.waterfall("tools/post-execute", call);
            return call;
        }
        if (events != null) call = events.waterfall("tools/execute", call);
        if (call.result == null && call.error == null) {
            try {
                call.result = host.executeSync(call.name, call.args);
            } catch (Exception e) {
                call.error = com.digitallife.ui.UiKit.safeMsg(e);
            }
        }
        if (events != null) call = events.waterfall("tools/post-execute", call);
        return call;
    }

    /**
     * v1.141.0（#40）：危险工具审批。
     *
     * <p>未装配策略、或工具不在危险集合内 → 原样放行（只读工具零打扰）。
     * 本轮已拒绝过的同一工具直接短路，不再打扰用户，杜绝模型重复试探造成的死循环。</p>
     */
    private void applyApproval(Call call) {
        if (call == null || call.rejected || approvalPolicy == null) return;
        if (!ToolApprovalPolicy.requiresApproval(call.name)) return;
        if (approvalPolicy.isDeniedThisTurn(call.name)) {
            call.rejected = true;
            call.error = "用户已拒绝，本轮不再执行：" + call.name;
            return;
        }
        if (approvalPolicy.isAllowedForSession(call.name)) return;

        String summary = ToolApprovalPolicy.summary(call.name, call.args);
        ToolApprovalPolicy.Outcome outcome = approver == null
                ? ToolApprovalPolicy.Outcome.DENY
                : approver.request(call.name, call.args, summary);
        if (outcome == ToolApprovalPolicy.Outcome.ALLOW_SESSION) {
            approvalPolicy.allowForSession(call.name);
            return;
        }
        if (outcome == ToolApprovalPolicy.Outcome.ALLOW_ONCE) return;

        // null / DENY 一律按拒绝处理，并记入本轮
        approvalPolicy.markDeniedThisTurn(call.name);
        call.rejected = true;
        call.error = "用户拒绝执行该工具：" + call.name;
    }
}
