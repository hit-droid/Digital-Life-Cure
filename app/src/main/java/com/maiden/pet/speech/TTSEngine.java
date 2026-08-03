package com.maiden.pet.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * 语音合成：Android 系统 TextToSpeech，免费离线中文语音。
 * 发音时把「正在说话」状态同步给渲染引擎驱动口型。
 * 初始化失败自动重试，并可在配置页查询引擎状态。
 */
public class TTSEngine implements TextToSpeech.OnInitListener {

    public interface Listener {
        void onSpeakingStart();
        void onSpeakingDone();
        void onError(String error);
        /** 初始化结束（无论成功与否），供上层决定语音降级策略 */
        void onInitResult(boolean ok, String error);
    }

    private final Context ctx;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready = false;
    private Listener listener;
    private volatile boolean speaking = false;
    private String pendingText = null;
    private int utteranceCount = 0;

    private int initStatus = 0;   // 0 初始化中 / 1 成功 / -1 失败
    private String lastError = null;
    private int initAttempts = 0;

    public TTSEngine(Context ctx, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
        initTts();
    }

    private void initTts() {
        if (tts != null) {
            try { tts.shutdown(); } catch (Exception ignored) {}
        }
        tts = new TextToSpeech(ctx, this);
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            initStatus = 1;
            int res = tts.setLanguage(Locale.CHINESE);
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                res = tts.setLanguage(Locale.CHINA);
            }
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                res = tts.setLanguage(Locale.US);
            }
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) { }

                @Override
                public void onDone(String utteranceId) {
                    utteranceCount--;
                    if (utteranceCount <= 0) {
                        utteranceCount = 0;
                        speaking = false;
                        if (listener != null) listener.onSpeakingDone();
                    }
                }

                @Override
                @Deprecated
                public void onError(String utteranceId) { }

                @Override
                public void onError(String utteranceId, int errorCode) {
                    speaking = false;
                    if (listener != null) listener.onError("语音合成失败");
                }
            });
            ready = true;
            if (pendingText != null) {
                String t = pendingText;
                pendingText = null;
                speak(t);
            }
            if (listener != null) listener.onInitResult(true, null);
        } else {
            initAttempts++;
            if (initAttempts < 3) {
                // 系统 TTS 引擎加载较慢时自动重试
                handler.postDelayed(this::initTts, 1000);
            } else {
                initStatus = -1;
                lastError = "TTS 初始化失败（语音引擎未安装或已被禁用）";
                if (listener != null) listener.onError(lastError);
                if (listener != null) listener.onInitResult(false, lastError);
            }
        }
    }

    /** 引导用户安装/启用系统 TTS 数据 */
    public void promptInstallTtsData() {
        try {
            Intent i = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Exception ignored) {}
    }

    public boolean isReady() { return ready; }

    /** 诊断用状态文本 */
    public String getState() {
        if (initStatus == 1) return "正常";
        if (initStatus == -1) return "失败：" + lastError;
        return "初始化中…";
    }

    /** 合成并播放一段文本（立即打断当前播放） */
    public void speak(String text) {
        if (text == null || text.isEmpty()) return;
        if (!ready) {
            pendingText = text;
            return;
        }
        speaking = true;
        utteranceCount = 1;
        if (listener != null) listener.onSpeakingStart();
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pet_tts_" + System.currentTimeMillis());
    }

    /** 追加到播放队列末尾（不打断当前播放，用于流式追加） */
    public void speakAppend(String text) {
        if (text == null || text.isEmpty() || !ready) return;
        if (!speaking) {
            speak(text);
            return;
        }
        utteranceCount++;
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, "pet_tts_" + System.currentTimeMillis());
    }

    /** 打断当前朗读 */
    public void stop() {
        if (tts != null && speaking) {
            tts.stop();
            speaking = false;
            utteranceCount = 0;
            if (listener != null) listener.onSpeakingDone();
        }
    }

    public boolean isSpeaking() { return speaking; }

    public void setListener(Listener l) { this.listener = l; }

    public void destroy() {
        handler.removeCallbacksAndMessages(null);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }
}
