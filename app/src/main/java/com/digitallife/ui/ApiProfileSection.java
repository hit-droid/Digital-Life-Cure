package com.digitallife.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import com.digitallife.brain.LLMClient;
import com.digitallife.R;
import com.digitallife.util.ApiManager;
import com.digitallife.util.ApiProfile;
import com.digitallife.util.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个大脑的 API Profile 编辑区（设置 Tab 用）。
 * 顶部下拉选择已有配置，下方表单编辑；支持保存/新建/删除/测试。
 */
public class ApiProfileSection {

    final String scope;
    final LinearLayout card;
    final Spinner spinner;
    final EditText etName, etBase, etKey, etModel;
    final TextView tvResult;
    final List<ApiProfile> profiles = new ArrayList<>();
    boolean fromUser = false;

    private final Context ctx;
    private final ApiManager apiManager;
    private final Settings settings;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public ApiProfileSection(Context ctx, LinearLayout root, String title, String scope, String roleLabel) {
        this.ctx = ctx;
        this.scope = scope;
        this.apiManager = new ApiManager(ctx);
        this.settings = new Settings(ctx);
        card = UiKit.card(ctx, root, title);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new Spinner(ctx);
        row.addView(spinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button btnNew = new Button(ctx);
        btnNew.setText("新建");
        btnNew.setTextSize(13f);
        btnNew.setTextColor(UiKit.color(ctx, R.color.brand));
        btnNew.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnNew.setAllCaps(false);
        UiKit.pressScale(btnNew);
        btnNew.setOnClickListener(v -> newProfile());
        row.addView(btnNew, UiKit.lp(ctx, 4));
        Button btnDel = new Button(ctx);
        btnDel.setText("删除");
        btnDel.setTextSize(13f);
        btnDel.setTextColor(UiKit.color(ctx, R.color.danger));
        btnDel.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnDel.setAllCaps(false);
        UiKit.pressScale(btnDel);
        btnDel.setOnClickListener(v -> deleteProfile());
        row.addView(btnDel, UiKit.lp(ctx, 4));
        card.addView(row, UiKit.lp(ctx, 0));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!fromUser || position < 0 || position >= profiles.size()) return;
                loadProfile(profiles.get(position));
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        etName = UiKit.input(ctx, card, roleLabel + " 配置名称（如：主用 DeepSeek）", "");
        etBase = UiKit.input(ctx, card, "API Base URL（如 https://api.deepseek.com/v1）", "");
        etKey = UiKit.input(ctx, card, "API Key", "");
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etModel = UiKit.input(ctx, card, "模型名（如 deepseek-chat）", "");
        Button btnSave = UiKit.button(ctx, card, "保存此配置");
        btnSave.setOnClickListener(v -> saveProfile());
        Button btnTest = UiKit.button(ctx, card, "测试此 API 连接");
        btnTest.setOnClickListener(v -> testConnection());
        tvResult = new TextView(ctx);
        tvResult.setTextSize(12f);
        tvResult.setLineSpacing(2f, 1f);
        tvResult.setPadding(0, UiKit.dp(ctx, 6), 0, 0);
        tvResult.setTextColor(UiKit.color(ctx, R.color.text_secondary));
        card.addView(tvResult, UiKit.lp(ctx, 0));
        refreshSpinner();
    }

    void refreshSpinner() {
        profiles.clear();
        profiles.addAll(apiManager.list(scope));
        List<String> names = new ArrayList<>();
        for (ApiProfile p : profiles) {
            names.add(p.name == null || p.name.trim().isEmpty() ? "（未命名）" : p.name);
        }
        fromUser = false;
        spinner.setAdapter(new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_dropdown_item, names));
        String curId = apiManager.getCurrentId(scope);
        int idx = 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(curId)) {
                idx = i;
                break;
            }
        }
        spinner.setSelection(idx, false);
        fromUser = true;
        if (!profiles.isEmpty()) {
            loadProfile(profiles.get(idx));
        } else {
            clearForm();
        }
    }

    void newProfile() {
        clearForm();
        fromUser = false;
        spinner.setSelection(Math.max(0, spinner.getCount() - 1), false);
        fromUser = true;
    }

    void deleteProfile() {
        String curId = apiManager.getCurrentId(scope);
        if (curId.isEmpty() || findProfile(curId) == null) {
            tvResult.setTextColor(UiKit.color(ctx, R.color.danger));
            tvResult.setText("没有可删除的配置");
            return;
        }
        apiManager.remove(scope, curId);
        if (ApiManager.SCOPE_CHAT.equals(scope)) {
            apiManager.syncCurrentToSettings(scope, settings);
        }
        refreshSpinner();
        tvResult.setTextColor(UiKit.color(ctx, R.color.success));
        tvResult.setText("已删除配置");
    }

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
        if (ApiManager.SCOPE_CHAT.equals(scope)) {
            apiManager.syncCurrentToSettings(scope, settings);
        }
        refreshSpinner();
        tvResult.setTextColor(UiKit.color(ctx, R.color.success));
        tvResult.setText("已保存：" + name);
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
