package com.digitallife.render;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.TextureView;

import com.digitallife.ui.glass.GlassPanelStyle;

/**
 * 液态玻璃图层：在普通 View 层级中插入一层 **GL 渲染的折射玻璃面板**。
 *
 * <p>为什么用 {@link TextureView} 而不是 GLSurfaceView：同窗口内 SurfaceView 的
 * 层级只有「所有 View 之上」（setZOrderOnTop）或「所有 View 之下」（默认/mediaOverlay）
 * 两种，无法做到「压在页面内容之上、又在抽屉内容之下」。TextureView 是普通 View，
 * 按兄弟 View 顺序合成，正好插在中间。</p>
 *
 * <p>背景来源：由调用方通过 {@link #setBackdrop(Bitmap)} 提供页面快照（见
 * {@code ui/glass/BackdropCapture}）；native 侧用折射着色器对快照做边缘弯折 + 色散 +
 * 模糊。无快照或 GL 初始化失败时自动降级（{@link #isAvailable()} 为 false，
 * 调用方保留原有 Java 绘制）。</p>
 */
public class LiquidGlassLayerView extends TextureView implements TextureView.SurfaceTextureListener {

    private static final String TAG = "LiquidGlassLayer";

    /** GL 是否可用（着色器编译成功）；false 时 {@link Listener#onGlassReady} 收到 false */
    public interface Listener {
        void onGlassReady(boolean ok);
    }

    private final Object lock = new Object();

    // ── 由主线程写入、GL 线程读取的状态（全部在 lock 内读写） ──
    private float panelX, panelY, panelW = -1f, panelH = -1f;
    private float originX, originY;
    private GlassPanelStyle style = GlassPanelStyle.drawer();
    private Bitmap pendingBackdrop;
    private boolean dirty = true;
    private boolean stopped;
    private boolean flipY = false;

    private volatile boolean available = false;
    private Listener listener;
    private RenderThread renderThread;

