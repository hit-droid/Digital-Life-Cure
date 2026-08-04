package com.digitallife.speech;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * 语音识别：Android 系统 SpeechRecognizer（免费离线）。
 * 支持用户按住说话（按住期间录音，松开发送识别）。
 */
public class STTEngine {

    public interface Listener {
        void onListeningStart();
        void onResult(String text);
        void onError(String error);
        void onEnd();
    }

    private SpeechRecognizer recognizer;
    private Listener listener;
    private boolean listening = false;
    private String lastError = null;

    public STTEngine(Context ctx, Listener l) {
        this.listener = l;
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx.getApplicationContext());
            if (recognizer == null) {
                lastError = "系统未安装语音识别服务";
                return;
            }
            recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) { }

            @Override
            public void onBeginningOfSpeech() { }

            @Override
            public void onRmsChanged(float rmsdB) { }

            @Override
            public void onBufferReceived(byte[] buffer) { }

            @Override
            public void onEndOfSpeech() {
                if (listener != null) listener.onEnd();
            }

            @Override
            public void onError(int error) {
                listening = false;
                if (listener != null) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH) listener.onError("没有听清，再说一次好吗？");
                    else if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) listener.onError("没有听到声音");
                    else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) listener.onError("缺少录音权限");
                    else listener.onError("语音识别出错");
                }
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                ArrayList<String> list = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    if (listener != null) listener.onResult(list.get(0));
                }
            }

            @Override
            public void onPartialResults(Bundle partialResults) { }

            @Override
            public void onEvent(int eventType, Bundle params) { }
        });
        } catch (Exception e) {
            recognizer = null;
            lastError = "语音识别服务不可用：" + e.getMessage();
        }
    }

    /** 开始监听（按住说话） */
    public void startListening() {
        if (listening) return;
        if (recognizer == null) {
            if (listener != null) listener.onError("语音识别服务不可用：" + (lastError == null ? "未知原因" : lastError));
            return;
        }
        listening = true;
        if (listener != null) listener.onListeningStart();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        recognizer.startListening(intent);
    }

    public boolean isAvailable() { return recognizer != null; }

    /** 诊断用状态文本 */
    public String getState() {
        if (recognizer != null) return "正常";
        return "不可用：" + (lastError == null ? "未知原因" : lastError);
    }

    public void stopListening() {
        if (recognizer != null) {
            try {
                recognizer.stopListening();
            } catch (Exception ignored) {
            }
        }
        listening = false;
    }

    public boolean isListening() { return listening; }

    public void destroy() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
    }
}
