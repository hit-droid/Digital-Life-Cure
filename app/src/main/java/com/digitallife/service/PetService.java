package com.digitallife.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
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
import com.digitallife.care.ComboPlayer;
import com.digitallife.brain.Heartbeat;
import com.digitallife.model.ModelInspector;
import com.digitallife.model.ModelManager;
import com.digitallife.util.CrashHandler;
import com.digitallife.util.MemoryStore;
import com.digitallife.util.OverlayGeometry;
import com.digitallife.util.Settings;
import com.digitallife.util.ThoughtStore;
import com.digitallife.ui.PetOverlayView;
import com.digitallife.ui.pet.EmotionExpression;
import com.digitallife.ui.pet.PetQuickMenu;
import com.digitallife.ui.pet.PetTouchReaction;
import com.digitallife.ui.pet.TouchBindings;
import com.digitallife.render.Live2DNative;
import com.digitallife.render.Live2DGLView;
import com.digitallife.render.ContinuousMotionEngine;
import com.digitallife.render.LipSync;
import com.digitallife.speech.STTEngine;
import com.digitallife.speech.TTSEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
    /** v1.183.0：说话口型节奏。TTS 给不出音频振幅，按文本音节时间线驱动 ParamMouthOpenY */
    private volatile LipSync lipSync;
    /** 口型刷新间隔：40ms ≈ 25fps，足够跟住音节又不至于每帧抢 GL 线程 */
    private static final long MOUTH_TICK_MS = 40L;
    private com.digitallife.brain.ProactiveEngine proactiveEngine; // v1.24.0
    private long lastProactiveTickMs = 0;

    private WindowManager windowManager;
    private PetOverlayView overlayView;
    private WindowManager.LayoutParams overlayParams;
    private DisplayManager.DisplayListener displayListener;

    public AICore aiCore; // v1.24.0: AgentConsoleActivity 需访问
    private CareAutomation careAutomation;
    /** v1.178.0：开机自动播放连招的播放器（懒建，就绪时触发一次） */
    private ComboPlayer comboPlayer;
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
    /** v1.135.0（Issue #27）：长按菜单里的「锁定位置」状态；锁拖拽，不锁点击 */
    private boolean positionLocked = false;
    /** v1.135.0：手动换表情的当前表情，菜单里点一次切下一个 */
    private String currentExpression = "F01";

    /** v1.140.0（Issue #48）：把 EmotionState 映射为待机表情，带迟滞与最短停留 */
    private final EmotionExpression emotionExpression = new EmotionExpression();
    /** 最近一次用户/对话交互时刻；自主表情在此后 OVERRIDE_COOLDOWN_MS 内让位 */
    private long lastInteractionMs = 0L;
    /** 交互后自主表情让位时长：期间不抢用户/对话刚设的表情 */
    private static final long OVERRIDE_COOLDOWN_MS = 6000L;

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
            long now = System.currentTimeMillis();
            applyAutonomousExpression(now);
            // v1.24.0：每 30 分钟巡检一次主动行为
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
        // v1.189.0：窗口尺寸统一走 OverlayGeometry（原 0.35/0.57 的窄条 + 两处重复魔数）
        int overlayW = OverlayGeometry.windowWidth(size.x, settings.getScale());
        int overlayH = OverlayGeometry.windowHeight(size.y, settings.getScale());

        overlayView = new PetOverlayView(this, this);
        positionLocked = settings.isPetLocked();
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
            // v1.189.1：窗口变大（缩放上限提高）后旧的持久化坐标可能越界，先夹回屏内
            overlayParams.x = OverlayGeometry.clampX((int) px, overlayW, size.x);
            overlayParams.y = OverlayGeometry.clampY((int) py, overlayH, size.y);
        } else {
            overlayParams.x = size.x / 2 - overlayW / 2;
            overlayParams.y = (int) (size.y * 0.18f);
        }
        try {
            windowManager.addView(overlayView, overlayParams);
        } catch (Exception e) {
            // v1.191.0：添加窗口失败（如窗口权限被撤销 / 悬浮窗被系统拦）时必须把 overlayView 置空。
            // 此前只 e.printStackTrace()，overlayView 仍非 null → ensureRunning() 开头的
            // `if (overlayView != null) return;` 会永远短路，用户再也起不来桌宠，只能重启 App。
            e.printStackTrace();
            overlayView = null;
            overlayParams = null;
            stopForeground(true);
            stopSelf();
            return;
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

            // v1.136.0：详情卡片的标题名与六维情绪（Issue #27 第 1 条「可点开详情」收尾）
            @Override
            public String name() {
                return settings == null ? "" : settings.getPetName();
            }

            @Override
            public float emotion(String dim) {
                return aiCore == null ? 0f : aiCore.getEmotion().get(dim);
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

    /**
     * v1.140.0（Issue #48）：情绪外显。
     *
     * <p>只在「没有其它东西在演」时接管待机表情：L2 状态机非 IDLE（犯困/无聊/生气/高兴
     * 各有姿态）、有动作在播、或刚发生过用户/对话交互的冷却期内，都让位不抢。
     * 真正切换仍交给 {@link EmotionExpression} 的迟滞 + 最短停留判定；变了才写 UI，
     * 顺带按表情配一个自主小动作。</p>
     */
    private void applyAutonomousExpression(long now) {
        if (aiCore == null || overlayView == null) return;
        if (vitals != null && vitals.getState() != PetState.IDLE) return;
        if (overlayView.isMotionPlaying()) return;
        if (now - lastInteractionMs < OVERRIDE_COOLDOWN_MS) return;
        String changed = emotionExpression.update(aiCore.getEmotion(), now);
        if (changed == null) return;
        overlayView.setExpression(changed);
        String motion = EmotionExpression.motionFor(changed);
        if (motion != null) overlayView.setMotion(motion);
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
        maybeAutoPlayStartupCombo();
    }

    /**
     * 开机自动播放连招（v1.178.0）：配置的连招若属于当前默认模型，模型就绪即播放一次。
     * 切换模型（{@code nativeChangeScene}）与播放动作（{@code nativeStartMotion}）都经 GL 线程
     * 同一 FIFO {@code queueEvent}，故「先切后播」天然有序，无需延迟等待；模型不匹配则静默跳过。
     */
    private void maybeAutoPlayStartupCombo() {
        if (settings == null) return;
        final String model = settings.getStartupComboModel();
        final String combo = settings.getStartupComboName();
        if (combo == null || combo.isEmpty()) return;
        if (model == null || model.isEmpty() || !model.equals(settings.getDefaultModelDir())) return;
        if (comboPlayer == null) {
            comboPlayer = new ComboPlayer(this, text -> {
                if (overlayView != null) {
                    overlayView.showBubble(text, Math.max(2f, text.length() * 0.06f));
                }
            });
        }
        comboPlayer.play(model, combo);
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
    public void onTapZone(String zone) {
        // v1.137.0（Issue #37）：摸头 / 戳身子的差异化反馈。
        // 只读调用 EmotionState 公开方法，不改 brain/ 内部算法；表情与动作已在 PetOverlayView 落定。
        PetTouchReaction.Reaction reaction = PetTouchReaction.reactionFor(zone);
        // v1.176.0：若用户绑定覆盖了表情，同步内部状态时要跟着改，避免冷却期一过就抢回旧表情
        String eventId = (zone == null || zone.isEmpty()) ? TouchBindings.EVENT_NONE : zone;
        TouchBindings.Binding bind = TouchBindings.get(this, eventId);
        String expr = TouchBindings.effective(bind.expression, reaction.expression);
        if (expr != null) {
            emotionExpression.syncCurrent(expr, System.currentTimeMillis());
        }
        if (aiCore != null) {
            if (reaction.intimacy != 0f) {
                aiCore.getEmotion().addIntimacy(reaction.intimacy);
            }
            for (String dim : reaction.emotionDims()) {
                aiCore.getEmotion().apply(dim, reaction.emotion(dim));
            }
        }
        if (reaction.bubble != null && overlayView != null) {
            overlayView.showBubble(reaction.bubble, 2.2f);
        }
    }

    @Override
    public void onDoubleTap() {
        if (aiCore == null) return;
        aiCore.onUserInteraction();
        noteUserInteraction();
        // v1.176.0：双击同样可绑表情 / 动作（默认表情 F02、无动作）
        TouchBindings.Binding bind = TouchBindings.get(this, TouchBindings.EVENT_DOUBLE_TAP);
        String expr = TouchBindings.effective(bind.expression, "F02");
        if (expr != null) {
            overlayView.setExpression(expr);
            emotionExpression.syncCurrent(expr, System.currentTimeMillis());
        }
        String group = TouchBindings.effective(bind.motionGroup, null);
        if (group != null && overlayView != null) {
            overlayView.setMotion(group, new java.util.Random().nextInt(6), 3);
        }
        overlayView.showBubble("嘿嘿~ 戳我干嘛呀！", 2f);
    }

    @Override
    public void onLongPress() {
        // v1.135.0（Issue #27 第 2 条）：长按不再直接跳设置，改为弹快捷菜单
        if (overlayView == null) return;
        noteUserInteraction();
        overlayView.showQuickMenu(PetQuickMenu.items(positionLocked), new PetOverlayView.MenuCallback() {
            @Override
            public void onItem(int index) {
                onQuickMenuItem(index);
            }
        });
    }

    /** 长按菜单的动作分发；索引取 {@link PetQuickMenu} 的常量，不靠文案匹配 */
    private void onQuickMenuItem(int index) {
        switch (index) {
            case PetQuickMenu.ITEM_EXPRESSION:
                currentExpression = PetQuickMenu.nextExpression(currentExpression);
                emotionExpression.syncCurrent(currentExpression, System.currentTimeMillis());
                if (overlayView != null) {
                    overlayView.setExpression(currentExpression);
                    overlayView.showBubble("表情：" + PetQuickMenu.expressionLabel(currentExpression), 1.6f);
                }
                break;
            case PetQuickMenu.ITEM_MODEL:
                // v1.186.0：快捷菜单直接换模型，不用再进设置页翻模型列表
                showModelChooser();
                break;
            case PetQuickMenu.ITEM_SNAPSHOT:
                // v1.186.0：给桌宠拍张照（只截模型窗口，带透明通道）
                takePetSnapshot();
                break;
            case PetQuickMenu.ITEM_DOCK_EDGE:
                // 用户明确点了贴边，就把开关打开并立刻吸附（几何复用 #26 的 OverlayDock）
                if (settings != null) settings.setEdgeDockEnabled(true);
                snapToEdgeIfNeeded();
                break;
            case PetQuickMenu.ITEM_CENTER:
                moveOverlayToCenter();
                break;
            case PetQuickMenu.ITEM_LOCK:
                positionLocked = !positionLocked;
                if (settings != null) settings.setPetLocked(positionLocked);
                if (overlayView != null) {
                    overlayView.showBubble(positionLocked ? "位置锁好啦，拖不动我" : "解锁啦，可以拖了", 1.6f);
                }
                break;
            case PetQuickMenu.ITEM_SETTINGS:
            default:
                openSettings();
                break;
        }
    }

    /**
     * v1.186.0：给桌宠拍张照。
     *
     * <p>普通 App 无法截别的应用，但可以截自己的悬浮窗——{@link Live2DGLView} 在 GL 线程
     * 用 {@code glReadPixels} 读回当前帧（透明背景一并保留），再交后台线程写入相册。</p>
     */
    private void takePetSnapshot() {
        if (overlayView == null) return;
        Live2DGLView gl = overlayView.getLive2DView();
        if (gl == null) return;
        overlayView.showBubble("咔嚓~", 1.2f);
        gl.requestSnapshot(bmp -> {
            if (bmp == null) {
                if (overlayView != null) overlayView.showBubble("还没画好，稍后再试", 1.8f);
                return;
            }
            // PNG 压缩与相册写入都不该卡主线程
            new Thread(() -> savePetSnapshot(bmp), "pet-snapshot").start();
        });
    }

    /** 写入系统相册；API 29+ 走 MediaStore，旧版本写应用私有 Pictures 再触发媒体扫描 */
    private void savePetSnapshot(Bitmap bmp) {
        String name = "pet_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
        boolean ok = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DigitalLife");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new java.io.IOException("MediaStore insert failed");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    if (os == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                        throw new java.io.IOException("compress failed");
                    }
                }
                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
            } else {
                File dir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "DigitalLife");
                if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("mkdirs failed");
                File f = new File(dir, name);
                try (FileOutputStream fos = new FileOutputStream(f)) {
                    if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                        throw new java.io.IOException("compress failed");
                    }
                }
                MediaScannerConnection.scanFile(this,
                        new String[]{f.getAbsolutePath()}, new String[]{"image/png"}, null);
            }
            ok = true;
        } catch (Throwable t) {
            Log.e(TAG, "savePetSnapshot failed", t);
        } finally {
            bmp.recycle();
        }
        final boolean saved = ok;
        if (overlayView != null) {
            overlayView.showBubble(saved ? "拍好啦，已存到相册" : "保存失败，请稍后再试", 2f);
        }
    }

    /**
     * v1.186.0：快捷菜单的模型二级菜单。
     *
     * <p>直接读 native 模型列表（与 {@code CareModelsActivity} 同源），首项「返回」回到主菜单；
     * 其余项按 native 索引顺序排列，回调索引减一就是 {@link #switchToModel(int)} 的下标，
     * 不做名字匹配，避免目录名重复/改名时切错。</p>
     */
    private void showModelChooser() {
        if (overlayView == null) return;
        final int count;
        try {
            count = Live2DNative.nativeGetModelCount();
        } catch (Throwable t) {
            Log.e(TAG, "showModelChooser count error", t);
            return;
        }
        if (count <= 0) {
            overlayView.showBubble("还没有可用的模型", 1.8f);
            return;
        }
        final String current = settings != null ? settings.getDefaultModelDir() : null;
        String[] labels = new String[count];
        for (int i = 0; i < count; i++) {
            String dir = Live2DNative.nativeGetModelDirName(i);
            if (dir == null || dir.isEmpty()) dir = "模型 " + (i + 1);
            labels[i] = dir.equals(current) ? dir + "（当前）" : dir;
        }
        overlayView.showQuickMenu(PetQuickMenu.modelMenu(labels), new PetOverlayView.MenuCallback() {
            @Override
            public void onItem(int index) {
                if (index <= 0) {
                    // 「返回」：重新弹主菜单
                    onLongPress();
                    return;
                }
                int modelIndex = index - 1;
                boolean same = modelIndex < labels.length && labels[modelIndex].endsWith("（当前）");
                if (!same) switchToModel(modelIndex);
                if (overlayView != null) {
                    String name = modelIndex < labels.length
                            ? labels[modelIndex].replace("（当前）", "") : "";
                    overlayView.showBubble(same ? "已经在用 " + name + " 啦" : "换成 " + name + " 啦", 1.8f);
                }
            }
        });
    }

    /** 「回到中间」：把窗口挪回屏幕正中并持久化 */
    private void moveOverlayToCenter() {
        if (windowManager == null || overlayView == null || overlayParams == null) return;
        try {
            Point size = new Point();
            windowManager.getDefaultDisplay().getRealSize(size);
            int x = PetQuickMenu.centerX(overlayParams.width, size.x);
            int y = PetQuickMenu.centerY(overlayParams.height, size.y);
            if (x == overlayParams.x && y == overlayParams.y) return;
            overlayParams.x = x;
            overlayParams.y = y;
            windowManager.updateViewLayout(overlayView, overlayParams);
            if (settings != null) settings.setOverlayPos(x, y);
        } catch (Exception ignored) {
        }
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
            // v1.132.0：松手吸附到最近的左/右边缘。
            // v1.135.0：菜单刚弹出时不要吸附，否则长按出菜单的瞬间窗口会跳一下；
            // 锁了位置也不能吸附，否则「锁定」形同虚设
            if (!positionLocked && !overlayView.isQuickMenuShowing()) snapToEdgeIfNeeded();
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
        if (positionLocked) return; // v1.135.0：锁定位置时不再跟随手指（点击/长按照常，菜单里能解锁）
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
            int overlayW = OverlayGeometry.windowWidth(size.x, settings.getScale());
            int overlayH = OverlayGeometry.windowHeight(size.y, settings.getScale());
            int oldW = overlayParams.width;
            int oldH = overlayParams.height;
            if (oldW > 0 && oldH > 0 && (oldW != overlayW || oldH != overlayH)) {
                overlayParams.x = (int) (overlayParams.x * (float) overlayW / oldW);
                overlayParams.y = (int) (overlayParams.y * (float) overlayH / oldH);
            }
            overlayParams.width = overlayW;
            overlayParams.height = overlayH;
            overlayParams.x = OverlayGeometry.clampX(overlayParams.x, overlayW, size.x);
            overlayParams.y = OverlayGeometry.clampY(overlayParams.y, overlayH, size.y);
            windowManager.updateViewLayout(overlayView, overlayParams);
        } catch (Exception ignored) {
        }
    }

    /**
     * v1.189.0：设置页调整「桌宠大小」后立即生效。
     *
     * <p>v1.191.0：不再复用旋转重建的 {@link #resizeOverlayForDisplay()}。那条路径会把坐标
     * 按「新旧宽高比」等比迁移（那是横竖屏切换时为了保持相对位置的需要），但用户改缩放时
     * 桌宠不该乱跑——只是变大变小，位置应当原地不动。同时这里改完必须把坐标落盘，
     * 否则下次启动仍是旧位置（旋转路径不落盘是有意的：旋转后的临时位置不该覆盖用户设置）。</p>
     */
    public void applyOverlayScale() {
        if (windowManager == null || overlayView == null || overlayParams == null) return;
        try {
            Point size = new Point();
            windowManager.getDefaultDisplay().getRealSize(size);
            int overlayW = OverlayGeometry.windowWidth(size.x, settings.getScale());
            int overlayH = OverlayGeometry.windowHeight(size.y, settings.getScale());
            overlayParams.width = overlayW;
            overlayParams.height = overlayH;
            // 位置不变，仅夹回屏内（变大后可能超出边界）
            overlayParams.x = OverlayGeometry.clampX(overlayParams.x, overlayW, size.x);
            overlayParams.y = OverlayGeometry.clampY(overlayParams.y, overlayH, size.y);
            windowManager.updateViewLayout(overlayView, overlayParams);
            settings.setOverlayPos(overlayParams.x, overlayParams.y);
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
            // 只有真的会发声才进入说话态；文字模式不该让嘴一直张着（v1.183.0 修）
            // 说话态与口型由 TTSEngine.onSpeakingStart(text) 统一开启
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
        // 同上：说话态交给 TTS 回调，避免文字模式下嘴永久张开
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
    public void onSpeakingStart(String text) {
        if (overlayView == null) return;
        overlayView.setSpeaking(true);
        startLipSync(text);
    }

    @Override
    public void onSpeakingAppend(String text) {
        // TTS 队列续播：时间线顺延，不打断当前口型
        if (lipSync != null) lipSync.append(text);
    }

    /**
     * 启动文本驱动的口型时间线（v1.183.0）。
     * 此前说话时嘴恒定张开 0.3，整段话纹丝不动；现在按音节开合、标点处停顿，
     * 灵感来自开源 Live2D 挂件的「用文本节奏驱动 ParamMouthOpenY」。
     */
    private void startLipSync(String text) {
        mainHandler.removeCallbacks(mouthTick);
        lipSync = LipSync.forText(text).start(SystemClock.uptimeMillis());
        if (lipSync.durationMs() <= 0L) {
            lipSync = null;
            overlayView.setMouth(0f);
            return;
        }
        mainHandler.post(mouthTick);
    }

    /** 停止口型并闭嘴 */
    private void stopLipSync() {
        mainHandler.removeCallbacks(mouthTick);
        lipSync = null;
        if (overlayView != null) overlayView.setMouth(0f);
    }

    private final Runnable mouthTick = new Runnable() {
        @Override
        public void run() {
            final LipSync ls = lipSync;
            if (ls == null || overlayView == null) return;
            final long now = SystemClock.uptimeMillis();
            if (ls.isFinished(now)) {
                lipSync = null;
                overlayView.setMouth(0f);
                return;
            }
            overlayView.setMouth(ls.valueAt(now));
            mainHandler.postDelayed(this, MOUTH_TICK_MS);
        }
    };

    @Override
    public void onSpeakingDone() {
        if (overlayView != null) {
            overlayView.setSpeaking(false);
        }
        stopLipSync();
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

    /** 用户交互登记：喂饱生理状态机、重置独白空闲计时、让自主表情让位 */
    private void noteUserInteraction() {
        if (vitals != null) vitals.noteInteraction();
        if (thoughtLoop != null) thoughtLoop.noteInteraction();
        // v1.140.0（Issue #48）：交互后的冷却期内不抢表情（AI 回复也会经此触发）
        lastInteractionMs = System.currentTimeMillis();
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
        // v1.191.0：断开 MCP 连接并注销其注册的远程工具。
        // 此前 onDestroy 不处理 MCP，工具残留在全局 ToolRegistry 里 → 服务停了但大脑仍能
        // 调用一个已经不再连接的远程工具；下次启动又连一遍，同名工具反复叠加。
        if (mcpManager != null) {
            mcpManager.disconnectAll();
            mcpManager = null;
        }
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