package com.digitallife.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.digitallife.R;
import com.digitallife.brain.AICore;
import com.digitallife.brain.EnvironmentSensors;
import com.digitallife.brain.PetState;
import com.digitallife.brain.PetVitalsManager;
import com.digitallife.brain.ThoughtLoopManager;
import com.digitallife.care.CareAI;
import com.digitallife.care.CareAutomation;
import com.digitallife.care.CareExecutor;
import com.digitallife.brain.Heartbeat;
import com.digitallife.model.ModelInspector;
import com.digitallife.model.ModelManager;
import com.digitallife.util.CrashHandler;
import com.digitallife.util.MemoryStore;
import com.digitallife.util.Settings;
import com.digitallife.util.ThoughtStore;
import com.digitallife.ui.PetOverlayView;
import com.digitallife.render.Live2DNative;
import com.digitallife.render.Live2DGLView;
import com.digitallife.render.ContinuousMotionEngine;
import com.digitallife.speech.STTEngine;
import com.digitallife.speech.TTSEngine;

/**
 * PetService：桌面伴侣前台服务。
 * AICore 持续运行（100ms 快速循环 + 30s LLM 慢循环），
 * 直接控制 Live2D 参数细节，AI 实时在线。
 */
public class PetService extends Service implements AICore.Output,
        PetOverlayView.Listener, TTSEngine.Listener {

    public static final String ACTION_START = "com.digitallife.START";
    public static final String ACTION_STOP = "com.digitallife.STOP";
    public static final String ACTION_TOGGLE_VOICE = "com.digitallife.TOGGLE_VOICE";
    // v1.23.0 长按桌面图标快捷方式启动
    public static final String ACTION_START_OVERLAY = "com.digitallife.START_OVERLAY";
    public static final String ACTION_STOP_OVERLAY = "com.digitallife.STOP_OVERLAY";

    private static final String CHANNEL_ID = "pet_overlay";
    private static final int NOTIFY_ID = 1001;
    private static final String TAG = "PetService";
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private com.digitallife.brain.ProactiveEngine proactiveEngine; // v1.24.0
    private long lastProactiveTickMs = 0;

    private WindowManager windowManager;
    private PetOverlayView overlayView;
    private WindowManager.LayoutParams overlayParams;
    private DisplayManager.DisplayListener displayListener;

    public AICore aiCore; // v1.24.0: AgentConsoleActivity 需访问
    private CareAutomation careAutomation;
    private TTSEngine tts;
    private STTEngine stt;
    private Settings settings;
    private Heartbeat heartbeat;
    private MemoryStore memory;
    /** v1.120.0：常驻服务承载记忆自动提取（此前 schedulePeriodic 从未被调用） */
    private com.digitallife.memory.MemoryExtractor memoryExtractor;

    // L2 生理状态机 / L3 心理独白
    private PetVitalsManager vitals;
    private EnvironmentSensors environmentSensors;
    private ThoughtLoopManager thoughtLoop;
    private ThoughtStore thoughtStore;
    private volatile boolean touching = false;
    private boolean vitalsTicking = false;
    private long lastDragTime = 0;

    private final STTEngine.Listener sttListener = new STTEngine.Listener() {
        @Override
        public void onListeningStart() {
            listening = true;
            if (overlayView != null) overlayView.showBubble("我在听，你说吧~", 4f);
        }

        @Override
        public void onResult(String text) {
            listening = false;
            if (aiCore != null) {
                aiCore.onUserInteraction();
                aiCore.onUserSays(text);
                if (proactiveEngine != null) proactiveEngine.markUserActive(); // v1.24.0
            }
        }

        @Override
        public void onError(String error) {
            listening = false;
            if (overlayView != null) overlayView.showBubble("(听不清： " + error + ")", 2.5f);
        }

        @Override
        public void onEnd() {
            listening = false;
        }
    };

    private boolean voiceEnabled = true;
    private boolean ttsAvailable = true;
    private boolean listening = false;

    /** 生理状态机 1Hz tick 的自循环 Runnable（提取为字段以便 onDestroy 取消，防止泄漏） */
    private final Runnable vitalsTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!vitalsTicking) return;
            if (vitals != null) vitals.tick(PetVitalsManager.currentHour(), touching);
            // v1.24.0：每 30 分钟巡检一次主动行为
            long now = System.currentTimeMillis();
            if (proactiveEngine != null
                    && now - lastProactiveTickMs > com.digitallife.brain.ProactiveEngine.INTERVAL_MS) {
                lastProactiveTickMs = now;
                try { proactiveEngine.tick(); } catch (Exception ignored) {}
            }
            mainHandler.postDelayed(this, 1000L);
        }
    };

    private static PetService instance;

    public static PetService getInstance() { return instance; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        CrashHandler.init(this);
        settings = new Settings(this);
        voiceEnabled = settings.isVoiceEnabled();
        initNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && (ACTION_STOP.equals(intent.getAction()) || ACTION_STOP_OVERLAY.equals(intent.getAction()))) {
            if (settings != null) settings.setPetEnabled(false); // v1.133.0：用户意图置为「不运行」，开机不再自启
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_TOGGLE_VOICE.equals(intent.getAction())) {
            voiceEnabled = !voiceEnabled;
            settings.setVoiceEnabled(voiceEnabled);
            return START_STICKY;
        }
        ensureRunning();
        if (settings != null) settings.setPetEnabled(true); // v1.133.0：唯一事实来源，供开机自启判断
        return START_STICKY;
    }

    private void ensureRunning() {
        if (overlayView != null) return;
        settings = new Settings(this);
        startForeground(NOTIFY_ID, buildNotification());

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        float scale = settings.getScale() / 100f;
        int overlayW = (int) (size.x * 0.35f * scale);
        int overlayH = (int) (size.y * 0.57f * scale);

        overlayView = new PetOverlayView(this, this);
        Live2DNative.init(this);
        // 恢复上次导入的模型到 C++ 动态模型列表
        ModelManager.registerImportedModels(this);
        overlayParams = new WindowManager.LayoutParams(
                overlayW, overlayH,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;

        float px = settings.getOverlayX();
        float py = settings.getOverlayY();
        if (px >= 0 && py >= 0) {
            overlayParams.x = (int) px;
            overlayParams.y = (int) py;
        } else {
            overlayParams.x = size.x / 2 - overlayW / 2;
            overlayParams.y = (int) (size.y * 0.18f);
        }
        try {
            windowManager.addView(overlayView, overlayParams);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 屏幕方向/尺寸变化时（横竖屏切换）自适应窗口大小与位置，避免窗口过小或跑出屏幕
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) {
            displayListener = new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) resizeOverlayForDisplay();
                }
            };
            dm.registerDisplayListener(displayListener, mainHandler);
        }

        aiCore = new AICore(settings);
        aiCore.setOutput(this);
        aiCore.start();
        memory = aiCore.getMemory();

        // v1.134.0：接线桌宠状态胶囊的数据源（Issue #27 第 1 条收尾）。
        // 此前 statusProvider 恒为 null，胶囊虽已建好却永远隐藏（PR #29 留下的半成品）。
        // 这里只读地把 AICore 的情绪标量喂给 ui/pet/PetStatusText —— ui/ 不反向依赖 brain/，
        // 因此走 PetOverlayView 定义的 StatusProvider 接口。
        overlayView.setStatusProvider(new PetOverlayView.StatusProvider() {
            @Override
            public float intimacy() {
                return aiCore == null ? 0f : aiCore.getEmotion().getIntimacy();
            }

            @Override
            public float energy() {
                return aiCore == null ? 0f : aiCore.getEmotion().getEnergy();
            }

            @Override
            public String dominant() {
                return aiCore == null ? null : aiCore.getEmotion().dominant();
            }
        });

        // v1.120.0：启动长期记忆自动提取（每 6 小时从近期对话沉淀 facts）
        memoryExtractor = new com.digitallife.memory.MemoryExtractor(this);
        memoryExtractor.schedulePeriodic();

        // v1.24.0：主动行为引擎
        proactiveEngine = new com.digitallife.brain.ProactiveEngine(this);
        com.digitallife.brain.BrainLog.getInstance().log("init", "主动行为引擎就绪（30min tick）");

        // 双层 AI 协作：注入执行层（AI-2），让大脑的行为意图落到真实动作/模型上
        CareExecutor careExecutor = CareExecutor.getInstance(this);
        careExecutor.setRender(careRender);
        aiCore.setExecutor(careExecutor);

        // 自动化引擎：自动体检 + 定时任务 + 异常自动修复，结果气泡主动反馈
        CareAI careAI = CareAI.getInstance(this);
        careAutomation = new CareAutomation(this, careExecutor, careAI, new CareAutomation.Report() {
            @Override
            public void onAutoReport(String text) {
                if (overlayView != null) overlayView.showBubble(text, Math.max(4f, text.length() * 0.06f));
            }
        });
        careAutomation.start();

        // 后台连接已保存的 MCP 工具服务器，连接成功后热刷新大脑工具集
        connectMcpServersAsync();

        if (voiceEnabled) {
            tts = new TTSEngine(this, this);
            stt = new STTEngine(this, sttListener);
        }

        // 心跳：定时问候、长时间冷落时主动刷存在感、随机微行为
        heartbeat = new Heartbeat(new Heartbeat.Listener() {
            @Override
            public void onProactiveInteraction(String reason) {
                if (aiCore == null) return;
                aiCore.onUserInteraction();
                memory.addAssistantMessage(reason);
                if (settings.isProactiveEnabled() && settings.isConfigured()) {
                    // 走 LLM 自主说话，让回复贴合当前记忆/时间
                    aiCore.onProactiveChat(reason);
                } else {
                    if (overlayView != null) {
                        overlayView.showBubble(reason, Math.max(2f, reason.length() * 0.15f));
                    }
                }
            }

            @Override
            public void onAmbientBehavior() {
                if (aiCore == null) return;
                aiCore.onUserInteraction();
                if (overlayView != null && !isMotionPlaying()) {
                    int g = new java.util.Random().nextInt(2);
                    overlayView.setMotion("Idle", g, 2);
                }
            }

            @Override
            public void onClockTick(String message) {
                if (aiCore == null) return;
                aiCore.onUserInteraction();
                if (settings.isProactiveEnabled() && settings.isConfigured()) {
                    aiCore.onProactiveChat(message);
                } else {
                    if (overlayView != null) {
                        overlayView.showBubble(message, Math.max(2f, message.length() * 0.15f));
                    }
                }
            }
        });
        heartbeat.start();

        // L2 生理状态机：每秒演化精力/无聊/情绪，状态切换时映射为姿态目标
        vitals = new PetVitalsManager(state -> {
            if (overlayView == null) return;
            Live2DGLView gl = overlayView.getLive2DView();
            if (gl == null) return;
            switch (state) {
                case SLEEPY:
                    gl.setParamTarget("ParamAngleY", -12f);
                    gl.setParamTarget("ParamAngleZ", 0f);
                    gl.setMouth(0.05f);
                    ContinuousMotionEngine m0 = gl.getMotionEngine();
                    if (m0 != null) m0.setStyle(0.35f, 0.4f, 0.4f, 0.4f);
                    break;
                case BORED:
                    gl.setParamTarget("ParamAngleY", 0f);
                    gl.setParamTarget("ParamAngleZ", 14f);
                    gl.setMouth(0.18f);
                    break;
                case ANNOYED:
                    gl.setParamTarget("ParamAngleZ", -10f);
                    gl.setParamTarget("ParamAngleY", 0f);
                    gl.setMouth(0f);
                    overlayView.setExpression("F02");
                    break;
                case HAPPY:
                    gl.setParamTarget("ParamAngleY", 0f);
                    gl.setParamTarget("ParamAngleZ", 0f);
                    gl.setMouth(0.12f);
                    overlayView.setExpression("F01");
                    break;
                default:
                    gl.setParamTarget("ParamAngleY", 0f);
                    gl.setParamTarget("ParamAngleZ", 0f);
                    gl.setMouth(0f);
                    break;
            }
        });
        vitalsTicking = true;
        startVitalsTick();

        // L3 心理独白：8~12 分钟静默采集环境+生理状态，LLM 产生内心独白并触发动作/气泡
        environmentSensors = new EnvironmentSensors(this);
        thoughtStore = new ThoughtStore(this);
        thoughtLoop = new ThoughtLoopManager(settings, environmentSensors, vitals, (tag, text) -> {
            // 内心独白持久化（发现页数据源）
            try {
                if (thoughtStore != null) thoughtStore.addThought(text);
            } catch (Exception ignored) {
            }
            if (overlayView == null) return;
            overlayView.showBubble(text, Math.max(3f, text.length() * 0.12f));
            switch (tag) {
                case "happy":
                    overlayView.setMotion("Tap", 0, 2);
                    break;
                case "sleepy":
                    overlayView.setMotion("Idle", 0, 1);
                    break;
                case "nod":
                    overlayView.setMotion("Idle", 1, 2);
                    break;
                case "shake":
                    overlayView.setMotion("Idle", 1, 2);
                    break;
                case "annoyed":
                    overlayView.setMotion("Tap", 1, 2);
                    break;
                default:
                    overlayView.setMotion("Idle", 1, 2);
                    break;
            }
        });
        thoughtLoop.start();

        // 无障碍感知：前台 App 变化 / 通知到达 → 写入记忆，择机主动搭话
        com.digitallife.service.PetAccessibilityService.setListener(
                new com.digitallife.service.PetAccessibilityService.Listener() {
                    @Override
                    public void onForegroundAppChanged(String packageName) {
                        if (memory == null || packageName == null) return;
                        memory.addEvent("用户打开了 App：" + packageName);
                    }

                    @Override
                    public void onNotification(String packageName, String text) {
                        if (memory == null || text == null) return;
                        if (text.length() > 60) text = text.substring(0, 60);
                        memory.addEvent("收到来自 " + packageName + " 的通知：" + text);
                    }
                });
    }

    private void startVitalsTick() {
        mainHandler.removeCallbacks(vitalsTickRunnable);
        mainHandler.postDelayed(vitalsTickRunnable, 1000L);
    }

    // ================= 悬浮窗交互 =================

    @Override
    public void onModelReady() {
        // 首帧渲染完成：应用默认模型 + 更新 L1 能力感知
        String def = settings != null ? settings.getDefaultModelDir() : "";
        if (def != null && !def.isEmpty() && !switchToModelByName(def)) {
            // 默认模型缺失时回落到第一个内置模型
            updateModelCapability(0);
        } else if (def == null || def.isEmpty()) {
            updateModelCapability(0);
        }
    }

    @Override
    public void onTap() {
        if (aiCore == null) return;
        aiCore.onUserInteraction();
        noteUserInteraction();
        if (voiceEnabled && tts != null && tts.isSpeaking()) {
            tts.stop();
            aiCore.cancel();
            if (stt != null) stt.startListening();
            return;
        }
        if (listening) return;
        if (!settings.isConfigured()) {
            overlayView.showBubble("我还没有接上大脑呢，先去设置里配置 API 吧~", 3f);
            return;
        }
        aiCore.onUserTap();
    }

    @Override
    public void onDoubleTap() {
        if (aiCore == null) return;
        aiCore.onUserInteraction();
        noteUserInteraction();
        overlayView.setExpression("F02");
        overlayView.showBubble("嘿嘿~ 戳我干嘛呀！", 2f);
    }

    @Override
    public void onLongPress() {
        openSettings();
    }

    @Override
    public void onChatMessage(String text) {
        if (overlayView != null) overlayView.hideChatInput();
        if (overlayParams != null) {
            overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            if (windowManager != null) {
                try { windowManager.updateViewLayout(overlayView, overlayParams); } catch (Exception ignored) {}
            }
        }
        if (aiCore == null) return;
        aiCore.onUserInteraction();
        noteUserInteraction();
        aiCore.onUserSays(text);
        if (proactiveEngine != null) proactiveEngine.markUserActive(); // v1.24.0
    }

    @Override
    public void onRequestChatFocus() {
        if (overlayParams != null) {
            overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            if (windowManager != null) {
                try { windowManager.updateViewLayout(overlayView, overlayParams); } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void onDrag(float dx, float dy) {
        if (dx == 0 && dy == 0) {
            overlayView.clearBubble();
            // 松手：恢复触摸状态，拖拽速度产生的摆角回弹归零
            touching = false;
            lastDragTime = 0;
            Live2DGLView gl = overlayView.getLive2DView();
            if (gl != null) {
                gl.setParamTarget("ParamAngleZ", 0f);
                gl.setParamTarget("ParamBodyAngleX", 0f);
            }
            snapToEdgeIfNeeded(); // v1.132.0：松手吸附到最近的左/右边缘
        }
    }

    /**
     * 松手后按设置把桌宠吸附到最近的左/右边缘并持久化。
     * 关闭「贴边停靠」时只把窗口夹回屏内（拖拽期间允许越界，靠这里兜底）。
     */
    private void snapToEdgeIfNeeded() {
        if (windowManager == null || overlayView == null || overlayParams == null || settings == null) return;
        try {
            Point size = new Point();
            windowManager.getDefaultDisplay().getRealSize(size);
            com.digitallife.util.OverlayDock.Result r = com.digitallife.util.OverlayDock.dock(
                    overlayParams.x, overlayParams.y,
                    overlayParams.width, overlayParams.height,
                    size.x, size.y, settings.isEdgeDockEnabled());
            if (r.x == overlayParams.x && r.y == overlayParams.y) return;
            overlayParams.x = r.x;
            overlayParams.y = r.y;
            windowManager.updateViewLayout(overlayView, overlayParams);
            settings.setOverlayPos(overlayParams.x, overlayParams.y);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDragged(float dx, float dy) {
        overlayParams.x += dx;
        overlayParams.y += dy;
        if (overlayParams.x < 0) overlayParams.x = 0;
        if (overlayParams.y < 0) overlayParams.y = 0;
        try {
            windowManager.updateViewLayout(overlayView, overlayParams);
        } catch (Exception ignored) {}
        settings.setOverlayPos(overlayParams.x, overlayParams.y);

        // L4 惯性摆角：拖拽速度越快，身体/头摆角越大（方向相反，松手弹簧回弹）
        touching = true;
        long now = System.currentTimeMillis();
        float dt = lastDragTime == 0 ? 0.05f : (now - lastDragTime) / 1000f;
        lastDragTime = now;
        if (dt > 0.001f) {
            float vx = dx / dt;
            float headZ = Math.max(-30f, Math.min(30f, vx * 0.008f));
            float bodyX = Math.max(-20f, Math.min(20f, vx * 0.005f));
            Live2DGLView gl = overlayView.getLive2DView();
            if (gl != null) {
                gl.setParamTarget("ParamAngleZ", -headZ);
                gl.setParamTarget("ParamBodyAngleX", -bodyX);
            }
        }
    }

    /** 屏幕尺寸/方向变化时重新计算窗口宽高，并等比迁移位置避免越界 */
    private void resizeOverlayForDisplay() {
        if (windowManager == null || overlayView == null || overlayParams == null) return;
        try {
            Point size = new Point();
            windowManager.getDefaultDisplay().getRealSize(size);
            float scale = settings.getScale() / 100f;
            int overlayW = (int) (size.x * 0.35f * scale);
            int overlayH = (int) (size.y * 0.57f * scale);
            int oldW = overlayParams.width;
            int oldH = overlayParams.height;
            if (oldW > 0 && oldH > 0 && (oldW != overlayW || oldH != overlayH)) {
                overlayParams.x = (int) (overlayParams.x * (float) overlayW / oldW);
                overlayParams.y = (int) (overlayParams.y * (float) overlayH / oldH);
            }
            overlayParams.width = overlayW;
            overlayParams.height = overlayH;
            overlayParams.x = Math.max(0, Math.min(overlayParams.x, Math.max(0, size.x - overlayW)));
            overlayParams.y = Math.max(0, Math.min(overlayParams.y, Math.max(0, size.y - overlayH)));
            windowManager.updateViewLayout(overlayView, overlayParams);
        } catch (Exception ignored) {
        }
    }

    // ================= 模型统一管理 =================

    /**
     * 切换到指定模型（护理大脑/界面统一入口）。
     * GL 线程执行 ChangeScene，随后按模型能力更新 L1 引擎接管策略，
     * 并把该模型记为新默认（下次启动自动加载）。
     */
    public void switchToModel(int index) {
        int count = Live2DNative.nativeGetModelCount();
        if (index < 0 || index >= count) return;
        String dir = Live2DNative.nativeGetModelDirName(index);
        Live2DGLView gl = overlayView != null ? overlayView.getLive2DView() : null;
        if (gl != null) {
            gl.queueEvent(() -> Live2DNative.nativeChangeScene(index));
        } else {
            Live2DNative.nativeChangeScene(index);
        }
        if (settings != null && dir != null && !dir.isEmpty()) {
            settings.setDefaultModelDir(dir);
        }
        updateModelCapability(index);
    }

    /** 按模型目录名切换；返回是否找到并切换 */
    public boolean switchToModelByName(String modelDirName) {
        if (modelDirName == null || modelDirName.isEmpty()) return false;
        int count = Live2DNative.nativeGetModelCount();
        for (int i = 0; i < count; i++) {
            String n = Live2DNative.nativeGetModelDirName(i);
            if (modelDirName.equals(n)) {
                switchToModel(i);
                return true;
            }
        }
        return false;
    }

    /**
     * 模型能力感知：读取模型 json 判定是否有可用动作组，
     * 更新 L1 引擎接管策略（有动作→让位，无动作→全量接管）。
     */
    public void updateModelCapability(int index) {
        try {
            int count = Live2DNative.nativeGetModelCount();
            if (index < 0 || index >= count) return;
            String dir = Live2DNative.nativeGetModelDirName(index);
            if (dir == null || dir.isEmpty()) return;
            boolean hasMotions = ModelInspector.hasUsableMotions(this, dir);
            Live2DGLView gl = overlayView != null ? overlayView.getLive2DView() : null;
            ContinuousMotionEngine engine = gl != null ? gl.getMotionEngine() : null;
            if (engine != null) engine.setModelHasMotions(hasMotions);
            Log.d(TAG, "model capability: " + dir + " usableMotions=" + hasMotions);
        } catch (Throwable t) {
            Log.e(TAG, "updateModelCapability error", t);
        }
    }

    // ================= AICore.Output =================

    /** CareExecutor.Render 实现：执行层（AI-2）落到与 AICore 相同的渲染目标 */
    private final CareExecutor.Render careRender = new CareExecutor.Render() {
        @Override
        public void setParam(String paramId, float value) {
            if (overlayView != null) overlayView.setParameterValue(paramId, value);
        }

        @Override
        public void setExpression(String name) {
            if (overlayView != null) overlayView.setExpression(name);
        }

        @Override
        public void playMotion(String group, int index, int priority) {
            if (overlayView != null) overlayView.setMotion(group, index, priority);
        }

        @Override
        public boolean isMotionPlaying() {
            return overlayView != null && overlayView.isMotionPlaying();
        }

        @Override
        public void bubble(String text, float seconds) {
            if (overlayView != null) overlayView.showBubble(text, seconds);
        }

        @Override
        public void error(String msg) {
            if (overlayView != null) overlayView.showBubble("(信号不好： " + msg + ")", 3f);
        }

        @Override
        public void thinking(boolean thinking) {
            if (overlayView != null && thinking) overlayView.setExpression("F01");
        }

        @Override
        public void speak(String text) {
            if (overlayView != null) overlayView.setSpeaking(true);
            if (voiceEnabled && ttsAvailable && tts != null) tts.speak(text);
        }

        @Override
        public void move(float x, float y) {
            PetService.this.onMove(x, y);
        }
    };

    @Override
    public void onLive2DParam(String paramId, float value) {
        if (overlayView == null) return;
        com.digitallife.render.Live2DGLView gl = overlayView.getLive2DView();
        if (gl != null && gl.isSpringControlled(paramId)) {
            // 行为参数（头/身体角度）走物理弹簧：GL 线程平滑到位，带惯性过冲
            gl.setParamTarget(paramId, value);
        } else {
            // 生理微动（呼吸/眨眼/视线/口型）直通，保留高频瞬时响应
            overlayView.setParameterValue(paramId, value);
        }
    }

    @Override
    public void onExpression(String name) {
        if (overlayView != null) overlayView.setExpression(name);
    }

    @Override
    public void onStyle(float energy, float alertness, float speed, float amplitude) {
        if (overlayView == null) return;
        ContinuousMotionEngine engine = overlayView.getLive2DView().getMotionEngine();
        if (engine != null) engine.setStyle(energy, alertness, speed, amplitude);
    }

    @Override
    public void onGazeBias(float x, float y) {
        if (overlayView == null) return;
        ContinuousMotionEngine engine = overlayView.getLive2DView().getMotionEngine();
        if (engine != null) engine.setGazeBias(x, y);
    }

    @Override
    public void onMotion(String group, int index, int priority) {
        if (overlayView != null) overlayView.setMotion(group, index, priority);
    }

    @Override
    public boolean isMotionPlaying() {
        return overlayView != null && overlayView.isMotionPlaying();
    }

    @Override
    public void onSpeak(String text) {
        if (overlayView != null) overlayView.setSpeaking(true);
        if (voiceEnabled && ttsAvailable && tts != null) tts.speak(text);
    }

    @Override
    public void onSpeakAppend(String text) {
        if (voiceEnabled && ttsAvailable && tts != null) tts.speakAppend(text);
    }

    @Override
    public void onBubble(String text, float seconds) {
        if (overlayView != null) overlayView.showBubble(text, seconds);
    }

    /** v1.24.0：主动行为冒泡（由 ProactiveEngine.tick 调用） */
    public void showProactiveBubble(String text) {
        onBubble(text, 4f);
    }

    @Override
    public void onThinking(boolean thinking) {
        if (overlayView == null) return;
        if (thinking) {
            overlayView.setExpression("F01");
            overlayView.showThinking();
        } else {
            overlayView.hideThinking();
        }
    }

    @Override
    public void onError(String msg) {
        if (overlayView != null) overlayView.showBubble("(信号不好： " + msg + ")", 3f);
    }

    @Override
    public void onMove(float x, float y) {
        if (overlayView == null || windowManager == null) return;
        mainHandler.post(() -> {
            Display display = windowManager.getDefaultDisplay();
            Point size = new Point();
            display.getRealSize(size);
            float targetX = x / 100f * (size.x - overlayParams.width);
            float targetY = y / 100f * (size.y - overlayParams.height);
            overlayParams.x = Math.max(0, Math.round(targetX));
            overlayParams.y = Math.max(0, Math.round(targetY));
            try {
                windowManager.updateViewLayout(overlayView, overlayParams);
            } catch (Exception ignored) {}
            settings.setOverlayPos(overlayParams.x, overlayParams.y);
        });
    }

    // ================= TTS =================

    @Override
    public void onSpeakingStart() {
        if (overlayView != null) {
            overlayView.setSpeaking(true);
            overlayView.setMouth(0.3f);
        }
    }

    @Override
    public void onSpeakingDone() {
        if (overlayView != null) {
            overlayView.setSpeaking(false);
            overlayView.setMouth(0f);
        }
        // 仅语音可用时自动续听；TTS 缺失时降级为文字模式，避免反复触发无意义的监听
        if (voiceEnabled && ttsAvailable && stt != null && !listening) {
            stt.startListening();
        }
    }

    @Override
    public void onInitResult(boolean ok, String error) {
        if (ok) {
            ttsAvailable = true;
            return;
        }
        // 语音合成不可用：关闭发声链路与自动续听，仅保留文字气泡
        ttsAvailable = false;
        if (overlayView != null) {
            overlayView.showBubble("语音引擎不可用，已切换为文字模式。可在设置里安装/启用系统 TTS。", 6f);
        }
    }

    // ================= 公共方法 =================

    public void toggleListening() {
        if (stt == null) return;
        if (!voiceEnabled || !ttsAvailable) {
            if (overlayView != null) overlayView.showBubble("语音互动未启用（TTS 不可用），请使用文字输入。", 3f);
            return;
        }
        if (listening) {
            stt.stopListening();
            listening = false;
        } else {
            if (tts != null && tts.isSpeaking()) tts.stop();
            stt.startListening();
        }
    }

    public AICore getAiCore() { return aiCore; }
    public PetOverlayView getOverlayView() { return overlayView; }
    public boolean isVoiceEnabled() { return voiceEnabled; }

    /** 用户交互登记：喂饱生理状态机、重置独白空闲计时 */
    private void noteUserInteraction() {
        if (vitals != null) vitals.noteInteraction();
        if (thoughtLoop != null) thoughtLoop.noteInteraction();
    }

    /** 语音引擎诊断文本（供配置页显示） */
    public String getVoiceDiag() {
        StringBuilder sb = new StringBuilder();
        sb.append("语音合成 TTS：").append(tts == null ? "未启用" : tts.getState()).append("\n");
        sb.append("语音识别 STT：").append(stt == null ? "未启用" : stt.getState());
        if (voiceEnabled && !ttsAvailable) {
            sb.append("\n（文字模式）语音引擎不可用，已降级为气泡文字回复");
        }
        return sb.toString();
    }

    /** TTS 是否可用（供配置页判断是否显示安装入口） */
    public boolean isTtsAvailable() { return ttsAvailable; }

    /** 引导用户安装/启用系统 TTS 数据 */
    public void openTtsInstall() {
        if (tts != null) {
            tts.promptInstallTtsData();
        } else {
            // tts 未初始化时也提供入口
            try {
                Intent i = new Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    /** 已保存的 LLM 配置（供配置页测试连接） */
    public String getApiBase() { return settings != null ? settings.getApiBase() : ""; }
    public String getApiKey() { return settings != null ? settings.getApiKey() : ""; }
    public String getModel() { return settings != null ? settings.getModel() : ""; }

    /** 热切换大脑配置：按 Settings 当前值重建 LLM 客户端 */
    public void reconfigureBrain() {
        if (aiCore != null) {
            aiCore.applyConfig();
        }
    }

    private com.digitallife.mcp.McpServerManager mcpManager;

    private com.digitallife.mcp.McpServerManager getMcpManager() {
        if (mcpManager == null) {
            mcpManager = new com.digitallife.mcp.McpServerManager(this);
        }
        return mcpManager;
    }

    /** 后台连接已保存的 MCP 服务器；完成后热刷新大脑工具 */
    private void connectMcpServersAsync() {
        com.digitallife.mcp.McpServerManager mgr = getMcpManager();
        if (mgr.list().isEmpty()) return;
        new Thread(() -> {
            try {
                mgr.connectAll();
                android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
                h.post(() -> reconfigureBrain());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void openSettings() {
        try {
            Intent i = new Intent(this, com.digitallife.ui.MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {}
    }

    // ================= 通知 =================

    private void initNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.channel_overlay),
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.channel_overlay_desc));
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, com.digitallife.ui.MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Intent stop = new Intent(this, PetService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(this, 1, stop,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(getString(R.string.notify_overlay_title))
                .setContentText(getString(R.string.notify_overlay_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pi)
                .setOngoing(true);
        b.addAction(0, getString(R.string.action_open), pi);
        b.addAction(0, getString(R.string.action_stop), piStop);
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        instance = null;
        if (displayListener != null) {
            try {
                DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
                if (dm != null) dm.unregisterDisplayListener(displayListener);
            } catch (Exception ignored) {}
            displayListener = null;
        }
        // 注销无障碍服务监听器，避免匿名内部类持有 Service 引用造成泄漏
        com.digitallife.service.PetAccessibilityService.setListener(null);
        if (careAutomation != null) careAutomation.stop();
        if (memoryExtractor != null) {
            memoryExtractor.cancel();
            memoryExtractor = null;
        }
        if (heartbeat != null) heartbeat.stop();
        if (aiCore != null) aiCore.stop();
        if (thoughtLoop != null) thoughtLoop.stop();
        vitalsTicking = false;
        // 取消 1Hz 自循环 tick，避免已 post 的 Runnable 仍持有 Service 引用
        mainHandler.removeCallbacks(vitalsTickRunnable);
        vitals = null;
        thoughtLoop = null;
        if (tts != null) { tts.stop(); tts.destroy(); }
        if (stt != null) stt.destroy();
        if (overlayView != null) {
            // 先在 GL 线程释放 Native Cubism 单例和 GL 纹理，
            // 避免重启时新 EGL Context 复用旧资源导致模型透明
            overlayView.releaseNative();
            if (windowManager != null) {
                try {
                    windowManager.removeView(overlayView);
                } catch (Exception ignored) {}
            }
        }
        overlayView = null;
        super.onDestroy();
    }
}