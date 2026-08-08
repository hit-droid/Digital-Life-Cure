package com.digitallife.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
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
 * 统一「模型配置」管理（对齐 Operit 多配置 + 密钥池）：
 * - 顶部 Tab 切换用途（对话大脑 / 护理大脑）
 * - 多套配置列表随时切换；「新建配置」与「保存此配置」分离，新增不会覆盖旧配置
 * - 每个配置支持密钥池：API Key 每行一个，请求自动轮换；支持批量导入/导出密钥
 */
public class ApiProfileSection {

    static final int REQ_IMPORT_KEYS = 7001;
    static final int REQ_EXPORT_KEYS = 7002;

    private final Activity activity;
    private final ApiManager apiManager;
    private final Settings settings;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final LinearLayout card;
    private final Button btnScopeChat, btnScopeCare;
    private final TextView tvCurrent;
    private final EditText etName, etBase, etKey, etModel;
    final TextView tvResult;
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
        hint.setText("配好模型后，在对话页顶部可一键切换；每个配置可填多个 API Key，请求自动轮换组成密钥池。");
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
        etKey = UiKit.input(ctx, card,
                "API Key：每行一个，多个自动组成密钥池轮换", "");
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etKey.setGravity(Gravity.TOP | Gravity.START);
        LinearLayout.LayoutParams kp = (LinearLayout.LayoutParams) etKey.getLayoutParams();
        kp.height = UiKit.dp(ctx, 92);
        etModel = UiKit.input(ctx, card, "模型名（如 deepseek-chat）", "");

        Button btnNew = UiKit.secondaryButton(ctx, card, "＋ 新建配置");
        btnNew.setOnClickListener(v -> startNewProfile());

        Button btnSave = UiKit.button(ctx, card, "保存此配置");
        btnSave.setOnClickListener(v -> saveProfile());

        Button btnTest = UiKit.secondaryButton(ctx, card, "测试此 API 连接");
        btnTest.setOnClickListener(v -> testConnection());

