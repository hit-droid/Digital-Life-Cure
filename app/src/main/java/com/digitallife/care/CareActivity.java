package com.digitallife.care;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 护理大脑对话界面（独立页面）。
 * 气泡聊天 UI：用户消息右对齐、AI 消息左对齐。支持上传模型 zip 自动解压分析。
 */
public class CareActivity extends Activity {

    private static final int REQ_PICK_ZIP = 2001;

    private CareAI careAI;
    private LinearLayout chatContainer;
    private EditText etInput;
    private Button btnSend, btnAttach, btnClear;
    private ScrollView scrollView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;
        careAI = CareAI.getInstance(this);
        careAI.setListener(new CareAI.CareListener() {
            @Override
            public void onDelta(String text) {
                safeRun(() -> appendToLastMessage(text));
            }

            @Override
            public void onToolCall(String toolName, JSONObject args, String toolCallId) {
                safeRun(() -> addToolBubble(toolName));
            }

            @Override
            public void onDone(String fullText) {
                safeRun(() -> {
                    btnSend.setEnabled(true);
                    btnSend.setText("发送");
                });
            }

            @Override
            public void onError(String error) {
                safeRun(() -> {
                    addAiBubble("⚠ " + error);
                    btnSend.setEnabled(true);
                    btnSend.setText("发送");
                });
            }
        });
        buildUi();
        addAiBubble("你好，我是护理大脑 🤖\n\n我可以帮你：\n· 上传模型 zip，自动解压并检查\n· 分析模型完整性（纹理/骨骼/动作）\n· 查看和编辑模型动作\n· 管理工作流和定时任务\n\n直接发消息，或点右下角上传模型压缩包。");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(238, 240, 245));

        // 顶部栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(dp(8), dp(12), dp(8), dp(12));
        topBar.setBackgroundColor(Color.rgb(96, 74, 210));

        Button btnBack = new Button(this);
        btnBack.setText("←");
        btnBack.setTextSize(18f);
        btnBack.setTextColor(Color.WHITE);
        btnBack.setBackgroundColor(Color.TRANSPARENT);
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("护理大脑");
        title.setTextSize(18f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        topBar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnClear = new Button(this);
        btnClear.setText("清空");
        btnClear.setTextSize(13f);
        btnClear.setTextColor(Color.rgb(210, 205, 255));
        btnClear.setBackgroundColor(Color.TRANSPARENT);
        btnClear.setOnClickListener(v -> {
            careAI.clearHistory();
            chatContainer.removeAllViews();
            addAiBubble("对话已清空，重新开始吧。");
        });
        topBar.addView(btnClear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 消息区
        scrollView = new ScrollView(this);
        chatContainer = new LinearLayout(this);
        chatContainer.setOrientation(LinearLayout.VERTICAL);
        chatContainer.setPadding(dp(10), dp(10), dp(10), dp(10));
        scrollView.addView(chatContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 输入区
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(dp(8), dp(6), dp(8), dp(10));
        bottomBar.setBackgroundColor(Color.WHITE);

        btnAttach = new Button(this);
        btnAttach.setText("📎");
        btnAttach.setTextSize(18f);
        btnAttach.setBackgroundColor(Color.TRANSPARENT);
        btnAttach.setPadding(dp(10), dp(6), dp(10), dp(6));
        btnAttach.setOnClickListener(v -> pickZip());
        bottomBar.addView(btnAttach, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        etInput = new EditText(this);
        etInput.setHint("输入消息…");
        etInput.setTextSize(14f);
        etInput.setBackgroundResource(android.R.drawable.edit_text);
        etInput.setSingleLine(false);
        etInput.setMinLines(1);
        etInput.setMaxLines(4);
        etInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        bottomBar.addView(etInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnSend = new Button(this);
        btnSend.setText("发送");
        btnSend.setTextColor(Color.WHITE);
        btnSend.setBackgroundColor(Color.rgb(96, 74, 210));
        btnSend.setPadding(dp(16), dp(8), dp(16), dp(8));
        btnSend.setOnClickListener(v -> sendMessage());
        bottomBar.addView(btnSend, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void sendMessage() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty() || !btnSend.isEnabled()) return;
        etInput.setText("");
        btnSend.setEnabled(false);
        btnSend.setText("…");
        addUserBubble(text);
        careAI.sendMessage(text);
        hideKeyboard();
    }

    private void pickZip() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/zip", "application/x-zip-compressed"});
            startActivityForResult(i, REQ_PICK_ZIP);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_ZIP && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) return;
            Toast.makeText(this, "正在读取文件…", Toast.LENGTH_SHORT).show();
            btnSend.setEnabled(false);
            btnSend.setText("…");
            new Thread(() -> {
                try {
                    File tempDir = new File(getCacheDir(), "care_uploads");
                    tempDir.mkdirs();
                    String fileName = "model_" + System.currentTimeMillis() + ".zip";
                    File tempFile = new File(tempDir, fileName);
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(tempFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf);
                    }
                    final String path = tempFile.getAbsolutePath();
                    final long size = tempFile.length();
                    handler.post(() -> {
                        addUserBubble("📦 上传模型包\n" + fileName + " (" + formatSize(size) + ")");
                        // 主动触发：先解压分析，让 AI 立即反馈，不等用户询问
                        careAI.analyzeUploadedZip(fileName, path);
                    });
                } catch (Exception e) {
                    handler.post(() -> {
                        btnSend.setEnabled(true);
                        btnSend.setText("发送");
                        Toast.makeText(this, "文件读取失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }
    }

    // ============ 气泡渲染 ============

    private void addUserBubble(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.RIGHT);
        TextView tv = makeBubble(text, Color.rgb(96, 74, 210), Color.WHITE, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        lp.bottomMargin = dp(5);
        lp.leftMargin = dp(60);
        tv.setMaxWidth(dp(280));
        row.addView(tv, lp);
        chatContainer.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollToBottom();
    }

    private void addAiBubble(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.LEFT);
        TextView tv = makeBubble(text, Color.WHITE, Color.rgb(50, 50, 70), true);
        tv.setTag("ai");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(5);
        lp.bottomMargin = dp(5);
        lp.rightMargin = dp(60);
        tv.setMaxWidth(dp(280));
        row.addView(tv, lp);
        chatContainer.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollToBottom();
    }

    private void addToolBubble(String toolName) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.LEFT);
        TextView tv = new TextView(this);
        tv.setText("⚙ " + toolName + " …");
        tv.setTextSize(11f);
        tv.setTextColor(Color.rgb(140, 130, 165));
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        tv.setTag("tool");
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.rgb(235, 232, 248));
        gd.setCornerRadius(dp(10));
        tv.setBackground(gd);
        row.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        chatContainer.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollToBottom();
    }

    private TextView makeBubble(String text, int bg, int fg, boolean isAi) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setLineSpacing(4f, 1f);
        tv.setTextColor(fg);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(bg);
        gd.setCornerRadii(new float[]{
                dp(isAi ? 4 : 14), dp(isAi ? 4 : 14),
                dp(14), dp(14),
                dp(isAi ? 14 : 4), dp(isAi ? 14 : 4),
                dp(14), dp(14)});
        tv.setBackground(gd);
        return tv;
    }

    private void appendToLastMessage(String text) {
        int count = chatContainer.getChildCount();
        if (count > 0) {
            View last = chatContainer.getChildAt(count - 1);
            if (last instanceof LinearLayout) {
                LinearLayout row = (LinearLayout) last;
                if (row.getChildCount() > 0 && row.getChildAt(0) instanceof TextView) {
                    TextView tv = (TextView) row.getChildAt(0);
                    if ("ai".equals(tv.getTag())) {
                        tv.setText(tv.getText() + text);
                        scrollToBottom();
                        return;
                    }
                }
            }
        }
        // 若当前最后不是 AI 气泡（如刚发完用户消息），则新开一个 AI 气泡
        addAiBubble(text);
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
    }

    private void safeRun(Runnable r) {
        handler.post(() -> {
            if (!destroyed) r.run();
        });
    }

    private int dp(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}