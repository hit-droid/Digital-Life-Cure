package com.digitallife.render;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GLSurfaceView that renders the Live2D Cubism model via Native SDK.
 */
public class Live2DGLView extends GLSurfaceView {

    private static final String TAG = "Live2DGLView";

    public interface Listener {
        void onReady();
        void onTap(float x, float y);
    }

    private final Renderer renderer;
    private final PhysicalController physics = new PhysicalController();
    private Listener listener;
    private boolean ready = false;

    /** 物理弹簧控制器（AI 行为参数接管通道） */
    public PhysicalController getPhysics() {
        return physics;
    }

    /** 该参数是否由物理弹簧接管（否则走直通 queueEvent） */
    public boolean isSpringControlled(String paramId) {
        return physics.isControlled(paramId);
    }

    /** AI 写入行为参数目标值（任意线程安全），物理层在 GL 线程平滑到位 */
    public void setParamTarget(String paramId, float value) {
        physics.setTarget(paramId, value);
    }

    public Live2DGLView(Context context) {
        super(context);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);
        // 普通窗口内容之上但位于 WindowManager 普通 View 之下：
        // 让气泡/输入框等普通 View 能覆盖模型 Surface，避免被 GL 内容遮挡
        setZOrderMediaOverlay(true);
        getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);

        renderer = new Live2DRenderer();
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isReady() {
        return ready;
    }

    public void setExpression(String expr) {
        queueEvent(() -> Live2DNative.nativeSetExpression(expr));
    }

    public void startMotion(String group, int index, int priority) {
        queueEvent(() -> Live2DNative.nativeStartMotion(group, index, priority));
    }

    public void setMouth(float open) {
        queueEvent(() -> Live2DNative.nativeSetMouth(open));
    }

    public void setParameterValue(String paramId, float value) {
        queueEvent(() -> Live2DNative.nativeSetParameterValue(paramId, value));
    }

    /** 同步查询是否有非待机动作在播放（直接从主线程读 native 原子标志） */
    public boolean isMotionPlaying() {
        return Live2DNative.nativeIsMotionPlaying();
    }

    /**
     * 在 GL 线程同步释放 Native Cubism 资源并停止渲染。
     * 用于停止桌宠时先释放旧 EGL Context 的 GL 资源，
     * 再移除悬浮窗，避免重启时复用失效的纹理/renderer。
     */
    public void releaseNative() {
        try {
            // queueEvent 会等待 GL 线程执行完成（在 GL 线程上触发后返回）
            queueEvent(() -> {
                try {
                    Live2DNative.nativeOnPause();
                    Live2DNative.nativeOnStop();
                    Live2DNative.nativeOnDestroy();
                } catch (Throwable t) {
                    Log.e(TAG, "releaseNative error", t);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "releaseNative queue error", t);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        queueEvent(() -> Live2DNative.nativeOnPause());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (listener == null) return false;

        final float x = event.getX();
        final float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                queueEvent(() -> Live2DNative.nativeOnTouchesBegan(x, y));
                listener.onTap(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                queueEvent(() -> Live2DNative.nativeOnTouchesEnded(x, y));
                return true;
            case MotionEvent.ACTION_MOVE:
                queueEvent(() -> Live2DNative.nativeOnTouchesMoved(x, y));
                return true;
        }
        return false;
    }

    private class Live2DRenderer implements Renderer {
        private long lastFrameNanos = 0L;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            Log.d(TAG, "onSurfaceCreated");
            physics.reset();
            lastFrameNanos = 0L;
            Live2DNative.nativeOnStart();
            Live2DNative.nativeOnSurfaceCreated();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            Log.d(TAG, "onSurfaceChanged: " + width + "x" + height);
            Live2DNative.nativeOnSurfaceChanged(width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            // EGL 垂直同步驱动每帧；用真实时间差驱动物理弹簧（首帧跳过）
            long now = System.nanoTime();
            float dt = lastFrameNanos == 0L ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
            lastFrameNanos = now;

            // 弹簧积分：把 AI 目标值翻译成带惯性/过冲的连续运动，一次 JNI 批量写入
            float[] values = physics.update(dt);
            Live2DNative.nativeSetParameterValues(PhysicalController.PARAM_IDS, values);

            Live2DNative.nativeOnDrawFrame();
            if (!ready) {
                ready = true;
                if (listener != null) {
                    post(() -> listener.onReady());
                }
            }
        }
    }
}