        LinearLayout keyRow = new LinearLayout(ctx);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        Button btnImport = new Button(ctx);
        btnImport.setText("批量导入密钥");
        btnImport.setTextSize(13f);
        btnImport.setAllCaps(false);
        btnImport.setTextColor(UiKit.color(ctx, R.color.brand));
        btnImport.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnImport.setPadding(UiKit.dp(ctx, 10), 0, UiKit.dp(ctx, 10), 0);
        btnImport.setOnClickListener(v -> pickImportKeys());
        Button btnExport = new Button(ctx);
        btnExport.setText("导出密钥");
        btnExport.setTextSize(13f);
        btnExport.setAllCaps(false);
        btnExport.setTextColor(UiKit.color(ctx, R.color.brand));
        btnExport.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnExport.setPadding(UiKit.dp(ctx, 10), 0, UiKit.dp(ctx, 10), 0);
        btnExport.setOnClickListener(v -> pickExportKeys());
        keyRow.addView(btnImport, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 44), 1));
        keyRow.addView(btnExport, new LinearLayout.LayoutParams(0, UiKit.dp(ctx, 44), 1));
        card.addView(keyRow, UiKit.lp(ctx, 6));

        Button btnDel = UiKit.secondaryButton(ctx, card, "删除此配置");
        btnDel.setTextColor(UiKit.color(ctx, R.color.danger));
        btnDel.setOnClickListener(v -> deleteProfile());

        tvResult = new TextView(ctx);
        tvResult.setTextSize(13f);
        tvResult.setLineSpacing(2f, 1f);
        tvResult.setPadding(0, UiKit.dp(ctx, 6), 0, 0);
        tvResult.setTextColor(UiKit.color(ctx, R.color.text_secondary));
        tvResult.setHint("填写后点「保存此配置」；点「新建配置」可另存一套而不覆盖现有。");
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
            String keyNote = keyCountLabel(cur);
            tvCurrent.setText("当前使用：" + (cur.name.isEmpty() ? "（未命名）" : cur.name)
                    + " · " + (cur.model.isEmpty() ? "?" : cur.model) + keyNote + "  ▾");
            loadProfile(cur);
        } else {
            tvCurrent.setText("尚未配置" + scopeLabel() + "模型，填写下方表单并保存  ▾");
            clearForm();
        }
    }

    private String keyCountLabel(ApiProfile p) {
        int n = p.effectiveKeys().size();
        if (n <= 0) return "";
        return n == 1 ? " · 1 key" : " · " + n + " keys";
    }

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
                    tvResult.setText("已切换当前" + scopeLabel() + "配置");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 新建 / 保存 / 删除 / 测试 ====================

    /** 新建配置：清空表单并进入「新增」语义，保存时不会覆盖现有配置 */
    void startNewProfile() {
        editingId = null;
        clearForm();
        tvResult.setTextColor(UiKit.color(activity, R.color.brand));
        tvResult.setText("已清空表单，填写后点「保存此配置」即可新建（现有配置不受影响）。");
    }

    void saveProfile() {
        String name = etName.getText().toString().trim();
        String base = etBase.getText().toString().trim();
        String model = etModel.getText().toString().trim();
        List<String> keys = parseKeys();
        if (name.isEmpty()) {
            tvResult.setTextColor(UiKit.color(activity, R.color.danger));
            tvResult.setText("请先填写配置名称");
            return;
        }
        if (keys.isEmpty()) {
            tvResult.setTextColor(UiKit.color(activity, R.color.danger));
            tvResult.setText("请至少填写一个 API Key");
            return;
        }

        ApiProfile existing = editingId == null ? null : findProfile(editingId);
        ApiProfile p;
        boolean isNew;
        if (existing == null) {
            p = new ApiProfile(apiManager.newId(), name, base, "", model);
            p.setApiKeys(keys);
            isNew = true;
        } else {
            p = existing;
            p.name = name;
            p.baseUrl = base;
            p.model = model;
            p.setApiKeys(keys);
            isNew = false;
        }
        apiManager.save(scope, p);
        apiManager.setCurrent(scope, p.id);
        editingId = p.id;
        syncSettings();
        refreshList();
        if (!base.isEmpty() && !keys.isEmpty() && !model.isEmpty()) {
            tvResult.setTextColor(UiKit.color(activity, R.color.text_secondary));
            tvResult.setText(isNew
                    ? "已新建「" + name + "」并设为当前，正在自动测试连接…"
                    : "已更新「" + name + "」，正在自动测试连接…");
            testConnection();
        } else {
            tvResult.setTextColor(UiKit.color(activity, R.color.success));
            tvResult.setText(isNew
                    ? "已新建「" + name + "」并设为当前"
                    : "已更新「" + name + "」");
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
        String key = parseKeys().isEmpty() ? "" : parseKeys().get(0);
        String model = etModel.getText().toString().trim();
        if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
            tvResult.setTextColor(UiKit.color(activity, R.color.danger));
            tvResult.setText("请先填写 Base URL / API Key / 模型名");
            return;
        }
        List<String> keys = parseKeys();
        tvResult.setTextColor(UiKit.color(activity, R.color.text_secondary));
        tvResult.setText(keys.size() > 1
                ? "正在用第 1 个 key 测试连接（共 " + keys.size() + " 个）…"
                : "正在测试，请稍候…");
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

    // ==================== 密钥池批量导入 / 导出 ====================

    private void pickImportKeys() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            activity.startActivityForResult(i, REQ_IMPORT_KEYS);
        } catch (Exception e) {
            tvResult.setTextColor(UiKit.color(activity, R.color.danger));
            tvResult.setText("无法打开文件选择器");
        }
    }

    private void pickExportKeys() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TITLE, "api_keys.txt");
            activity.startActivityForResult(i, REQ_EXPORT_KEYS);
        } catch (Exception e) {
            tvResult.setTextColor(UiKit.color(activity, R.color.danger));
            tvResult.setText("无法创建导出文件");
        }
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_IMPORT_KEYS && resultCode == Activity.RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri == null) return;
            new Thread(() -> {
                try {
                    java.io.InputStream is = activity.getContentResolver().openInputStream(uri);
                    byte[] buf = new byte[is.available()];
                    int off = 0;
                    while (off < buf.length) {
                        int r = is.read(buf, off, buf.length - off);
                        if (r < 0) break;
                        off += r;
                    }
                    is.close();
                    String content = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
                    List<String> imported = new ArrayList<>();
                    for (String line : content.split("\n")) {
                        String k = line.trim();
                        if (!k.isEmpty()) imported.add(k);
                    }
                    handler.post(() -> {
                        if (imported.isEmpty()) {
                            tvResult.setTextColor(UiKit.color(activity, R.color.warning));
                            tvResult.setText("文件中没有找到有效的 API Key");
                            return;
                        }
                        String old = etKey.getText().toString().trim();
                        StringBuilder sb = new StringBuilder();
                        if (!old.isEmpty()) sb.append(old).append("\n");
                        for (String k : imported) sb.append(k).append("\n");
                        etKey.setText(sb.toString().trim());
                        etKey.setSelection(etKey.getText().length());
                        tvResult.setTextColor(UiKit.color(activity, R.color.success));
                        tvResult.setText("已导入 " + imported.size() + " 个密钥，点「保存此配置」生效");
                    });
                } catch (Exception e) {
                    handler.post(() -> {
                        tvResult.setTextColor(UiKit.color(activity, R.color.danger));
                        tvResult.setText("读取密钥文件失败：" + e.getMessage());
                    });
                }
            }).start();
        } else if (requestCode == REQ_EXPORT_KEYS && resultCode == Activity.RESULT_OK && data != null) {
            android.net.Uri uri = data.getData();
            if (uri == null) return;
            List<String> keys = parseKeys();
            new Thread(() -> {
                try {
                    java.io.OutputStream os = activity.getContentResolver().openOutputStream(uri);
                    StringBuilder sb = new StringBuilder();
                    for (String k : keys) sb.append(k).append("\n");
                    os.write(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    os.close();
                    handler.post(() -> {
                        tvResult.setTextColor(UiKit.color(activity, R.color.success));
                        tvResult.setText("已导出 " + keys.size() + " 个密钥");
                    });
                } catch (Exception e) {
                    handler.post(() -> {
                        tvResult.setTextColor(UiKit.color(activity, R.color.danger));
                        tvResult.setText("导出失败：" + e.getMessage());
                    });
                }
            }).start();
        }
    }

    // ==================== 工具 ====================

    private List<String> parseKeys() {
        List<String> keys = new ArrayList<>();
        String raw = etKey.getText() == null ? "" : etKey.getText().toString();
        for (String line : raw.split("\n")) {
            String k = line.trim();
            if (!k.isEmpty() && !keys.contains(k)) keys.add(k);
        }
        return keys;
    }

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
        List<String> keys = p.effectiveKeys();
        if (keys.isEmpty()) {
            etKey.setText(p.apiKey);
        } else {
            StringBuilder sb = new StringBuilder();
            for (String k : keys) sb.append(k).append("\n");
            etKey.setText(sb.toString().trim());
        }
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
