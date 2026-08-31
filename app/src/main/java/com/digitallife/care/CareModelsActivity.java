package com.digitallife.care;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.R;
import com.digitallife.model.ModelInspector;
import com.digitallife.model.ModelManager;
import com.digitallife.render.Live2DNative;
import com.digitallife.service.PetService;
import com.digitallife.util.Settings;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 护理大脑模型管理面板。
 * 列出所有已安装的 Live2D 模型（内置 + 已导入），展示动作/资源状态，
 * 支持查看详情、立即切换、设为默认、删除、播放动作。
 */
public class CareModelsActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout listContainer;
    private TextView tvDetail;
    private CareTools tools;
    private Settings settings;
    private volatile boolean destroyed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;
        tools = new CareTools(this);
        settings = new Settings(this);
        buildUi();
        refreshList();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.operit_bg));

        // ===== 顶栏 =====
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_top_bar);
        topBar.setPadding(dp(6), dp(12), dp(6), dp(12));

        ImageButton btnBack = iconButton(R.drawable.ic_back);
        btnBack.setContentDescription("返回");
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, btnLp(40, 40));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("模型管理");
        tvTitle.setTextSize(17f);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnRefresh = new Button(this);
        btnRefresh.setHapticFeedbackEnabled(true);   // 自动生成：haptic
        btnRefresh.setContentDescription("刷新");   // 自动生成：a11y
        btnRefresh.setText("刷新");
        btnRefresh.setTextSize(13f);
        btnRefresh.setTextColor(Color.WHITE);
        btnRefresh.setAllCaps(false);
        btnRefresh.setBackgroundResource(R.drawable.bg_btn_primary);
        btnRefresh.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        rlp.setMargins(dp(4), 0, dp(4), 0);
        btnRefresh.setOnClickListener(v -> refreshList());
        topBar.addView(btnRefresh, rlp);

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ===== 模型列表 =====
        ScrollView listScroll = new ScrollView(this);
        listScroll.setVerticalScrollBarEnabled(false);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(12), dp(12), dp(12), dp(8));
        listScroll.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== 详情区 =====
        ScrollView detailScroll = new ScrollView(this);
        detailScroll.setBackgroundColor(Color.WHITE);
        tvDetail = new TextView(this);
        tvDetail.setTextSize(12f);
        tvDetail.setLineSpacing(3f, 1f);
        tvDetail.setPadding(dp(14), dp(12), dp(14), dp(12));
        tvDetail.setTextColor(getColorCompat(R.color.operit_text_primary));
        tvDetail.setText("点击上方模型查看详情。");
        detailScroll.addView(tvDetail, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230));
        root.addView(detailScroll, dlp);

        setContentView(root);
    }

    private void refreshList() {
        listContainer.removeAllViews();
        int count;
        try {
            count = Live2DNative.nativeGetModelCount();
        } catch (Throwable t) {
            count = 0;
        }
        List<String> imported = ModelManager.listImportedModelDirs(this);
        String defaultDir = settings.getDefaultModelDir();

        if (count <= 0) {
            TextView empty = new TextView(this);
            empty.setText("暂无可用模型。\n可在护理大脑对话中发送模型 zip 压缩包完成安装。");
            empty.setTextSize(13f);
            empty.setTextColor(getColorCompat(R.color.operit_text_secondary));
            empty.setPadding(dp(8), dp(20), dp(8), dp(20));
            empty.setGravity(Gravity.CENTER);
            listContainer.addView(empty);
            return;
        }

        for (int i = 0; i < count; i++) {
            final String name;
            try {
                name = Live2DNative.nativeGetModelDirName(i);
            } catch (Throwable t) {
                continue;
            }
            if (name == null || name.isEmpty()) continue;

            final boolean isImported = imported.contains(name);
            final boolean hasMotions = ModelInspector.hasUsableMotions(this, name);
            final boolean isDefault = name.equals(defaultDir);
            final int index = i;

            listContainer.addView(buildModelCard(name, isImported, hasMotions, isDefault, index));
        }
    }

    private View buildModelCard(final String name, final boolean isImported,
                                final boolean hasMotions, final boolean isDefault, final int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        // 第一行：名称 + 状态标记
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextSize(15f);
        tvName.setTextColor(getColorCompat(R.color.operit_text_primary));
        tvName.setTypeface(Typeface.DEFAULT_BOLD);
        row1.addView(tvName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (isDefault) {
            row1.addView(tag("默认", Color.rgb(230, 150, 60)));
        }
        row1.addView(tag(isImported ? "已导入" : "内置",
                isImported ? Color.rgb(80, 150, 220) : Color.rgb(130, 130, 150)));
        row1.addView(tag(hasMotions ? "有动作" : "零动作",
                hasMotions ? Color.rgb(90, 160, 100) : Color.rgb(200, 130, 60)));
        card.addView(row1);

        // 第二行：操作按钮
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, 0);

        row2.addView(actionButton("详情", v -> showDetail(name)), actLp());
        row2.addView(actionButton("切换", v -> switchModel(name)), actLp());
        if (!isDefault) {
            row2.addView(actionButton("设为默认", v -> setDefaultModel(name)), actLp());
        }
        if (isImported) {
            row2.addView(actionButton("删除", v -> deleteModel(name)), actLp());
        }
        card.addView(row2);

        return card;
    }

    private void showDetail(final String modelName) {
        tvDetail.setText("正在分析 " + modelName + " …");
        new Thread(() -> {
            final String report;
            try {
                report = tools.execute("analyze_model", jsonArgs("modelName", modelName));
            } catch (Exception e) {
                final String err = "分析失败: " + com.digitallife.ui.UiKit.safeMsg(e);
                safeRun(() -> tvDetail.setText(err));
                return;
            }
            safeRun(() -> {
                tvDetail.setText(report);
            });
        }).start();
    }

    private void switchModel(String modelName) {
        PetService svc = PetService.getInstance();
        if (svc == null) {
            toast("桌宠未启动，无法切换。请先在主界面启动桌宠。");
            return;
        }
        svc.switchToModelByName(modelName);
        toast("已切换至: " + modelName);
        refreshList();
    }

    private void setDefaultModel(String modelName) {
        try {
            String r = tools.execute("set_default_model", jsonArgs("modelName", modelName));
            toast(r);
            refreshList();
        } catch (Exception e) {
            toast("设置失败: " + com.digitallife.ui.UiKit.safeMsg(e));
        }
    }

    private void deleteModel(String modelName) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除模型")
                .setMessage("确定删除模型「" + modelName + "」吗？")
                .setPositiveButton("删除", (d, w) -> {
                    try {
                        String r = tools.execute("delete_model", jsonArgs("modelName", modelName));
                        toast(r);
                        refreshList();
                        tvDetail.setText("点击上方模型查看详情。");
                    } catch (Exception e) {
                        toast("删除失败: " + com.digitallife.ui.UiKit.safeMsg(e));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ============ 视图工具 ============

    private TextView tag(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(10f);
        tv.setTextColor(color);
        tv.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(4);
        tv.setBackground(makeTagBg(color));
        tv.setLayoutParams(lp);
        return tv;
    }

    private android.graphics.drawable.GradientDrawable makeTagBg(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(8));
        g.setColor(Color.argb(40, Color.red(color), Color.green(color), Color.blue(color)));
        g.setStroke(1, color);
        return g;
    }

    private Button actionButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setContentDescription("b");   // 自动生成：a11y
        b.setText(text);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setTextColor(getColorCompat(R.color.operit_text_primary));
        b.setBackgroundResource(R.drawable.bg_btn_secondary);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams actLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private JSONObject jsonArgs(String k, String v) {
        JSONObject o = new JSONObject();
        try {
            o.put(k, v);
        } catch (Exception ignored) {
        }
        return o;
    }

    private ImageButton iconButton(int res) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        // v1.37.0：无障碍描述（当前工厂仅用于返回键）
        if (res == R.drawable.ic_back) b.setContentDescription("返回");
        return b;
    }

    private LinearLayout.LayoutParams btnLp(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(h));
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private void safeRun(Runnable r) {
        handler.post(() -> {
            if (!destroyed) r.run();
        });
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int getColorCompat(int res) {
        return getResources().getColor(res);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
    }
}
