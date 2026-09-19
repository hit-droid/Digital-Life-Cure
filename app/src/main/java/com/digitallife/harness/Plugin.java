package com.digitallife.harness;

/**
 * Cordis 风格插件：向共享 {@link HarnessContext} 贡献服务、事件与可逆副作用。
 * 卸载时 {@link #deactivate(HarnessContext)} 与 context 上登记的 effect 一并撤销。
 */
public interface Plugin {
    String id();

    void activate(HarnessContext ctx);

    default void deactivate(HarnessContext ctx) {
    }
}
