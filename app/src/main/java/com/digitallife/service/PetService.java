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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.digitallife.R;
import com.digitallife.brain.AICore;
import com.digitallife.brain.Heartbeat;
import com.digitallife.model.ModelManager;
import com.digitallife.util.CrashHandler;
import com.digitallife.util.MemoryStore;
import com.digitallife.util.Settings;
import com.digitallife.ui.PetOverlayView;
import com.digitallife.render.Live2DNative;
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

    private static final String CHANNEL_ID = "pet_overlay";
    private static final int NOTIFY_ID = 1001;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private PetOverlayView overlayView;
    private WindowManager.LayoutParams overlayParams;

    private AICore aiCore;
    private TTSEngine tts;
    private STTEngine stt;
    private Settings settings;
    private Heartbeat heartbeat;
    private MemoryStore memory;

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
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_TOGGLE_VOICE.equals(intent.getAction())) {
            voiceEnabled = !voiceEnabled;
            settings.setVoiceEnabled(voiceEnabled);
            return START_STICKY;
        }
        ensureRunning();
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

        aiCore = new AICore(settings);
        aiCore.setOutput(this);
        aiCore.start();
        memory = aiCore.getMemory();

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

    // ================= 悬浮窗交互 =================

    @Override
    public void onTap() {
        if (aiCore == null) return;
        aiCore.onUserInteraction();
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
        aiCore.onUserSays(text);
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
    }

    // ================= AICore.Output =================

    @Override
    public void onLive2DParam(String paramId, float value) {
        if (overlayView != null) overlayView.setParameterValue(paramId, value);
    }

    @Override
    public void onExpression(String name) {
        if (overlayView != null) overlayView.setExpression(name);
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

    @Override
    public void onThinking(boolean thinking) {
        if (overlayView != null && thinking) {
            overlayView.setExpression("F01");
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
        if (heartbeat != null) heartbeat.stop();
        if (aiCore != null) aiCore.stop();
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