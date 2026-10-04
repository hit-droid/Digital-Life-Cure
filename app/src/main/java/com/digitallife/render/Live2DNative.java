package com.digitallife.render;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * JNI bridge to the Live2D Cubism Native SDK.
 * All native methods are called from the GLSurfaceView renderer.
 */
public class Live2DNative {
    private static final String TAG = "Live2DNative";

    private static final boolean sLoaded;
    private static final String sLoadError;

    static {
        boolean loaded = false;
        String error = null;
        try {
            System.loadLibrary("maidendungeon");
            loaded = true;
        } catch (Throwable t) {
            // 不让类初始化直接抛错：否则任何首次触碰本类的代码路径都会「无提示闪退」。
            // 记录原因，由调用方决定降级（渲染不可用但界面仍能打开）。
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            Log.e(TAG, "Live2D native 库加载失败", t);
        }
        sLoaded = loaded;
        sLoadError = error;
    }

    /** native 库是否可用；false 时所有 native 方法调用都会抛 UnsatisfiedLinkError */
    public static boolean isLoaded() {
        return sLoaded;
    }

    /** native 库加载失败原因；成功时为 null */
    public static String loadError() {
        return sLoadError;
    }

    private static AssetManager sAssetManager;
    /** 内部导入模型根目录：files/models/，C++ 加载路径在此目录下回退 */
    private static File sModelsDir;

    public static void init(Context context) {
        sAssetManager = context.getAssets();
        sModelsDir = new File(context.getFilesDir(), "models");
    }

    /** 内部导入模型根目录（ModelManager 使用） */
    public static File getModelsDir() {
        return sModelsDir;
    }

    /**
     * Called from C++ side to load a model file.
     * 优先从 assets 读取；assets 中不存在时回退到内部导入模型目录
     * files/models/<path>，从而让导入模型走同一条加载链路。
     */
    public static byte[] loadFile(String path) {
        if (path == null) return null;
        if (sAssetManager != null) {
            try {
                InputStream is = sAssetManager.open(path);
                return readAll(is);
            } catch (Exception ignored) {
                // assets 无此文件，回退到内部目录
            }
        }
        if (sModelsDir != null) {
            try {
                File f = new File(sModelsDir, path);
                if (f.isFile()) {
                    return readAll(new FileInputStream(f));
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load internal model file: " + path, e);
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream is) throws Exception {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try { is.close(); } catch (Exception ignored) {}
        }
    }

    public static void moveTaskToBack() {
        // no-op for floating window scenario
    }

    // Lifecycle
    public static native void nativeOnStart();
    public static native void nativeOnPause();
    public static native void nativeOnStop();
    public static native void nativeOnDestroy();

    // Surface
    public static native void nativeOnSurfaceCreated();
    public static native void nativeOnSurfaceChanged(int width, int height);
    public static native void nativeOnDrawFrame();

    // Touch
    public static native void nativeOnTouchesBegan(float x, float y);
    public static native void nativeOnTouchesEnded(float x, float y);
    public static native void nativeOnTouchesMoved(float x, float y);

    // Model control
    public static native void nativeSetExpression(String expressionId);
    public static native void nativeStartMotion(String groupName, int index, int priority);
    public static native void nativeSetMouth(float open);
    /** 直接设置任意 Live2D 参数值（如 ParamAngleX, ParamEyeLOpen 等） */
    public static native void nativeSetParameterValue(String paramId, float value);
    /** 批量设置外部参数：物理层每帧一次 JNI 写入，替代逐参数 queueEvent */
    public static native void nativeSetParameterValues(String[] paramIds, float[] values);
    /** 是否有非待机动作（如 TapBody）在播放；AI 待机微动应让位 */
    public static native boolean nativeIsMotionPlaying();

    // Model switching
    public static native void nativeChangeScene(int index);
    public static native int nativeGetModelCount();
    public static native String nativeGetModelDirName(int index);
    /** 运行时注册导入的模型目录（位于 files/models/ 下） */
    public static native void nativeAddModelDir(String dir, String jsonBase);
}