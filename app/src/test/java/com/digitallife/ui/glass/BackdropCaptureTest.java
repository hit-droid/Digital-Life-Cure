package com.digitallife.ui.glass;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * {@link BackdropCapture#captureDrawableRegion} 回归。
 *
 * <p>底部导航身后没有内容、只有页面背景 drawable，快照必须「按 root 尺寸铺底、再截取
 * 导航所在的那一块」——偏移算错的话，玻璃折射到的就是页面顶上那块环境光，而不是导航
 * 身后的那块（真机上表现为「折射错位」，很难肉眼归因）。这里用一条横向黑→白渐变
 * 钉住偏移量：截取越靠右的区域，像素应当越亮。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BackdropCaptureTest {

    /** 横向黑→白渐变：颜色只由「画布 x 相对 drawable bounds」决定，便于验证偏移。 */
    private static Drawable horizontalGradient() {
        return new Drawable() {
            private final Paint paint = new Paint();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                paint.setShader(new LinearGradient(b.left, 0, b.right, 0,
                        Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP));
                canvas.drawRect(b.left, b.top, b.right, b.bottom, paint);
            }

            @Override public void setAlpha(int alpha) { }

            @Override public void setColorFilter(ColorFilter cf) { }

            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }

    @Test
    public void badInputReturnsNull() {
        assertNull(BackdropCapture.captureDrawableRegion(null, 100, 100, 0, 0, 10, 10));
        assertNull(BackdropCapture.captureDrawableRegion(horizontalGradient(), 0, 100, 0, 0, 10, 10));
        assertNull(BackdropCapture.captureDrawableRegion(horizontalGradient(), 100, 0, 0, 0, 10, 10));
        assertNull(BackdropCapture.captureDrawableRegion(horizontalGradient(), 100, 100, 0, 0, 0, 10));
        assertNull(BackdropCapture.captureDrawableRegion(horizontalGradient(), 100, 100, 0, 0, 10, 0));
    }

    @Test
    public void cropsRegionAtCorrectOffset() {
        int rootW = 100;
        int rootH = 200;
        Drawable d = horizontalGradient();

        Bitmap left = BackdropCapture.captureDrawableRegion(d, rootW, rootH, 0, 0, 20, 20);
        Bitmap right = BackdropCapture.captureDrawableRegion(d, rootW, rootH, 80, 0, 20, 20);
        assertNotNull(left);
        assertNotNull(right);
        assertEquals(20, left.getWidth());
        assertEquals(20, left.getHeight());

        int l = Color.red(left.getPixel(0, 0));
        int r = Color.red(right.getPixel(0, 0));
        // 黑→白渐变：截取靠右的 20px，起点像素必须明显更亮
        assertTrue("偏移未生效：左块 " + l + " 右块 " + r, r > l + 40);

        left.recycle();
        right.recycle();
    }

    @Test
    public void solidColorIsRenderedForReal() {
        // 用 NATIVE 图形模式跑真 Skia：纯色应逐像素落到位
        Bitmap bmp = BackdropCapture.captureDrawableRegion(
                new ColorDrawable(Color.RED), 50, 50, 10, 10, 8, 8);
        assertNotNull(bmp);
        assertEquals(Color.RED, bmp.getPixel(4, 4));
        bmp.recycle();
    }
}
