package com.digitallife.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.ui.UiKit;

public class AboutActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.operit_bg));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_operit_topbar);
        topBar.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 56) + statusBarHeight(), UiKit.dp(this, 16), UiKit.dp(this, 12));
        topBar.setElevation(UiKit.dp(this, 4));

        TextView btnBack = new TextView(this);
        btnBack.setText("<");
        btnBack.setTextSize(20f);
        btnBack.setTextColor(0xFFFFFFFF);
        btnBack.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 8), UiKit.dp(this, 12), UiKit.dp(this, 8));
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(R.string.route_about);
        tvTitle.setTextSize(20f);
        tvTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTitle.setTextColor(0xFFFFFFFF);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        TextView version = new TextView(this);
        try {
            String vName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            int vCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
            version.setText("版本 " + vName + " (" + vCode + ")");
        } catch (Exception e) {
            version.setText("版本 1.23.2");
        }
        version.setTextSize(14f);
        version.setTextColor(UiKit.color(this, R.color.operit_text_secondary));
        content.addView(version, lp);

        TextView desc = new TextView(this);
        desc.setText("基于 Operit AI 视觉风格重构的数字生命 App\n角色：小汐\n© 2026 hit-droid");
        desc.setTextSize(13f);
        desc.setTextColor(UiKit.color(this, R.color.operit_text_hint));
        desc.setLineSpacing(UiKit.dp(this, 4), 1f);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = UiKit.dp(this, 16);
        content.addView(desc, lp2);

        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }
}
