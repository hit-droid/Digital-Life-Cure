package com.digitallife.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.tools.HookRunner;
import com.digitallife.tools.ToolUsageLog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 开发者选项（v1.24.0）。
 * 工具调用统计、HooK 状态、版本信息、日志导出。
 */
public class DeveloperActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.operit_bg));

        // 顶栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_operit_topbar);
        topBar.setPadding(UiKit.dp(this, 8),
                UiKit.dp(this, 56) + statusBarHeight(),
                UiKit.dp(this, 16), UiKit.dp(this, 12));
        topBar.setElevation(UiKit.dp(this, 4));

        TextView btnBack = new TextView(this);
        btnBack.setText("<");
        btnBack.setTextSize(20f);
        btnBack.setTextColor(0xFFFFFFFF);
        btnBack.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 8),
                UiKit.dp(this, 12), UiKit.dp(this, 8));
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(R.string.route_developer);
        tvTitle.setTextSize(20f);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(0xFFFFFFFF);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 滚动
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16),
                UiKit.dp(this, 16), UiKit.dp(this, 16));

        // ===== 版本信息 =====
        content.addView(buildSection("版本信息"));
        content.addView(buildInfoRow("App", "v1.24.0 (versionCode 27)"));
        content.addView(buildInfoRow("角色", "小汐"));
        content.addView(buildInfoRow("智能体", "已启用（工具/记忆/角色/计划/控制台）"));
        content.addView(buildInfoRow("Java", System.getProperty("java.version")));
        content.addView(buildInfoRow("Android", android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")"));

        // ===== 智能体状态 =====
        content.addView(buildSection("智能体状态"));
        content.addView(buildInfoRow("Hook Runner",
                "pre=" + HookRunner.getInstance().preCount()
                        + " post=" + HookRunner.getInstance().postCount()
                        + " err=" + HookRunner.getInstance().errCount()));
        content.addView(buildInfoRow("脑日志",
                com.digitallife.brain.BrainLog.getInstance().recent(0).size() + " 条"));
        content.addView(buildInfoRow("工具调用",
                new com.digitallife.tools.ToolUsageLog(this).all().size() + " 条"));

        // ===== 操作 =====
        content.addView(buildSection("调试操作"));

        Button exportBrain = mkButton("导出脑日志到剪贴板");
        exportBrain.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (com.digitallife.brain.BrainLog.BrainEntry e :
                    com.digitallife.brain.BrainLog.getInstance().recent(500)) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                sb.append(sdf.format(new Date(e.timestamp)))
                        .append(" [").append(e.tag).append("] ")
                        .append(e.message).append("\n");
            }
            copyToClipboard(sb.toString());
            toast("已复制 " + com.digitallife.brain.BrainLog.getInstance().recent(0).size() + " 条脑日志");
        });
        content.addView(exportBrain);

        Button exportTools = mkButton("导出工具调用日志到剪贴板");
        exportTools.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (ToolUsageLog.Entry e : new ToolUsageLog(this).all()) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                sb.append(sdf.format(new Date(e.timestamp)))
                        .append(" ").append(e.toolName);
                if (e.error != null) sb.append(" ERROR: ").append(e.error);
                sb.append(" ").append(e.durationMs).append("ms\n");
            }
            copyToClipboard(sb.toString());
            toast("已复制 " + new ToolUsageLog(this).all().size() + " 条工具日志");
        });
        content.addView(exportTools);

        Button clearBrain = mkButton("清空脑日志");
        clearBrain.setOnClickListener(v -> {
            com.digitallife.brain.BrainLog.getInstance().clear();
            toast("已清空脑日志");
        });
        content.addView(clearBrain);

        Button clearTools = mkButton("清空工具调用日志");
        clearTools.setOnClickListener(v -> {
            new ToolUsageLog(this).clear();
            toast("已清空工具调用日志");
        });
        content.addView(clearTools);

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private View buildSection(String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(14f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(0xFF6366F1);
        tv.setPadding(0, UiKit.dp(this, 12), 0, UiKit.dp(this, 6));
        return tv;
    }

    private View buildInfoRow(String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView k = new TextView(this);
        k.setText(key);
        k.setTextSize(13f);
        k.setTextColor(UiKit.color(this, R.color.operit_text_hint));
        row.addView(k, new LinearLayout.LayoutParams(
                UiKit.dp(this, 90), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(13f);
        v.setTypeface(Typeface.MONOSPACE);
        v.setTextColor(UiKit.color(this, R.color.operit_text_primary));
        row.addView(v, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private Button mkButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(13f);
        b.setBackgroundResource(R.drawable.bg_btn_primary);
        b.setPadding(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, 6);
        lp.bottomMargin = UiKit.dp(this, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private void copyToClipboard(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("digital_life", s));
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }
}
