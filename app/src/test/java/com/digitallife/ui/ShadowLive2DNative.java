package com.digitallife.ui;

import com.digitallife.render.Live2DNative;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/**
 * 截图自查专用 shadow：Robolectric 里没有 libmaidendungeon.so，任何 native 调用都会抛
 * UnsatisfiedLinkError，导致主壳（SettingsTabView / ContactsTabView 构造期就会数模型）
 * 整个 buildUi 中断、截不到图。
 *
 * <p>只把「纯查询」类 native 方法兜成安全默认值（模型数 0 / 空列表），让界面能构建出来；
 * 不模拟任何渲染行为。仅测试期生效，不改变生产代码。</p>
 */
@Implements(value = Live2DNative.class, callThroughByDefault = true)
public class ShadowLive2DNative {

    @Implementation
    protected static int nativeGetModelCount() {
        return 0;
    }

    @Implementation
    protected static String nativeGetModelDirName(int index) {
        return "";
    }

    @Implementation
    protected static void nativeAddModelDir(String dir, String jsonBase) {
        // no-op：测试环境无 native 模型表
    }

    @Implementation
    protected static boolean nativeIsMotionPlaying() {
        return false;
    }
}
