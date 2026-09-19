package com.digitallife.harness.plugin;

import com.digitallife.harness.ContextCompressor;
import com.digitallife.harness.HarnessContext;
import com.digitallife.harness.Plugin;

/**
 * 把上下文压缩器挂到 ctx.compressor，AgentLoop 在 deriveMessages 后调用。
 */
public final class CompressPlugin implements Plugin {
    @Override
    public String id() {
        return "dsh-compress";
    }

    @Override
    public void activate(HarnessContext ctx) {
        ctx.provide("compressor", new ContextCompressor());
    }
}
