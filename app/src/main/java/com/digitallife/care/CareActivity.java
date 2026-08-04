package com.digitallife.care;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.util.ApiManager;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;

/**
 * 护理大脑对话界面。
 * 独立于主配置界面，通过聊天与 AI 交互管理模型和动作。
 */
public class CareActivity extends Activity {

    private static final int REQ_PICK_ZIP = 2001;

    private CareAI careAI;
    private LinearLayout chatContainer;
    private EditText etInput;
    private Button btnSend, btnAttach, btnBack, btnClear;
    private ScrollView scrollView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView tvStatus;
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;

        careAI = new CareAI(this);
        careAI.setListener(new CareAI.CareListener() {
            @Override
            public void onDelta(String text) {
                safeRun(() -> appendToLastMessage(text));
            }

            @Override
            public void onToolCall(String toolName, JSONObject args) {
                safeRun(() -> addMessage("system", "调用工具: " + toolName + "(" + args.toString() + ")", Color.rgb(200, 180, 100)));
            }

            @Override
            public void onDone(String fullText) {
                safeRun(() -> {
                    setStatus("就绪");
                    btnSend.setEnabled(true);
                });
            }

            @Override
            public void onError(String error) {
                safeRun(() -> {
                    addMessage("system", "错误: " + error, Color.rgb(220, 80, 80));
                    setStatus("出错");
                    btnSend.setEnabled(true);
                });
            }
        });

        buildUi();
        addMessage("system", "你好，我是护理大脑。\n我可以帮你管理 Live2D 模型、创建/修改动作、管理工作流和定时任务。\n发一个消息开始吧。", Color.rgb(130, 125, 150));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (careAI != null) careAI.cancel();
    }

    private void safeRun(Runnable r) {
        handler.post(() -> {
            if (!destroyed) r.run();
        });
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(247, 246, 251));

        // 顶部栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setPadding(dp(12), dp(12), dp(12), dp(8));
        topBar.setBackgroundColor(Color.rgb(96, 74, 210));

        btnBack = new Button(this);
        btnBack.setText("← 返回");
        btnBack.setTextColor(Color.WHITE);
        btnBack.setBackgroundColor(Color.TRANSPARENT);
        btnBack.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("护理大脑");
        title.setTextSize(18f);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(16), 0, 0, 0);
        topBar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnClear = new Button(this);
        btnClear.setText("清空");
        btnClear.setTextColor(Color.rgb(200, 200, 255));
        btnClear.setBackgroundColor(Color.TRANSPARENT);
        btnClear.setPadding(dp(8), dp(4), dp(8), dp(4));
        btnClear.setOnClickListener(v -> {
            careAI.clearHistory();
            chatContainer.removeAllViews();
            addMessage("system", "对话已清空。", Color.rgb(130, 125, 150));
        });
        topBar.addView(btnClear, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 聊天区域
        scrollView = new ScrollView(this);
        scrollView.setPadding(dp(12), dp(8), dp(12), dp(8));

        chatContainer = new LinearLayout(this);
        chatContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(chatContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部输入区
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bottomBar.setBackgroundColor(Color.WHITE);

        btnAttach = new Button(this);
        btnAttach.setText("📎");
        btnAttach.setTextSize(18f);
        btnAttach.setBackgroundColor(Color.TRANSPARENT);
        btnAttach.setPadding(dp(8), dp(8), dp(8), dp(8));
        btnAttach.setOnClickListener(v -> pickZip());
        bottomBar.addView(btnAttach, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        etInput = new EditText(this);
        etInput.setHint("输入消息…");
        etInput.setTextSize(14f);
        etInput.setPadding(dp(8), dp(8), dp(8), dp(8));
        etInput.setBackgroundResource(android.R.drawable.edit_text);
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

        // 状态提示
        tvStatus = new TextView(this);
        tvStatus.setText("就绪");
        tvStatus.setTextSize(11f);
        tvStatus.setTextColor(Color.rgb(130, 125, 150));
        tvStatus.setPadding(dp(8), 0, dp(8), 0);
        bottomBar.addView(tvStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void sendMessage() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty() || btnSend.isEnabled() == false) return;
        etInput.setText("");
        btnSend.setEnabled(false);
        addMessage("user", text, Color.rgb(60, 60, 80));
        setStatus("思考中…");
        careAI.sendMessage(text);
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
            Toast.makeText(this, "正在处理文件…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    // 复制到临时文件
                    File tempDir = new File(getCacheDir(), "care_uploads");
                    tempDir.mkdirs();
                    String fileName = "upload_" + System.currentTimeMillis() + ".zip";
                    File tempFile = new File(tempDir, fileName);
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         FileOutputStream out = new FileOutputStream(tempFile)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) out.write(buf);
                    }
                    final String path = tempFile.getAbsolutePath();
                    handler.post(() -> {
                        addMessage("user", "[上传文件: " + fileName + "]", Color.rgb(60, 60, 80));
                        setStatus("处理中…");
                        careAI.handleFile(fileName, path);
                    });
                } catch (Exception e) {
                    handler.post(() -> {
                        Toast.makeText(this, "文件处理失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }
    }

    // ============ UI 辅助 ============

    private void addMessage(String role, String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setLineSpacing(4f, 1f);
        tv.setTextColor(color);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        tv.setBackgroundColor(Color.rgb(255, 255, 255));
        tv.setAlpha(role.equals("user") ? 0.95f : 0.85f);
        // 设置圆角背景
        tv.setBackgroundResource(android.R.drawable.editbox_background);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        chatContainer.addView(tv, lp);
        scrollToBottom();
    }

    /** 追加文本到最后一条消息 */
    private void appendToLastMessage(String text) {
        int count = chatContainer.getChildCount();
        if (count > 0) {
            View last = chatContainer.getChildAt(count - 1);
            if (last instanceof TextView) {
                TextView tv = (TextView) last;
                tv.setText(tv.getText() + text);
                scrollToBottom();
            }
        }
    }

    private void scrollToBottom() {
        if (scrollView != null) {
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private void setStatus(String text) {
        if (tvStatus != null) tvStatus.setText(text);
    }

    private int dp(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}