package com.digitallife.ui.glass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * 抓取一个 View 子树中的一块矩形画面为位图——液态玻璃的「背景快照」。
 *
 * <p>玻璃要折射的就是它身后的内容，而 Java View 无法直接把身后像素交给 GL，
 * 因此先在 CPU 侧把页面画进 {@link Bitmap}，再由 GL 层上传为纹理。
 * 抓取是**软件重绘**，代价与视图数量成正比，故只应在布局/打开浮层这类
 * 低频时机调用（不要每帧调用）。</p>
 *
 * <p><b>为什么只能 1:1（不做降采样）</b>：native 折射着色器里，面板内像素坐标
 * 直接当作快照纹理坐标使用（见 {@code glass/GlassRenderer.cpp} 的 {@code contentUv}），
 * 没有缩放 uniform。快照一旦缩放，采样位置就会整体错位。</p>
 */
public final class BackdropCapture {

    private BackdropCapture() {
    }

    /**
     * 把 {@code root} 上以 ({@code left},{@code top}) 为左上角、{@code w}×{@code h} 的区域
     * 画进一张同尺寸位图。
     *
     * <p>调用前提：该区域内**没有**需要在快照里排除的浮层（例如抽屉、玻璃层）可见——
     * 否则会把他们自己也画进快照造成自反馈。主壳侧栏的做法是「在抽屉置为可见之前先抓」。</p>
     *
     * @return 位图；{@code root} 尚未布局或绘制失败时返回 null
     */
    public static Bitmap captureRegion(View root, int left, int top, int w, int h) {
        if (root == null || w <= 0 || h <= 0) return null;

        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.translate(-left, -top);
            root.draw(canvas);
        } catch (Throwable ignored) {
            // 某些视图（SurfaceView、超大层级）无法软件重绘，返回 null 由调用方降级
            if (bmp != null) {
                bmp.recycle();
                bmp = null;
            }
        }
        return bmp;
    }

    /**
     * 把「某个 drawable 铺满 root 尺寸时的画面」中的一块矩形抓成位图。
     *
     * <p>与 {@link #captureRegion} 的区别：这里只画 drawable，**不画任何子 View**。
     * 用于「浮层身后没有内容、只有页面背景」的场景——底部导航就压在
     * {@code bg_operit_page} 的环境光上（导航是 LinearLayout 里的独立一行，
     * 内容区在它上方、不与其重叠）。若改用 {@code captureRegion(root)} 会把导航
     * 自身也画进快照（导航是 root 的子 View），折射自己形成自反馈。</p>
     *
     * <p>gradient/radial 的坐标以 drawable 的 bounds 为基准，故这里把 drawable 的
     * bounds 设为 root 的完整矩形、再把画布平移 {@code (-left,-top)}，截取出来的
     * 就是该区域「和它在屏上一致」的样子。</p>
     *
     * @param d     root 的背景 drawable（{@code root.getBackground()}），可为 null
     * @param rootW root 的整宽（drawable bounds 的右边界）
     * @param rootH root 的整高（drawable bounds 的下边界）
     * @param left,top,w,h  要抓取的区域（root 坐标系）
     * @return 位图；drawable 为 null、尺寸非法或绘制失败时返回 null
     */
    public static Bitmap captureDrawableRegion(Drawable d, int rootW, int rootH,
                                               int left, int top, int w, int h) {
        if (d == null || rootW <= 0 || rootH <= 0 || w <= 0 || h <= 0) return null;
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.translate(-left, -top);
            d.setBounds(0, 0, rootW, rootH);
            d.draw(canvas);
        } catch (Throwable ignored) {
            if (bmp != null) {
                bmp.recycle();
                bmp = null;
            }
        }
        return bmp;
    }
}
