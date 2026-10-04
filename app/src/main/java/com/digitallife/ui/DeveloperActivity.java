package com.digitallife.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

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
        LinearLayout topBar = UiKit.pageTopBar(this, getString(R.string.route_developer));
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 滚动
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16),
                UiKit.dp(this, 16), UiKit.dp(this, 16));

        // ===== 版本信息 =====
        LinearLayout versionCard = UiKit.card(this, content, "版本信息");
        UiKit.addInfoRow(this, versionCard, "App", appVersionText());
        UiKit.addInfoRow(this, versionCard, "角色", "小汐");
        UiKit.addInfoRow(this, versionCard, "智能体", "已启用（工具/记忆/角色/计划/控制台）");
        UiKit.addInfoRow(this, versionCard, "Java", System.getProperty("java.version"));
        UiKit.addInfoRow(this, versionCard, "Android", android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")");

        // ===== 智能体状态 =====
        LinearLayout statusCard = UiKit.card(this, content, "智能体状态");
        UiKit.addInfoRow(this, statusCard, "Hook Runner",
                "pre=" + HookRunner.getInstance().preCount()
                        + " post=" + HookRunner.getInstance().postCount()
                        + " err=" + HookRunner.getInstance().errCount());
        UiKit.addInfoRow(this, statusCard, "脑日志",
                com.digitallife.brain.BrainLog.getInstance().recent(0).size() + " 条");
        UiKit.addInfoRow(this, statusCard, "工具调用",
                new com.digitallife.tools.ToolUsageLog(this).all().size() + " 条");

        // ===== 操作 =====
        UiKit.sectionTitle(this, content, "调试操作", null);

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

    /** 版本号从 PackageManager 实时读取，避免界面写死版本随发版过期 */
    private String appVersionText() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return "v" + pi.versionName + " (versionCode " + pi.versionCode + ")";
        } catch (Exception e) {
            return "未知";
        }
    }

    private Button mkButton(String text) {
        Button b = new Button(this);
        b.setHapticFeedbackEnabled(true);   // 自动生成：haptic
        b.setContentDescription("b");   // 自动生成：a11y
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
}
