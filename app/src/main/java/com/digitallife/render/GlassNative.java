package com.digitallife.render;

import android.util.Log;

/**
 * 液态玻璃渲染器的 JNI 桥。
 *
 * <p>native 实现见 {@code app/src/main/cpp/glass/}：GlassRenderer（GLES2 折射着色器）+
 * GlassJni（本类对应的入口）。折射着色器移植自「ytbl 液态玻璃」（默檐）。</p>
 *
 * <p>所有 native 方法都必须在 **持有当前 EGL 上下文的 GL 线程**上调用。</p>
 */
public final class GlassNative {

    private static final String TAG = "GlassNative";

    private static final boolean sLoaded;
    private static final String sLoadError;

    static {
        // 与 Live2DNative 同一个 .so（maidendungeon）；重复 loadLibrary 是幂等的。
        // 同样不允许类初始化抛错，失败由调用方降级（玻璃层不启用，界面照常）。
        boolean loaded = false;
        String error = null;
        try {
            System.loadLibrary("maidendungeon");
            loaded = true;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "液态玻璃 native 库加载失败", t);
        }
        sLoaded = loaded;
        sLoadError = error;
    }

    /** 参数数组下标，与 {@code glass/GlassJni.cpp} 里的 enum 一一对应 */
    public static final int P_X = 0;
    public static final int P_Y = 1;
    public static final int P_W = 2;
    public static final int P_H = 3;
    public static final int P_RADIUS_TL = 4;
    public static final int P_RADIUS_TR = 5;
    public static final int P_RADIUS_BR = 6;
    public static final int P_RADIUS_BL = 7;
    public static final int P_REFRACTION_HEIGHT = 8;
    public static final int P_REFRACTION_AMOUNT = 9;
    public static final int P_DISPERSION = 10;
    public static final int P_BLUR = 11;
    public static final int P_OPACITY = 12;
    public static final int P_VIBRANCY = 13;
    public static final int P_BRIGHTNESS = 14;
    public static final int P_CONTRAST = 15;
    public static final int P_SATURATION = 16;
    public static final int P_SURFACE_R = 17;
    public static final int P_SURFACE_G = 18;
    public static final int P_SURFACE_B = 19;
    public static final int P_SURFACE_A = 20;
    public static final int P_TINT_MODE = 21;
    public static final int P_HL_R = 22;
    public static final int P_HL_G = 23;
    public static final int P_HL_B = 24;
    public static final int P_HL_A = 25;
    public static final int P_HL_ALPHA = 26;
    public static final int P_HL_WIDTH = 27;
    public static final int P_HL_BLUR = 28;
    public static final int P_HL_ANGLE = 29;
    public static final int P_HL_FALLOFF = 30;
    public static final int P_HL_MODE = 31;
    public static final int P_INNER_R = 32;
    public static final int P_INNER_G = 33;
    public static final int P_INNER_B = 34;
    public static final int P_INNER_A = 35;
    public static final int P_INNER_ALPHA = 36;
    public static final int P_INNER_RADIUS = 37;
    public static final int P_INNER_OFFSET_X = 38;
    public static final int P_INNER_OFFSET_Y = 39;
    public static final int P_CAPTURE_ORIGIN_X = 40;
    public static final int P_CAPTURE_ORIGIN_Y = 41;
    public static final int P_CAPTURE_W = 42;
    public static final int P_CAPTURE_H = 43;
    public static final int P_CAPTURE_TEX_W = 44;
    public static final int P_CAPTURE_TEX_H = 45;
    public static final int P_FLIP_Y = 46;
    public static final int P_FALLBACK_R = 47;
    public static final int P_FALLBACK_G = 48;
    public static final int P_FALLBACK_B = 49;
    public static final int P_FALLBACK_A = 50;

    /** 参数数组长度 */
    public static final int PARAM_COUNT = 51;

    private GlassNative() {
    }

    /** native 库是否可用；false 时玻璃层必须降级（保留原有 Java 绘制） */
    public static boolean isLoaded() {
        return sLoaded;
    }

    /** 加载失败原因；成功时为 null */
    public static String loadError() {
        return sLoadError;
    }

    /** 编译着色器并准备网格；返回 false 表示 GLSL 失败，调用方应降级 */
    public static native boolean nativeInit();

    /** 释放 GL 资源（须在 EGL 上下文仍 current 时调用） */
    public static native void nativeShutdown();

    /** 告知渲染目标尺寸（surface 像素），用于面板矩形 → NDC 换算 */
    public static native void nativeSetSurfaceSize(int w, int h);

    /** 每帧绘制前调用：设置混合等 GL 状态 */
    public static native void nativeBeginFrame();

    /**
     * 绘制一块玻璃面板。
     *
     * @param params {@link #PARAM_COUNT} 长度的参数数组，下标见 P_* 常量
     * @param texId  背景纹理 id；0 表示无背景，使用 fallback 底色
     * @return 是否成功绘制
     */
    public static native boolean nativeDrawPanel(float[] params, int texId);
}
