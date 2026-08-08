package com.digitallife.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.brain.LLMClient;
import com.digitallife.util.ApiManager;
import com.digitallife.util.ApiProfile;
import com.digitallife.util.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * 统一「模型配置」管理（对齐 Operit 的多配置 + 为任务指定模型）：
 * - 顶部 Tab 切换用途（对话大脑 / 护理大脑）
 * - 当前配置一键切换，增删都在保存/删除语义内完成，不再有孤立的「新建/删除」按钮
 * - 配好后在对话页顶部可直接切换模型
 */
public class ApiProfileSection {

    private final Context ctx;
    private final ApiManager apiManager;
    private final Settings settings;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final LinearLayout card;
    private final Button btnScopeChat, btnScopeCare;
    private final TextView tvCurrent;
    private final EditText etName, etBase, etKey, etModel;
    final TextView tvResult;
    private final List<ApiProfile> profiles = new ArrayList<>();

    private String scope = ApiManager.SCOPE_CHAT;

    public ApiProfileSection(Context ctx, LinearLayout root) {
        this.ctx = ctx;
        this.apiManager = new ApiManager(ctx);
        this.settings = new Settings(ctx);
        card = UiKit.card(ctx, root, "模型配置");

        TextView hint = new TextView(ctx);
        hint.setText("配好模型后，在对话页顶部可一键切换，无需再回这里设置。");
        hint.setTextSize(13f);
        hint.setLineSpacing(2f, 1f);
        hint.setTextColor(UiKit.color(ctx, R.color.text_secondary));
        card.addView(hint, UiKit.lp(ctx, 0));

        LinearLayout scopeRow = new LinearLayout(ctx);
        scopeRow.setOrientation(LinearLayout.HORIZONTAL);
        scopeRow.setGravity(Gravity.CENTER_VERTICAL);
        btnScopeChat = tabButton("对话大脑", ApiManager.SCOPE_CHAT);
        btnScopeCare = tabButton("护理大脑", ApiManager.SCOPE_CARE);
        scopeRow.addView(btnScopeChat, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 42), 1));
        scopeRow.addView(btnScopeCare, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 42), 1));
        card.addView(scopeRow, UiKit.lp(ctx, 8));

        tvCurrent = new TextView(ctx);
        tvCurrent.setTextSize(13f);
        tvCurrent.setTextColor(UiKit.color(ctx, R.color.brand));
        tvCurrent.setGravity(Gravity.CENTER_VERTICAL);
        tvCurrent.setPadding(0, UiKit.dp(ctx, 10), 0, UiKit.dp(ctx, 4));
        tvCurrent.setOnClickListener(v -> showPicker());
        card.addView(tvCurrent, UiKit.lp(ctx, 0));

        etName = UiKit.input(ctx, card, "配置名称（如：主用 DeepSeek）", "");
        etBase = UiKit.input(ctx, card, "API Base URL（如 https://api.deepseek.com/v1）", "");
        etKey = UiKit.input(ctx, card, "API Key", "");
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etModel = UiKit.input(ctx, card, "模型名（如 deepseek-chat）", "");

        Button btnSave = UiKit.button(ctx, card, "保存此配置");
        btnSave.setOnClickListener(v -> saveProfile());
        Button btnTest = UiKit.secondaryButton(ctx, card, "测试此 API 连接");
        btnTest.setOnClickListener(v -> testConnection());
        Button btnDel = UiKit.secondaryButton(ctx, card, "删除此配置");
        btnDel.setTextColor(UiKit.color(ctx, R.color.danger));
        btnDel.setOnClickListener(v -> deleteProfile());

        tvResult = new TextView(ctx);
        tvResult.setTextSize(13f);
        tvResult.setLineSpacing(2f, 1f);
        tvResult.setPadding(0, UiKit.dp(ctx, 6), 0, 0);
        tvResult.setTextColor(UiKit.color(ctx, R.color.text_secondary));
        tvResult.setHint("填写后点「测试 API 连接」，这里会显示详细结果。");
        card.addView(tvResult, UiKit.lp(ctx, 0));

        switchScope(ApiManager.SCOPE_CHAT);
    }

    // ==================== 用途切换 ====================

    private Button tabButton(String text, String s) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setOnClickListener(v -> switchScope(s));
        return b;
    }

    void switchScope(String s) {
        this.scope = s;
        boolean chat = ApiManager.SCOPE_CHAT.equals(s);
        setTabStyle(btnScopeChat, chat);
        setTabStyle(btnScopeCare, !chat);
        refreshList();
    }

    private void setTabStyle(Button b, boolean selected) {
        if (selected) {
            b.setTextColor(Color.WHITE);
            b.setBackgroundResource(R.drawable.bg_btn_primary);
        } else {
            b.setTextColor(UiKit.color(ctx, R.color.brand));
            b.setBackgroundResource(R.drawable.bg_btn_secondary);
        }
    }

    private String scopeLabel() {
        return ApiManager.SCOPE_CARE.equals(scope) ? "护理大脑" : "对话大脑";
    }

    // ==================== 当前配置 ====================

    void refreshList() {
        profiles.clear();
        profiles.addAll(apiManager.list(scope));
        ApiProfile cur = apiManager.getCurrent(scope);
        if (cur != null) {
            tvCurrent.setText("当前使用：" + (cur.name.isEmpty() ? "（未命名）" : cur.name)
                    + " · " + (cur.model.isEmpty() ? "?" : cur.model) + "  ▾");
            loadProfile(cur);
        } else {
            tvCurrent.setText("尚未配置" + scopeLabel() + "模型，填写下方表单并保存  ▾");
            clearForm();
        }
    }

    void showPicker() {
        profiles.clear();
        profiles.addAll(apiManager.list(scope));
        if (profiles.isEmpty()) {
            tvResult.setTextColor(UiKit.color(ctx, R.color.warning));
            tvResult.setText("还没有" + scopeLabel() + "模型配置，填写下方表单后点「保存此配置」即可创建。");
            return;
        }
        String curId = apiManager.getCurrentId(scope);
        int curIdx = 0;
        String[] names = new String[profiles.size()];
        for (int i = 0; i < profiles.size(); i++) {
            ApiProfile p = profiles.get(i);
            if (p.id.equals(curId)) curIdx = i;
            names[i] = (p.name.isEmpty() ? "（未命名）" : p.name)
                    + " · " + (p.model.isEmpty() ? "?" : p.model);
        }
        final int idx = curIdx;
        new AlertDialog.Builder(ctx)
                .setTitle(scopeLabel() + " - 选择配置")
                .setSingleChoiceItems(names, curIdx, (d, w) -> {
                    apiManager.setCurrent(scope, profiles.get(w).id);
                    syncSettings();
                    d.dismiss();
                    refreshList();
                    tvResult.setTextColor(UiKit.color(ctx, R.color.success));
                    tvResult.setText("已切换当前" + scopeLabel() + "配置");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 保存 / 删除 / 测试 ====================

    void saveProfile() {
        String name = etName.getText().toString().trim();
        String base = etBase.getText().toString().trim();
        String key = etKey.getText().toString().trim();
        String model = etModel.getText().toString().trim();
        if (name.isEmpty()) {
            tvResult.setTextColor(UiKit.color(ctx, R.color.danger));
            tvResult.setText("请先填写配置名称");
            return;
        }
        ApiProfile p = findProfile(apiManager.getCurrentId(scope));
        if (p == null) {
            p = new ApiProfile(apiManager.newId(), name, base, key, model);
        } else {
            p.name = name;
            p.baseUrl = base;
            p.apiKey = key;
            p.model = model;
        }
        apiManager.save(scope, p);
        apiManager.setCurrent(scope, p.id);
        syncSettings();
        refreshList();
        if (!base.isEmpty() && !key.isEmpty() && !model.isEmpty()) {
            tvResult.setTextColor(UiKit.color(ctx, R.color.text_secondary));
            tvResult.setText("已保存「" + name + "」，正在自动测试连接…");
            testConnection();
        } else {
            tvResult.setTextColor(UiKit.color(ctx, R.color.success));
            tvResult.setText("已保存「" + name + "」，在对话页顶部可直接切换到它");
        }
    }

    /** 供设置页「启动桌宠」等入口一键保存当前配置 */
    public void saveCurrentProfile() {
        saveProfile();
    }

    void deleteProfile() {
        String curId = apiManager.getCurrentId(scope);
        if (curId.isEmpty() || findProfile(curId) == null) {
            tvResult.setTextColor(UiKit.color(ctx, R.color.danger));
            tvResult.setText("没有可删除的配置");
            return;
        }
        apiManager.remove(scope, curId);
        syncSettings();
        refreshList();
        tvResult.setTextColor(UiKit.color(ctx, R.color.success));
        tvResult.setText("已删除该" + scopeLabel() + "配置");
    }

    void testConnection() {
        String base = etBase.getText().toString().trim();
        String key = etKey.getText().toString().trim();
        String model = etModel.getText().toString().trim();
        if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
            tvResult.setTextColor(UiKit.color(ctx, R.color.danger));
            tvResult.setText("请先填写 Base URL / API Key / 模型名");
            return;
        }
        tvResult.setTextColor(UiKit.color(ctx, R.color.text_secondary));
        tvResult.setText("正在测试，请稍候…");
        LLMClient.testConnection(base, key, model, (text, err) -> handler.post(() -> {
            if (err == null) {
                tvResult.setTextColor(UiKit.color(ctx, R.color.success));
                tvResult.setText("✓ " + text);
            } else {
                tvResult.setTextColor(UiKit.color(ctx, R.color.danger));
                tvResult.setText("✗ " + err + "\n" + UiKit.friendlyApiError(err, base, model));
            }
        }));
    }

    // ==================== 工具 ====================

    private void syncSettings() {
        if (ApiManager.SCOPE_CHAT.equals(scope)) {
            apiManager.syncCurrentToSettings(scope, settings);
        }
    }

    private void loadProfile(ApiProfile p) {
        etName.setText(p.name);
        etBase.setText(p.baseUrl);
        etKey.setText(p.apiKey);
        etModel.setText(p.model);
    }

    private void clearForm() {
        etName.setText("");
        etBase.setText("");
        etKey.setText("");
        etModel.setText("");
    }

    private ApiProfile findProfile(String id) {
        for (ApiProfile p : profiles) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }
}
