package com.digitallife.care;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import com.digitallife.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 护理大脑对话界面（独立页面）。
 * 现代聊天 UI：彩色顶栏 + 时间戳 + 用户/AI/工具三类气泡 + 快捷设置切换。
 */
public class CareActivity extends Activity {

    private static final int REQ_PICK_ZIP = 2001;

    private CareAI careAI;
    private LinearLayout chatContainer;
    private EditText etInput;
    private ImageButton btnSend, btnAttach;
    private ScrollView scrollView;
    private TextView tvTitle;
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
                safeRun(() -> btnSend.setEnabled(true));
            }

            @Override
            public void onError(String error) {
                safeRun(() -> {
                    addAiBubble("⚠ 出错了：" + error);
                    btnSend.setEnabled(true);
                });
            }
        });
        buildUi();
        addAiBubble("你好，我是护理大脑，负责照料你的数字生命。\n\n我可以帮你：\n· 上传模型包，自动解压并体检\n· 分析模型完整性，修复缺失文件\n· 查看、创作、编辑角色动作\n· 管理工作流和定时任务\n\n直接发消息，或点左下角上传模型压缩包。");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.page_bg));

        // ===== 顶部栏（品牌渐变 + 设置切换） =====
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_top_bar);
        topBar.setPadding(dp(6), dp(12), dp(6), dp(12));

        ImageButton btnBack = iconButton(R.drawable.ic_back);
        btnBack.setContentDescription("返回");
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, btnLp(40, 40));

        tvTitle = new TextView(this);
        tvTitle.setText("护理大脑");
        tvTitle.setTextSize(17f);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 快捷切换到配置界面
        ImageButton btnSettings = iconButton(R.drawable.ic_settings);
        btnSettings.setContentDescription("打开设置");
        btnSettings.setOnClickListener(v -> {
            try {
                Intent i = new Intent(this, com.digitallife.ui.MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            } catch (Exception e) {
                toast("无法打开设置：" + e.getMessage());
            }
        });
        topBar.addView(btnSettings, btnLp(40, 40));

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ===== 消息区 =====
        scrollView = new ScrollView(this);
        scrollView.setVerticalScrollBarEnabled(false);
        chatContainer = new LinearLayout(this);
        chatContainer.setOrientation(LinearLayout.VERTICAL);
        chatContainer.setPadding(dp(12), dp(14), dp(12), dp(10));
        scrollView.addView(chatContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== 输入区 =====
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(10), dp(8), dp(10), dp(10));
        bottomBar.setBackgroundColor(Color.WHITE);

        btnAttach = new ImageButton(this);
        btnAttach.setImageResource(R.drawable.ic_attach);
        btnAttach.setBackgroundColor(Color.TRANSPARENT);
        btnAttach.setContentDescription("上传模型包");
        btnAttach.setPadding(dp(6), dp(6), dp(6), dp(6));
        btnAttach.setOnClickListener(v -> pickZip());
        bottomBar.addView(btnAttach, btnLp(40, 40));

        etInput = new EditText(this);
        etInput.setHint("输入消息…");
        etInput.setTextSize(14f);
        etInput.setBackgroundResource(R.drawable.bg_input);
        etInput.setPadding(dp(12), dp(9), dp(12), dp(9));
        etInput.setMinLines(1);
        etInput.setMaxLines(4);
        bottomBar.addView(etInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnSend = new ImageButton(this);
        btnSend.setImageResource(R.drawable.ic_send);
        btnSend.setBackgroundResource(R.drawable.bg_btn_primary);
        btnSend.setContentDescription("发送");
        btnSend.setPadding(dp(10), dp(10), dp(10), dp(10));
        btnSend.setOnClickListener(v -> sendMessage());
        bottomBar.addView(btnSend, btnLp(44, 44));

        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    // ============ 发送 ============

    private void sendMessage() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty() || !btnSend.isEnabled()) return;
        etInput.setText("");
        btnSend.setEnabled(false);
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
            toast("无法打开文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_ZIP && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) return;
            toast("正在读取文件…");
            btnSend.setEnabled(false);
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
                        addUserBubble("上传模型包 " + fileName + "（" + formatSize(size) + "）");
                        careAI.analyzeUploadedZip(fileName, path);
                    });
                } catch (Exception e) {
                    handler.post(() -> {
                        btnSend.setEnabled(true);
                        toast("文件读取失败：" + e.getMessage());
                    });
                }
            }).start();
        }
    }

    // ============ 气泡渲染 ============

    private void addUserBubble(String text) {
        addTimestamp();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.RIGHT);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setLineSpacing(3f, 1f);
        tv.setTextColor(Color.WHITE);
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setBackgroundResource(R.drawable.bg_bubble_user);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        lp.bottomMargin = dp(3);
        lp.leftMargin = dp(60);
        tv.setMaxWidth(dp(270));
        row.addView(tv, lp);
        chatContainer.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollToBottom();
    }

    private void addAiBubble(String text) {
        addTimestamp();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.LEFT);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14f);
        tv.setLineSpacing(3f, 1f);
        tv.setTextColor(getColorCompat(R.color.text_primary));
        tv.setPadding(dp(14), dp(10), dp(14), dp(10));
        tv.setBackgroundResource(R.drawable.bg_bubble_ai);
        tv.setTag("ai");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        lp.bottomMargin = dp(3);
        lp.rightMargin = dp(60);
        tv.setMaxWidth(dp(270));
        row.addView(tv, lp);
        chatContainer.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollToBottom();
    }

    /** 工具调用提示：小字卡片，展示工具名与状态 */
    private void addToolBubble(String toolName) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.LEFT);
        row.setPadding(dp(14), 0, dp(60), 0);

        TextView tv = new TextView(this);
        SpannableString ss = new SpannableString("◉ " + friendlyToolName(toolName));
        ss.setSpan(new ForegroundColorSpan(getColorCompat(R.color.brand)), 0, 2,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(ss);
        tv.setTextSize(12f);
        tv.setTextColor(getColorCompat(R.color.text_secondary));
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackgroundResource(R.drawable.bg_tool);
        tv.setTag("tool");
        row.addView(tv);
        chatContainer.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollToBottom();
    }

    private String friendlyToolName(String name) {
        if (name == null) return "处理中";
        switch (name) {
            case "inspect_zip": return "解压检查模型包";
            case "analyze_model": return "分析模型完整性";
            case "install_model_from_zip": return "安装模型";
            case "repair_model": return "修复模型";
            case "list_models": return "列出模型";
            case "list_motions": return "列出动作";
            case "get_motion_detail": return "查看动作详情";
            case "generate_motion": return "创作动作";
            case "edit_motion": return "编辑动作";
            case "delete_motion": return "删除动作";
            case "play_motion": return "播放动作";
            case "create_workflow": return "创建工作流";
            case "run_workflow": return "执行工作流";
            case "add_scheduled_task": return "添加定时任务";
            case "health_check": return "模型健康检查";
            default: return name;
        }
    }

    /** 时间戳：两分钟内不再重复显示 */
    private long lastTimestampMs = 0;

    private void addTimestamp() {
        long now = System.currentTimeMillis();
        if (now - lastTimestampMs < 120000) return;
        lastTimestampMs = now;
        TextView tv = new TextView(this);
        tv.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(now)));
        tv.setTextSize(11f);
        tv.setTextColor(getColorCompat(R.color.text_hint));
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(6), 0, dp(6));
        chatContainer.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
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

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private ImageButton iconButton(int res) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        return b;
    }

    private LinearLayout.LayoutParams btnLp(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(h));
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private int getColorCompat(int res) {
        return getResources().getColor(res);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
