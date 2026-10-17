package com.digitallife.render;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Looper;

import com.digitallife.ui.glass.GlassPanelStyle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * 液态玻璃层的降级路径回归：native 库加载失败（或着色器初始化失败）时，
 * 必须「界面照常、玻璃不启用」，而不是抛异常把宿主界面一起带崩——
 * {@link LiquidGlassLayerView} 是插在普通 View 层级「页面内容之上、抽屉内容之下」
 * 的中间层，它一崩整页都白。
 *
 * <p>本环境没有 {@code libmaidendungeon.so}，{@code GlassNative} 静态块必然走到
 * {@code UnsatisfiedLinkError} 分支（{@code isLoaded()==false}），正好等价于真机上
 * 「库加载失败」这一最坏场景，可以在这里真跑一遍而不需要设备。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class LiquidGlassLayerViewTest {

    @Test
    public void nativeLoadFailure_degradesWithoutCrash() {
        // 前提：测试环境 native 库不可用，后续断言才落在「降级」分支上
        assertFalse("Robolectric 无 native 库，前提为 isLoaded()==false", GlassNative.isLoaded());

        ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
        Activity host = controller.get();

        LiquidGlassLayerView view = new LiquidGlassLayerView(host);
        host.setContentView(view);

        final boolean[] notified = {false};
        final boolean[] readyValue = {true};
        view.setListener(ok -> {
            notified[0] = true;
            readyValue[0] = ok;
        });

        // 模拟 SurfaceTexture 就绪；native 不可用时不得启动任何 GL 线程
        view.onSurfaceTextureAvailable(null, 100, 100);
        view.onSurfaceTextureSizeChanged(null, 120, 120);
        view.onSurfaceTextureUpdated(null);

        // 降级回调经 post 派发，需驱动主线程消息队列
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue("必须回调 onGlassReady(false) 让上层保留原 Java 绘制", notified[0]);
        assertFalse("降级时 ready 必须为 false", readyValue[0]);
        assertFalse("降级时 isAvailable() 必须为 false", view.isAvailable());

        // 降级期间任何入口都不应抛异常（调用方会在不知情的情况下继续调这些方法）
        view.setBackdrop(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888));
        view.setBackdropFlipY(true);
        view.setPanel(0, 0, 40, 40, 0, 0);
        view.setStyle(GlassPanelStyle.drawer());
        view.onSurfaceTextureDestroyed(null);
        view.release();
    }
}
