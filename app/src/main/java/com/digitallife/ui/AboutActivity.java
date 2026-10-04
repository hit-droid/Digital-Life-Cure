package com.digitallife.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.R;
import com.digitallife.ui.UiKit;
import com.digitallife.update.UpdateChecker;
import com.digitallife.update.UpdateClient;
import com.digitallife.util.EnvFacts;

public class AboutActivity extends Activity {

    /** 非空表示已发现新版本，此时「检查更新」按钮改为「前往下载」 */
    private String downloadUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.operit_bg));

        LinearLayout topBar = UiKit.pageTopBar(this, getString(R.string.route_about));
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16));

        TextView appName = new TextView(this);
        appName.setText(R.string.app_name);
        appName.setTextSize(24f);
        appName.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        appName.setTextColor(UiKit.color(this, R.color.operit_accent));
        content.addView(appName);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = UiKit.dp(this, 8);

        final String curVersion = UpdateClient.currentVersion(this);
        TextView version = new TextView(this);
        try {
            int vCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            version.setText("版本 " + curVersion + " (" + vCode + ")");
        } catch (Exception e) {
            version.setText("版本 " + curVersion);
        }
        version.setTextSize(14f);
        version.setTextColor(UiKit.color(this, R.color.operit_text_secondary));
        content.addView(version, lp);

        // v1.147.0（#87）：「运行环境自检」卡片（只读 + 一键复制环境信息）
        addEnvFactsCard(content, curVersion);

        TextView desc = new TextView(this);
        desc.setText("基于 Operit AI 视觉风格重构的数字生命 App\n角色：小汐\n© 2026 hit-droid");
        desc.setTextSize(13f);
        desc.setTextColor(UiKit.color(this, R.color.operit_text_hint));
        desc.setLineSpacing(UiKit.dp(this, 4), 1f);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = UiKit.dp(this, 16);
        content.addView(desc, lp2);

        // v1.138.0（Issue #41）：应用内更新检查。手动触发，不后台轮询。
        final TextView updateStatus = new TextView(this);        updateStatus.setTextSize(13f);
        updateStatus.setTextColor(UiKit.color(this, R.color.operit_text_hint));
        updateStatus.setLineSpacing(UiKit.dp(this, 3), 1f);

        final Button checkBtn = UiKit.button(this, content, "检查更新");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = UiKit.dp(this, 20);
        content.addView(updateStatus, blp);

        checkBtn.setOnClickListener(v -> {
            if (downloadUrl != null) {
                openUrl(downloadUrl);
            } else {
                checkUpdate(checkBtn, updateStatus, curVersion);
            }
        });

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    /** 触发一次检查：按钮进入「检查中」禁用态，结果回来再恢复，避免连点重复发请求 */
    private void checkUpdate(final Button btn, final TextView status, final String curVersion) {
        btn.setEnabled(false);
        btn.setText("检查中…");
        status.setTextColor(UiKit.color(this, R.color.operit_text_hint));
        status.setText("正在检查最新版本…");
        UpdateClient.check(this, new UpdateClient.Callback() {
            @Override
            public void onResult(UpdateChecker.Release latest, boolean isNewer, String error) {
                btn.setEnabled(true);
                if (error != null) {
                    btn.setText("重试");
                    status.setTextColor(UiKit.color(AboutActivity.this, R.color.operit_text_hint));
                    status.setText("检查失败：" + error);
                    return;
                }
                if (isNewer) {
                    downloadUrl = latest.url;
                    btn.setText("前往下载 " + latest.tag);
                    status.setTextColor(UiKit.color(AboutActivity.this, R.color.operit_accent));
                    status.setText("发现新版本 " + latest.tag + "（当前 " + curVersion + "），点上面按钮前往下载。");
                } else {
                    btn.setText("再检查一次");
                    status.setTextColor(UiKit.color(AboutActivity.this, R.color.operit_text_secondary));
                    status.setText("已是最新版本（" + curVersion + "）。");
                }
            }
        });
    }

    /**
     * v1.147.0（#87）：运行环境自检卡片。
     * <p>Android 侧负责取值（SDK/版本/ABI/页大小），拼装与判定全部交给纯逻辑
     * {@link EnvFacts}。用户点「复制环境信息」把多行报告写进剪贴板，方便贴到 issue。</p>
     */
    private void addEnvFactsCard(LinearLayout content, final String appVersion) {
        final int sdkInt = android.os.Build.VERSION.SDK_INT;
        final String release = android.os.Build.VERSION.RELEASE;
        final String[] abiArr = android.os.Build.SUPPORTED_ABIS;
        final java.util.List<String> abis = abiArr == null
                ? java.util.Collections.<String>emptyList()
                : java.util.Arrays.asList(abiArr);
        long pageSizeTmp = 0L;
        try {
            // API 21+ 可用；取不到时保持 0（纯逻辑侧显示「未知」）
            pageSizeTmp = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
        } catch (Throwable ignored) {
        }
        final long pageSize = pageSizeTmp;   // lambda 捕获需 effectively final

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12),
                UiKit.dp(this, 14), UiKit.dp(this, 12));
        card.setElevation(UiKit.dp(this, 1));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiKit.dp(this, 16);

        TextView title = new TextView(this);
        title.setText("运行环境");
        title.setTextSize(14f);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.color(this, R.color.operit_text_primary));
        card.addView(title);

        final String body = EnvFacts.androidLine(sdkInt, release)
                + "\nABI：" + EnvFacts.abiLine(abis)
                + "\n内存页大小：" + EnvFacts.pageLine(pageSize)
                + "\n16 KB 页面设备：" + (EnvFacts.compatible16k(pageSize) ? "是" : "否");
        TextView bodyTv = new TextView(this);
        bodyTv.setText(body);
        bodyTv.setTextSize(13f);
        bodyTv.setTextColor(UiKit.color(this, R.color.operit_text_secondary));
        bodyTv.setLineSpacing(UiKit.dp(this, 3), 1f);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp2.topMargin = UiKit.dp(this, 8);
        card.addView(bodyTv, blp2);

        Button copyBtn = new Button(this);
        copyBtn.setHapticFeedbackEnabled(true);
        copyBtn.setContentDescription("复制环境信息");
        copyBtn.setText("复制环境信息");
        copyBtn.setTextSize(13f);
        copyBtn.setAllCaps(false);
        copyBtn.setTextColor(UiKit.color(this, R.color.operit_accent));
        copyBtn.setBackgroundResource(R.drawable.bg_btn_secondary);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 34));
        cblp.topMargin = UiKit.dp(this, 10);
        copyBtn.setOnClickListener(v -> {
            String text = EnvFacts.report(appVersion, sdkInt, release, abis, pageSize);
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("环境信息", text));
                Toast.makeText(this, "已复制环境信息", Toast.LENGTH_SHORT).show();
            }
        });
        card.addView(copyBtn, cblp);

        content.addView(card, clp);
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可打开该链接的应用", Toast.LENGTH_SHORT).show();
        }
    }

}
