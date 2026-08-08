package com.digitallife.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
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
 * 统一「模型配置」管理（对齐 Operit 模型配置页）：
 * - 顶部：当前配置快捷切换（点击弹出列表）+「＋新建」只填名称
 * - 操作行：重命名 / 删除 / 测试连接
 * - 编辑表单：配置名称、API Base URL、模型名、主 API Key（单行，失焦脱敏）
 * - 密钥池（折叠）：启用多个 Key 后，每个 Key 单独一行显示名称/状态，可添加/编辑/删除
 * 保存的配置互不覆盖，随时一键切换。
 */
public class ApiProfileSection {

    private final Activity activity;
    private final ApiManager apiManager;
    private final Settings settings;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final LinearLayout card;
    private final Button btnScopeChat, btnScopeCare;
    private final TextView tvCurrent;
    private final EditText etName, etBase, etKey, etModel;
    final TextView tvResult;
    private final LinearLayout keyPoolBox;
    private final TextView tvPoolStatus;
    private android.widget.Switch swKeyPool;

    private final List<ApiProfile> profiles = new ArrayList<>();

    /** 当前正在编辑的配置 id；null 表示将新建 */
    private String editingId;

    private String scope = ApiManager.SCOPE_CHAT;

    public ApiProfileSection(Context ctx, LinearLayout root) {
        this.activity = (Activity) ctx;
        this.apiManager = new ApiManager(ctx);
        this.settings = new Settings(ctx);
        card = UiKit.card(ctx, root, "模型配置");

        TextView hint = new TextView(ctx);
        hint.setText("可保存多套配置并在对话页/设置页一键切换；每套配置可启用密钥池，多个 Key 请求时自动轮换。");
        hint.setTextSize(13f);
        hint.setLineSpacing(2f, 1f);
        hint.setTextColor(UiKit.color(ctx, R.color.text_secondary));
        card.addView(hint, UiKit.lp(ctx, 0));

        // 用途 Tab（对话大脑 / 护理大脑）
        LinearLayout scopeRow = new LinearLayout(ctx);
        scopeRow.setOrientation(LinearLayout.HORIZONTAL);
        scopeRow.setGravity(Gravity.CENTER_VERTICAL);
        btnScopeChat = tabButton("对话大脑", ApiManager.SCOPE_CHAT);
        btnScopeCare = tabButton("护理大脑", ApiManager.SCOPE_CARE);
        scopeRow.addView(btnScopeChat, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 42), 1));
        scopeRow.addView(btnScopeCare, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 42), 1));
        card.addView(scopeRow, UiKit.lp(ctx, 8));

        // 当前配置快捷切换（点击弹列表）+ 新建
        LinearLayout pickerRow = new LinearLayout(ctx);
        pickerRow.setOrientation(LinearLayout.HORIZONTAL);
        pickerRow.setGravity(Gravity.CENTER_VERTICAL);
        tvCurrent = new TextView(ctx);
        tvCurrent.setTextSize(13f);
        tvCurrent.setTextColor(UiKit.color(ctx, R.color.brand));
        tvCurrent.setGravity(Gravity.CENTER_VERTICAL);
        tvCurrent.setPadding(0, UiKit.dp(ctx, 10), 0, UiKit.dp(ctx, 10));
        tvCurrent.setOnClickListener(v -> showPicker());
        pickerRow.addView(tvCurrent, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button btnNew = UiKit.secondaryButton(ctx, pickerRow, "＋ 新建配置");
        LinearLayout.LayoutParams nlp = (LinearLayout.LayoutParams) btnNew.getLayoutParams();
        nlp.topMargin = 0;
        btnNew.setOnClickListener(v -> startNewProfile());
        card.addView(pickerRow, UiKit.lp(ctx, 0));

        // 操作行：重命名 / 测试连接 / 删除
        LinearLayout opsRow = new LinearLayout(ctx);
        opsRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnRename = opButton("重命名");
        btnRename.setOnClickListener(v -> renameProfile());
        Button btnTest = opButton("测试连接");
        btnTest.setOnClickListener(v -> testConnection());
        Button btnDel = opButton("删除");
        btnDel.setTextColor(UiKit.color(ctx, R.color.danger));
        btnDel.setOnClickListener(v -> deleteProfile());
        opsRow.addView(btnRename, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 36), 1));
        opsRow.addView(btnTest, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 36), 1));
        opsRow.addView(btnDel, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 36), 1));
        card.addView(opsRow, UiKit.lp(ctx, 4));

        // 编辑表单：名称 / Base URL / 模型名 / 主 API Key
        etName = UiKit.input(ctx, card, "配置名称（如：主用 DeepSeek）", "");
        etBase = UiKit.input(ctx, card, "API Base URL（如 https://api.deepseek.com/v1）", "");
        etModel = UiKit.input(ctx, card, "模型名（如 deepseek-chat）", "");
        etKey = UiKit.input(ctx, card, "API Key", "");
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etKey.setTransformationMethod(new PasswordTransformationMethod());
        etKey.setOnFocusChangeListener((v, hasFocus) -> {
            etKey.setTransformationMethod(hasFocus ? null : new PasswordTransformationMethod());
        });

        // 密钥池（折叠）
        swKeyPool = UiKit.switchRow(ctx, card, "启用多个 Key（密钥池）", false);
        keyPoolBox = new LinearLayout(ctx);
        keyPoolBox.setOrientation(LinearLayout.VERTICAL);
        keyPoolBox.setVisibility(View.GONE);
        card.addView(keyPoolBox, UiKit.lp(ctx, 4));
        swKeyPool.setOnCheckedChangeListener((b, checked) -> {
            keyPoolBox.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) renderKeyPool();
        });

        tvPoolStatus = new TextView(ctx);
        tvPoolStatus.setTextSize(12f);
        tvPoolStatus.setLineSpacing(2f, 1f);
        tvPoolStatus.setTextColor(UiKit.color(ctx, R.color.text_secondary));
        keyPoolBox.addView(tvPoolStatus, UiKit.lp(ctx, 2));

        Button btnAddKey = UiKit.secondaryButton(ctx, keyPoolBox, "＋ 添加 Key");
        btnAddKey.setOnClickListener(v -> showAddKeyDialog(null));

        Button btnSave = UiKit.button(ctx, card, "保存此配置");
        btnSave.setOnClickListener(v -> saveProfile());

        tvResult = new TextView(ctx);
        tvResult.setTextSize(13f);
        tvResult.setLineSpacing(2f, 1f);
        tvResult.setPadding(0, UiKit.dp(ctx, 6), 0, 0);
        tvResult.setTextColor(UiKit.color(ctx, R.color.text_secondary));
        tvResult.setHint("填写后点「保存此配置」；点「＋新建配置」可另存一套而不覆盖现有。");
        card.addView(tvResult, UiKit.lp(ctx, 0));

        switchScope(ApiManager.SCOPE_CHAT);
    }

    // ==================== 用途切换 ====================

    private Button tabButton(String text, String s) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setOnClickListener(v -> switchScope(s));
        return b;
    }

    private Button opButton(String text) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setTextColor(UiKit.color(activity, R.color.brand));
        b.setBackgroundResource(R.drawable.bg_btn_secondary);
        b.setPadding(UiKit.dp(activity, 6), 0, UiKit.dp(activity, 6), 0);
        UiKit.pressScale(b);
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
            b.setTextColor(UiKit.color(activity, R.color.brand));
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
            tvCurrent.setText((cur.name.isEmpty() ? "（未命名）" : cur.name)
                    + " · " + (cur.model.isEmpty() ? "?" : cur.model) + keyCountLabel(cur) + "  ▾");
            loadProfile(cur);
            syncKeyPoolSwitch(cur);
        } else {
            tvCurrent.setText("尚未配置" + scopeLabel() + "模型，填写下方表单并保存  ▾");
            clearForm();
            keyPoolBox.setVisibility(View.GONE);
            swKeyPool.setChecked(false);
        }
    }

    private String keyCountLabel(ApiProfile p) {
        int n = p.effectiveKeys().size();
        if (n <= 0) return "";
        return n == 1 ? " · 1 key" : " · " + n + " keys";
    }

    /** 快捷切换当前配置 */
    void showPicker() {
        profiles.clear();
        profiles.addAll(apiManager.list(scope));
        if (profiles.isEmpty()) {
            tvResult.setTextColor(UiKit.color(activity, R.color.warning));
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
                    + " · " + (p.model.isEmpty() ? "?" : p.model) + keyCountLabel(p);
        }
        final int idx = curIdx;
        new AlertDialog.Builder(activity)
                .setTitle(scopeLabel() + " - 选择配置")
                .setSingleChoiceItems(names, curIdx, (d, w) -> {
                    apiManager.setCurrent(scope, profiles.get(w).id);
                    syncSettings();
                    d.dismiss();
                    refreshList();
                    tvResult.setTextColor(UiKit.color(activity, R.color.success));
                    tvResult.setText("已切换到「" + (profiles.get(w).name.isEmpty() ? "未命名" : profiles.get(w).name)
                            + "」，无需重新填写");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 新建 / 重命名 / 删除 / 测试 ====================

    /** 新建配置：Operit 式，只填名称创建并立即选中，不覆盖任何现有配置 */
    void startNewProfile() {
        EditText input = new EditText(activity);
        input.setHint("配置名称（如：备用 Gemini）");
        input.setTextSize(14f);
        input.setSingleLine(true);
        new AlertDialog.Builder(activity)
                .setTitle("新建配置")
                .setMessage("只填写名称即可创建一套新配置，URL / Key / 模型在下方表单填写，不会影响现有配置。")
                .setView(input)
                .setPositiveButton("创建", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        tvResult.setTextColor(UiKit.color(activity, R.color.danger));
                        tvResult.setText("名称不能为空");
                        return;
                    }
                    ApiProfile p = new ApiProfile(apiManager.newId(), name, "", "", "");
                    apiManager.save(scope, p);
                    apiManager.setCurrent(scope, p.id);
                    editingId = p.id;
                    syncSettings();
                    refreshList();
                    tvResult.setTextColor(UiKit.color(activity, R.color.success));
                    tvResult.setText("已新建「" + name + "」并设为当前，请填写下方 Base URL / API Key / 模型名后保存。");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void renameProfile() {
        ApiProfile cur = apiManager.getCurrent(scope);
        if (cur == null) {
            tvResult.setTextColor(UiKit.color(activity, R.color.danger));
            tvResult.setText("没有可重命名的配置");
            return;
        }
        EditText input = new EditText(activity);
        input.setText(cur.name);
        input.setTextSize(14f);
        input.setSingleLine(true);
        new AlertDialog.Builder(activity)
                .setTitle("重命名配置")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        tvResult.setTextColor(UiKit.color(activity, R.color.danger));
                        tvResult.setText("名称不能为空");
                        return;
                    }
                    cur.name = name;
                    apiManager.save(scope, cur);
                    refreshList();
                    tvResult.setTextColor(UiKit.color(activity, R.color.success));
                    tvResult.setText("已重命名为「" + name + "」");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void saveProfile() {
        String name = etName.getText().toString().trim();
        String base = etBase.getText().toString().trim();
        String model = etModel.getText().toString().trim();
        String mainKey = etKey.getText().toString().trim();
        if (name.isEmpty()) {
            tvResult.setTextColor(UiKit.color(activity, R.color.danger));
            tvResult.setText("请先填写配置名称");
            return;
        }
        if (mainKey.isEmpty()) {
            tvResult.setTextColor(UiKit.color(activity, R.color.danger));
            tvResult.setText("请至少填写一个 API Key");
            return;
        }

        ApiProfile existing = editingId == null ? null : findProfile(editingId);
        ApiProfile p;
        boolean isNew;
        if (existing == null) {
            p = new ApiProfile(apiManager.newId(), name, base, mainKey, model);
            isNew = true;
        } else {
            p = existing;
            p.name = name;
            p.baseUrl = base;
            p.model = model;
            isNew = false;
        }
        // 合并密钥池：主 key + 池中其他 key（去重），主 key 变化时替换第一项
        List<String> pool = readPoolKeys();
        List<String> merged = new ArrayList<>();
        merged.add(mainKey);
        for (String k : pool) {
            if (!k.equals(mainKey) && !merged.contains(k)) merged.add(k);
        }
        p.setApiKeys(merged);
        apiManager.save(scope, p);
        apiManager.setCurrent(scope, p.id);
        editingId = p.id;
        syncSettings();
        refreshList();
        if (!base.isEmpty() && !model.isEmpty()) {
            tvResult.setTextColor(UiKit.color(activity, R.color.text_secondary));
            tvResult.setText(isNew
                    ? "已新建「" + name + "」并设为当前，正在自动测试连接…"
                    : "已更新「" + name + "」，正在自动测试连接…");
            testConnection();
        } else {
            tvResult.setTextColor(UiKit.color(activity, R.color.success));
            tvResult.setText(isNew
                    ? "已保存「" + name + "」并设为当前"
                    : "已保存「" + name + "」");
        }
    }

    /** 供设置页「启动桌宠」等入口一键保存当前配置 */
    public void saveCurrentProfile() {
        saveProfile();
    }

    void deleteProfile() {
        String curId = apiManager.getCurrentId(scope);
        if (curId.isEmpty() || findProfile(curId) == null) {
            tvResult.setTextColor(UiKit.color(activity, R.color.danger));
            tvResult.setText("没有可删除的配置");
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle("删除配置")
                .setMessage("确定删除当前" + scopeLabel() + "配置？")
                .setPositiveButton("删除", (d, w) -> {
                    apiManager.remove(scope, curId);
                    editingId = null;
                    syncSettings();
                    refreshList();
                    tvResult.setTextColor(UiKit.color(activity, R.color.success));
                    tvResult.setText("已删除该" + scopeLabel() + "配置");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    void testConnection() {
        String base = etBase.getText().toString().trim();
        String key = etKey.getText().toString().trim();
        String model = etModel.getText().toString().trim();
        if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
            tvResult.setTextColor(UiKit.color(activity, R.color.danger));
            tvResult.setText("请先填写 Base URL / API Key / 模型名");
            return;
        }
        tvResult.setTextColor(UiKit.color(activity, R.color.text_secondary));
        tvResult.setText("正在测试，请稍候…");
        LLMClient.testConnection(base, key, model, (text, err) -> handler.post(() -> {
            if (err == null) {
                tvResult.setTextColor(UiKit.color(activity, R.color.success));
                tvResult.setText("✓ " + text);
            } else {
                tvResult.setTextColor(UiKit.color(activity, R.color.danger));
                tvResult.setText("✗ " + err + "\n" + UiKit.friendlyApiError(err, base, model));
            }
        }));
    }

    // ==================== 密钥池 ====================

    private void syncKeyPoolSwitch(ApiProfile p) {
        boolean enabled = p.effectiveKeys().size() > 1;
        swKeyPool.setOnCheckedChangeListener(null);
        swKeyPool.setChecked(enabled);
        swKeyPool.setOnCheckedChangeListener((b, checked) -> {
            keyPoolBox.setVisibility(checked ? View.VISIBLE : View.GONE);
            if (checked) renderKeyPool();
        });
        keyPoolBox.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (enabled) renderKeyPool();
    }

    /** 当前池中除主 key 外的 key（从表单主 key 与持久化 key 合并计算） */
    private List<String> readPoolKeys() {
        List<String> pool = new ArrayList<>();
        ApiProfile cur = apiManager.getCurrent(scope);
        if (cur != null) {
            String mainKey = etKey.getText() == null ? "" : etKey.getText().toString().trim();
            for (String k : cur.effectiveKeys()) {
                if (!k.equals(mainKey) && !pool.contains(k)) pool.add(k);
            }
        }
        return pool;
    }

    /** 重建密钥池列表：每 key 一行（名称 + 后4位 + 编辑/删除） */
    private void renderKeyPool() {
        keyPoolBox.removeAllViews();
        String mainKey = etKey.getText() == null ? "" : etKey.getText().toString().trim();
        List<String> pool = readPoolKeys();
        int total = pool.size() + (mainKey.isEmpty() ? 0 : 1);
        tvPoolStatus.setText("密钥池：共 " + total + " 个 Key，请求时自动轮换。"
                + (pool.isEmpty() && mainKey.isEmpty() ? "\n点下方「＋ 添加 Key」或填写上方 API Key 加入。"
                : "\n（主 Key 为上方填写的 API Key，其余为池内备用 Key）"));
        keyPoolBox.addView(tvPoolStatus, UiKit.lp(activity, 2));

        for (String k : pool) {
            keyPoolBox.addView(poolKeyRow(k), UiKit.lp(activity, 4));
        }

        Button btnAddKey = UiKit.secondaryButton(activity, keyPoolBox, "＋ 添加 Key");
        btnAddKey.setOnClickListener(v -> showAddKeyDialog(null));
    }

    private LinearLayout poolKeyRow(String key) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(activity, 10), UiKit.dp(activity, 6), UiKit.dp(activity, 10), UiKit.dp(activity, 6));
        row.setBackgroundResource(R.drawable.bg_input);

        TextView label = new TextView(activity);
        label.setText("Key …" + (key.length() > 4 ? key.substring(key.length() - 4) : key));
        label.setTextSize(13f);
        label.setTextColor(UiKit.color(activity, R.color.text_primary));
        row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button btnEdit = smallKeyButton("编辑");
        btnEdit.setOnClickListener(v -> showAddKeyDialog(key));
        row.addView(btnEdit);
        Button btnDel = smallKeyButton("删除");
        btnDel.setTextColor(UiKit.color(activity, R.color.danger));
        btnDel.setOnClickListener(v -> removePoolKey(key));
        row.addView(btnDel);
        return row;
    }

    private Button smallKeyButton(String text) {
        Button b = new Button(activity);
        b.setText(text);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setTextColor(UiKit.color(activity, R.color.brand));
        b.setBackgroundResource(R.drawable.bg_btn_secondary);
        b.setPadding(UiKit.dp(activity, 8), 0, UiKit.dp(activity, 8), 0);
        UiKit.pressScale(b);
        return b;
    }

    private void showAddKeyDialog(String existing) {
        EditText input = new EditText(activity);
        input.setHint("粘贴一个 API Key");
        input.setTextSize(14f);
        input.setSingleLine(true);
        if (existing != null) input.setText(existing);
        new AlertDialog.Builder(activity)
                .setTitle(existing == null ? "添加 Key" : "编辑 Key")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String key = input.getText().toString().trim();
                    if (key.isEmpty()) {
                        tvResult.setTextColor(UiKit.color(activity, R.color.danger));
                        tvResult.setText("Key 不能为空");
                        return;
                    }
                    List<String> pool = readPoolKeys();
                    if (existing != null) {
                        for (int i = 0; i < pool.size(); i++) {
                            if (pool.get(i).equals(existing)) pool.set(i, key);
                        }
                    } else if (!pool.contains(key)) {
                        pool.add(key);
                    }
                    ApiProfile cur = apiManager.getCurrent(scope);
                    if (cur == null) {
                        tvResult.setTextColor(UiKit.color(activity, R.color.danger));
                        tvResult.setText("请先创建并保存配置，再添加备用 Key");
                        return;
                    }
                    List<String> merged = new ArrayList<>();
                    String mainKey = etKey.getText() == null ? "" : etKey.getText().toString().trim();
                    if (!mainKey.isEmpty()) merged.add(mainKey);
                    for (String k : pool) {
                        if (!merged.contains(k)) merged.add(k);
                    }
                    cur.setApiKeys(merged);
                    apiManager.save(scope, cur);
                    syncSettings();
                    refreshList();
                    tvResult.setTextColor(UiKit.color(activity, R.color.success));
                    tvResult.setText(existing == null ? "已添加备用 Key" : "已更新该 Key");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void removePoolKey(String key) {
        ApiProfile cur = apiManager.getCurrent(scope);
        if (cur == null) return;
        List<String> merged = new ArrayList<>();
        String mainKey = etKey.getText() == null ? "" : etKey.getText().toString().trim();
        for (String k : cur.effectiveKeys()) {
            if (!k.equals(key)) merged.add(k);
        }
        cur.setApiKeys(merged);
        apiManager.save(scope, cur);
        syncSettings();
        refreshList();
        tvResult.setTextColor(UiKit.color(activity, R.color.success));
        tvResult.setText("已从密钥池移除该 Key");
    }

    // ==================== 工具 ====================

    private void syncSettings() {
        if (ApiManager.SCOPE_CHAT.equals(scope)) {
            apiManager.syncCurrentToSettings(scope, settings);
        }
    }

    private void loadProfile(ApiProfile p) {
        editingId = p.id;
        etName.setText(p.name);
        etBase.setText(p.baseUrl);
        etModel.setText(p.model);
        etKey.setText(p.primaryKey());
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
