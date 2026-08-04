package com.digitallife.render;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.InputStream;

/**
 * JNI bridge to the Live2D Cubism Native SDK.
 * All native methods are called from the GLSurfaceView renderer.
 */
public class Live2DNative {
    private static final String TAG = "Live2DNative";

    static {
        System.loadLibrary("maidendungeon");
    }

    private static AssetManager sAssetManager;

    public static void init(Context context) {
        sAssetManager = context.getAssets();
    }

    /**
     * Called from C++ side to load a file from assets.
     */
    public static byte[] loadFile(String path) {
        if (sAssetManager == null) {
            Log.e(TAG, "AssetManager not initialized");
            return null;
        }
        try {
            InputStream is = sAssetManager.open(path);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            is.close();
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load: " + path, e);
            return null;
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
    /** 是否有非待机动作（如 TapBody）在播放；AI 待机微动应让位 */
    public static native boolean nativeIsMotionPlaying();

    // Model switching
    public static native void nativeChangeScene(int index);
    public static native int nativeGetModelCount();
    public static native String nativeGetModelDirName(int index);
}