    public LiquidGlassLayerView(Context context) {
        super(context);
        setOpaque(false);
        setSurfaceTextureListener(this);
        // 非交互层：触摸穿透给下层（抽屉遮罩/内容）
        setClickable(false);
        setFocusable(false);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** GL 是否可用；必须在收到 {@link Listener#onGlassReady} 之后读取 */
    public boolean isAvailable() {
        return available;
    }

    /** 上传新的背景快照（会接管该 Bitmap 的生命周期，上传后回收） */
    public void setBackdrop(Bitmap bmp) {
        synchronized (lock) {
            if (pendingBackdrop != null && pendingBackdrop != bmp && !pendingBackdrop.isRecycled()) {
                pendingBackdrop.recycle();
            }
            pendingBackdrop = bmp;
            dirty = true;
        }
        ensureRendering();
    }

    /** 背景纹理是否为帧缓冲拷贝（需要翻转 v）；Java 上传的位图传 false */
    public void setBackdropFlipY(boolean flip) {
        synchronized (lock) {
            flipY = flip;
            dirty = true;
        }
    }

    /**
     * 设置玻璃面板。
     *
     * @param x,y,w,h   面板矩形（本 View 局部像素）；w 或 h ≤0 表示隐藏
     * @param originX/Y 面板左上角在**背景快照**中的像素坐标
     */
    public void setPanel(float x, float y, float w, float h, float originX, float originY) {
        synchronized (lock) {
            this.panelX = x;
            this.panelY = y;
            this.panelW = w;
            this.panelH = h;
            this.originX = originX;
            this.originY = originY;
            dirty = true;
        }
        ensureRendering();
    }

    public void setStyle(GlassPanelStyle s) {
        if (s == null) return;
        synchronized (lock) {
            style = s;
            dirty = true;
        }
        ensureRendering();
    }

    private void ensureRendering() {
        RenderThread t = renderThread;
        if (t != null) t.wake();
    }

    // ── TextureView.SurfaceTextureListener ──

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (!GlassNative.isLoaded()) {
            Log.w(TAG, "native 库不可用，玻璃层降级：" + GlassNative.loadError());
            notifyReady(false);
            return;
        }
        stopped = false;
        renderThread = new RenderThread(surface, getContext().getResources().getDisplayMetrics().density);
        renderThread.start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        stopRenderThread();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    private void notifyReady(boolean ok) {
        available = ok;
        Listener l = listener;
        if (l != null) post(() -> l.onGlassReady(ok));
    }

    /** 释放 GL 资源（在宿主 Activity 销毁时调用） */
    public void release() {
        stopRenderThread();
        synchronized (lock) {
            if (pendingBackdrop != null && !pendingBackdrop.isRecycled()) {
                pendingBackdrop.recycle();
            }
            pendingBackdrop = null;
        }
    }

    private void stopRenderThread() {
        RenderThread t;
        synchronized (lock) {
            stopped = true;
            t = renderThread;
            renderThread = null;
            lock.notifyAll();
        }
        if (t != null) {
            t.quit();
            try {
                t.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * GL 渲染线程：持有独立 EGL 上下文，按需渲染（脏标记唤醒，无动画时不空转）。
     */
    private final class RenderThread extends Thread {

        private final SurfaceTexture surfaceTexture;
        private final float density;

        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

        private int textureId = 0;
        private int texW = 0, texH = 0;
        private int capW = 0, capH = 0;
        private boolean initialized = false;
        private volatile boolean quit = false;

        RenderThread(SurfaceTexture st, float density) {
            super("LiquidGlass");
            this.surfaceTexture = st;
            this.density = density > 0 ? density : 1f;
        }

        void wake() {
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        void quit() {
            quit = true;
            wake();
        }

        @Override
        public void run() {
            try {
                if (!initEgl()) {
                    notifyReady(false);
                    return;
                }
                if (!GlassNative.nativeInit()) {
                    Log.e(TAG, "玻璃着色器初始化失败，降级");
                    notifyReady(false);
                    releaseEgl();
                    return;
                }
                initialized = true;
                notifyReady(true);
                loop();
            } catch (Throwable t) {
                Log.e(TAG, "玻璃渲染线程异常", t);
                notifyReady(false);
            } finally {
                if (initialized) GlassNative.nativeShutdown();
                releaseEgl();
                destroyTexture();
            }
        }

        private void loop() {
            while (!quit) {
                boolean work;
                Bitmap bmp = null;
                float x, y, w, h, ox, oy, flip;
                GlassPanelStyle st;
                synchronized (lock) {
                    if (!dirty && !quit) {
                        try {
                            lock.wait(1000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    work = dirty && !quit;
                    dirty = false;
                    if (pendingBackdrop != null) {
                        bmp = pendingBackdrop;
                        pendingBackdrop = null;
                    }
                    x = panelX;
                    y = panelY;
                    w = panelW;
                    h = panelH;
                    ox = originX;
                    oy = originY;
                    flip = flipY ? 1f : 0f;
                    st = style;
                }
                if (!work) continue;

                if (bmp != null) uploadBackdrop(bmp);
                drawFrame(x, y, w, h, ox, oy, flip, st);
            }
        }

        private boolean initEgl() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false;
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false;

            int[] attribs = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE,
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
                    || numConfigs[0] <= 0) {
                return false;
            }
            int[] ctxAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                    ctxAttribs, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false;

            int[] surfAttribs = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surfaceTexture,
                    surfAttribs, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false;

            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        }

        private void releaseEgl() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
        }

        private void destroyTexture() {
            if (textureId != 0) {
                GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
                textureId = 0;
            }
        }

        private void uploadBackdrop(Bitmap bmp) {
            int bw = bmp.getWidth();
            int bh = bmp.getHeight();
            if (textureId == 0) {
                int[] ids = new int[1];
                GLES20.glGenTextures(1, ids, 0);
                textureId = ids[0];
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            texW = bw;
            texH = bh;
            capW = bw;
            capH = bh;
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            if (!bmp.isRecycled()) bmp.recycle();
        }

        private void drawFrame(float x, float y, float w, float h, float ox, float oy,
                               float flip, GlassPanelStyle st) {
            int viewW = getWidth();
            int viewH = getHeight();
            GLES20.glViewport(0, 0, viewW, viewH);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (w > 0.5f && h > 0.5f) {
                GlassNative.nativeSetSurfaceSize(viewW, viewH);
                GlassNative.nativeBeginFrame();
                float[] params = st.toParams(density, x, y, w, h,
                        capW, capH, texW, texH, flip > 0.5f, ox, oy);
                GlassNative.nativeDrawPanel(params, textureId);
            }
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        }
    }
}
