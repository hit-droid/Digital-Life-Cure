package com.digitallife.tools;

import android.content.Context;

import com.digitallife.brain.Tools;
import com.digitallife.harness.EventBus;
import com.digitallife.harness.ToolPipeline;
import com.digitallife.ui.ToolMarketActivity;

/**
 * 工具把关与审计的标准装配。
 *
 * <p>禁用策略在 {@code tools/pre-execute} 上短路，使用日志在
 * {@code tools/post-execute} 上记录。这段策略原先散在各大脑里，收敛到流水线后
 * 由本类统一装配，避免每个大脑各写一份（曾经因为 AgentBrain 删除而让日志断流）。
 */
public final class ToolGovernance {

    private ToolGovernance() {
    }

    /**
     * 装出一条带禁用策略与使用日志的流水线。
     *
     * @param app 用于读取禁用名单与写日志；为 null 时跳过策略（测试用）
     * @param host 工具宿主
     */
    public static ToolPipeline install(Context app, Tools host) {
        final Context ctx = app != null ? app.getApplicationContext() : null;
        EventBus bus = new EventBus();

        bus.addWaterfall("tools/pre-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call,
                                            EventBus.Next<ToolPipeline.Call> next) {
                if (call == null) return null;
                if (ctx != null && ToolMarketActivity.isDisabled(ctx, call.name)) {
                    call.rejected = true;
                    call.error = "工具已被用户禁用：" + call.name;
                    return call;
                }
                return next.apply(call);
            }
        });

        bus.addWaterfall("tools/post-execute", new EventBus.Waterfall<ToolPipeline.Call>() {
            @Override
            public ToolPipeline.Call handle(ToolPipeline.Call call,
                                            EventBus.Next<ToolPipeline.Call> next) {
                if (call != null && ctx != null) {
                    record(ctx, call);
                }
                return next.apply(call);
            }
        });

        ToolPipeline pipeline = new ToolPipeline(bus);
        pipeline.setHost(host);
        return pipeline;
    }

    /** 写一条使用日志；失败不影响工具调用本身 */
    static void record(Context ctx, ToolPipeline.Call call) {
        try {
            ToolUsageLog log = ToolUsageLog.getInstance(ctx);
            ToolUsageLog.Entry e = new ToolUsageLog.Entry();
            e.timestamp = System.currentTimeMillis();
            e.toolName = call.name;
            e.args = call.args != null ? call.args.toString() : "";
            e.result = call.result;
            e.error = call.error;
            e.durationMs = 0;
            log.add(e);
        } catch (Exception ignored) {
        }
    }
}
