package com.digitallife.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.digitallife.R;
import com.digitallife.brain.AICore;
import com.digitallife.brain.LLMClient;
import com.digitallife.care.CareModelsActivity;
import com.digitallife.model.ModelManager;
import com.digitallife.render.Live2DNative;
import com.digitallife.service.PetService;
import com.digitallife.storage.DataPort;
import com.digitallife.storage.PendingRestore;
import com.digitallife.util.ApiManager;
import com.digitallife.util.ApiProfile;
import com.digitallife.util.CrashHandler;
import com.digitallife.util.PassphraseCrypto;
import com.digitallife.util.Settings;

/**
 * 微信式「设置」Tab：
 * API 配置（对话/护理多 Profile）、桌宠启停、悬浮窗/无障碍/TTS、语音互动、
 * 模型管理入口、记忆调试、关于。
 */
public class SettingsTabView extends LinearLayout {

    /** 导出数据（SAF 创建文档）的请求码，由 MainActivity 转发 */
    public static final int REQ_EXPORT_DATA = 7001;
    /** 从备份恢复（SAF 打开文档）的请求码，由 MainActivity 转发 */
    public static final int REQ_IMPORT_DATA = 7002;

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Settings settings;
    private final ApiManager apiManager;

    private Switch swVoice, swProactive, swEdgeDock, swAutoStart;
    private EditText etPetName, etChat;
    private TextView tvStatus, tvVoiceDiag, tvMemoryDebug, tvCrashPath, tvModelList;

    private ApiProfileSection modelSection;
    private int currentModelIndex = 0;

    /** 转发文件选择结果：导出数据落盘完成后提示 / 从备份恢复 */
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_EXPORT_DATA) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                askExportPassphrase(data.getData());
            }
            return true;
        }
        if (requestCode == REQ_IMPORT_DATA) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                importBackup(data.getData());
            }
            return true;
        }
        return false;
    }

    public SettingsTabView(Activity activity) {
        super(activity);
        this.activity = activity;
        this.settings = new Settings(activity);
        this.apiManager = new ApiManager(activity);
        setOrientation(LinearLayout.VERTICAL);
        setBackgroundColor(UiKit.color(activity, R.color.operit_bg));
        buildUi();
        updateStatus();
        // 启动后若刚应用过「从备份恢复」，第一时间汇报结果（清除后不再弹）
        handler.post(this::reportRestoreResultIfAny);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 8),
                UiKit.dp(activity, 12), UiKit.dp(activity, 20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // v1.23.0: Operit 风格角色卡 (OverviewCard) - 紫色背景
        LinearLayout overview = UiKit.roleCard(activity, root, "数字生命 · 小汐",
                "设置、状态与权限集中管理");
        tvStatus = new TextView(activity);
        tvStatus.setTextSize(12f);
        tvStatus.setTextColor(0xCCFFFFFF);
        tvStatus.setPadding(0, UiKit.dp(activity, 12), 0, 0);
        tvStatus.setIncludeFontPadding(false);
        overview.addView(tvStatus);
        // StatChip 行: 版本 / 启动状态 / 当前模型
        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, UiKit.dp(activity, 10), 0, 0);
        overview.addView(chips);
        UiKit.statChip(activity, chips, "v" + UiKit.appVersion(activity));
        UiKit.statChip(activity, chips, settings.isConfigured() ? "已配置" : "未配置");

        // ---------- 4 个分组（v1.21.0 分组结构 + Operit 色板） ----------
        // 分组内容保持透明：卡片自带圆角 surface 面板（UiKit.card），
        // 若再给分组铺一层同色底，内外面板同色，卡片边界就看不出来了。
        LinearLayout gStart = UiKit.expandableCard(activity, root, "启动与权限", true);
        LinearLayout gMain = UiKit.expandableCard(activity, root, "桌宠与功能", true);
        LinearLayout gChat = UiKit.expandableCard(activity, root, "互动", true);
        LinearLayout gDev = UiKit.expandableCard(activity, root, "开发者与调试", false);

        // ---------- 启停控制 ----------
        LinearLayout cCtrl = UiKit.card(activity, gStart, "启停控制");
        LinearLayout rowBtn = new LinearLayout(activity);
        rowBtn.setOrientation(LinearLayout.HORIZONTAL);
        Button btnStart = new Button(activity);
        btnStart.setHapticFeedbackEnabled(true);   // 自动生成：haptic
        btnStart.setContentDescription("启动");   // 自动生成：a11y
        btnStart.setText("启动桌宠");
        btnStart.setTextSize(14f);
        btnStart.setAllCaps(false);
        btnStart.setTextColor(Color.WHITE);
        btnStart.setBackgroundResource(R.drawable.bg_btn_primary);
        btnStart.setOnClickListener(v -> startPet());
        rowBtn.addView(btnStart, new LinearLayout.LayoutParams(0, UiKit.dp(activity, 48), 1));
        Button btnStop = new Button(activity);
        btnStop.setHapticFeedbackEnabled(true);   // 自动生成：haptic
        btnStop.setContentDescription("停止");   // 自动生成：a11y
        btnStop.setText("停止桌宠");
        btnStop.setTextSize(14f);
        btnStop.setAllCaps(false);
        btnStop.setTextColor(UiKit.color(activity, R.color.operit_accent));
        btnStop.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnStop.setOnClickListener(v -> stopPet());
        rowBtn.addView(btnStop, new LinearLayout.LayoutParams(0, UiKit.dp(activity, 48), 1));
        cCtrl.addView(rowBtn, UiKit.lp(activity, 0));

        // ---------- 悬浮窗权限 ----------
        LinearLayout cOverlay = UiKit.card(activity, gStart, "悬浮窗权限");
        Button btnOverlay = UiKit.button(activity, cOverlay, "授予悬浮窗权限 / 检查授权");
        btnOverlay.setOnClickListener(v -> requestOverlayPermission());

        // ---------- 模型配置（统一管理对话/护理两套） ----------
        modelSection = new ApiProfileSection(activity, gMain);

        // ---------- 功能设置 ----------
        LinearLayout cSwitch = UiKit.card(activity, gMain, "功能设置");
        etPetName = UiKit.input(activity, cSwitch, "角色名字（默认 小汐）", settings.getPetName());
        swVoice = UiKit.switchRow(activity, cSwitch, "语音互动（说话+发声）", settings.isVoiceEnabled());
        swProactive = UiKit.switchRow(activity, cSwitch, "自主行为（会主动找你说话）", settings.isProactiveEnabled());
        swEdgeDock = UiKit.switchRow(activity, cSwitch, "贴边停靠（松手吸附到屏幕边缘）", settings.isEdgeDockEnabled());
        swAutoStart = UiKit.switchRow(activity, cSwitch, "开机自动启动（重启后桌宠自己回来）", settings.isAutoStartEnabled());

        Button btnAccessibility = UiKit.button(activity, cSwitch, "无障碍感知权限（可选，增强互动）");
        btnAccessibility.setOnClickListener(v -> requestAccessibilityPermission());
        TextView tvAccessibility = new TextView(activity);
        tvAccessibility.setTextSize(13f);
        tvAccessibility.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
        tvAccessibility.setText("授权后她也能感知你正在用什么 App、来了什么通知，从而主动搭话。不授权也不影响主功能。");
        cSwitch.addView(tvAccessibility, UiKit.lp(activity, 4));

        // ---------- 桌宠形象（Live2D 形象管理，与上面的 LLM 模型配置区分开） ----------
        LinearLayout cAppearance = UiKit.card(activity, gMain, "桌宠形象");
        Button btnSwitchModel = UiKit.button(activity, cAppearance, "切换形象（当前: " + getCurrentModelName() + "）");
        btnSwitchModel.setOnClickListener(v -> showModelPicker(btnSwitchModel));

        final TextView tvModelListTmp = new TextView(activity);
        tvModelListTmp.setTextSize(11f);
        tvModelListTmp.setLineSpacing(2f, 1f);
        tvModelListTmp.setPadding(0, UiKit.dp(activity, 2), 0, 0);
        tvModelListTmp.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
        tvModelList = tvModelListTmp;
        cAppearance.addView(tvModelListTmp, UiKit.lp(activity, 0));

        // 形象管理入口（护理大脑模型面板）
        Button btnModels = UiKit.secondaryButton(activity, cAppearance, "打开形象管理（详情/删除）");
        btnModels.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(activity, CareModelsActivity.class));
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开形象管理: " + com.digitallife.ui.UiKit.safeMsg(e), Toast.LENGTH_SHORT).show();
            }
        });

        // ---------- 记忆管理 ----------
        LinearLayout cMemory = UiKit.card(activity, gMain, "记忆管理");
        Button btnMemory = UiKit.button(activity, cMemory, "打开记忆管理（查看 / 修正 / 备份）");
        btnMemory.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(activity, MemoryManageActivity.class));
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开记忆管理: " + com.digitallife.ui.UiKit.safeMsg(e), Toast.LENGTH_SHORT).show();
            }
        });

        // ---------- 数据与隐私（v1.140.0：密钥加密 / 数据导出 / 一键清除） ----------
        LinearLayout cPrivacy = UiKit.card(activity, gMain, "数据与隐私");

        TextView tvSecure = new TextView(activity);
        tvSecure.setTextSize(12f);
        tvSecure.setLineSpacing(2f, 1f);
        tvSecure.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
        tvSecure.setText(settings.isSecureStorageSupported()
                ? "API Key 已用系统密钥库（Android Keystore）加密保存，且不随系统备份导出。"
                : "当前系统（Android 5.x）没有密钥库加密，API Key 以明文保存，请注意。");
        cPrivacy.addView(tvSecure, UiKit.lp(activity, 0));

        Button btnExport = UiKit.button(activity, cPrivacy, "导出我的数据（对话 / 记忆 / 角色 / 设置，可加口令）");
        btnExport.setOnClickListener(v -> exportData());

        Button btnImport = UiKit.secondaryButton(activity, cPrivacy, "从备份恢复（导入先前导出的备份）");
        btnImport.setOnClickListener(v -> pickBackup());

        Button btnWipe = UiKit.secondaryButton(activity, cPrivacy, "清除全部数据（不含已导入模型）");
        btnWipe.setOnClickListener(v -> confirmClearData());

        Button btnPrivacy = UiKit.secondaryButton(activity, cPrivacy, "隐私说明");
        btnPrivacy.setOnClickListener(v -> showPrivacy());

        // ---------- 互动（语音 + 快速聊天） ----------
        // 组头已叫「互动」，卡片内标题不能同名，否则截图上「互动」上下连着出现两次。
        LinearLayout cChat = UiKit.card(activity, gChat, "语音与快速聊天");
        Button btnVoice = UiKit.button(activity, cChat, "按住说话（语音对话）");
        btnVoice.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startListening();
                    v.setAlpha(0.6f);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopListening();
                    v.setAlpha(1f);
                    return true;
            }
            return true;
        });

        LinearLayout chatRow = new LinearLayout(activity);
        chatRow.setOrientation(LinearLayout.HORIZONTAL);
        etChat = new EditText(activity);
        etChat.setHint("输入一句话和她聊天…");
        etChat.setSingleLine(true);
        etChat.setHintTextColor(UiKit.color(activity, R.color.operit_text_hint));
        etChat.setTextSize(14f);
        etChat.setTextColor(UiKit.color(activity, R.color.operit_text_primary));
        etChat.setBackgroundResource(R.drawable.bg_input);
        etChat.setPadding(UiKit.dp(activity, 12), 0, UiKit.dp(activity, 12), 0);
        chatRow.addView(etChat, new LinearLayout.LayoutParams(0, UiKit.dp(activity, 48), 1));
        Button btnChat = new Button(activity);
        btnChat.setHapticFeedbackEnabled(true);   // 自动生成：haptic
        btnChat.setContentDescription("btnChat");   // 自动生成：a11y
        btnChat.setText("发送");
        btnChat.setAllCaps(false);
        btnChat.setTextColor(Color.WHITE);
        btnChat.setTextSize(14f);
        btnChat.setBackgroundResource(R.drawable.bg_btn_primary);
        btnChat.setOnClickListener(v -> sendChat());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(UiKit.dp(activity, 80), UiKit.dp(activity, 48));
        blp.leftMargin = UiKit.dp(activity, 8);
        chatRow.addView(btnChat, blp);
        cChat.addView(chatRow, UiKit.lp(activity, 0));

        Button btnClear = UiKit.button(activity, cChat, "清空对话记忆");
        btnClear.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle("清空对话记忆")
                    .setMessage("确定清空她的全部对话记忆吗？此操作不可撤销。")
                    .setPositiveButton("清空", (d, w) -> {
                        AICore ai = getAiCore();
                        if (ai != null) ai.getMemory().clearContext();
                        Toast.makeText(activity, "记忆已清空", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        // ---------- 开发者选项（折叠） ----------
        LinearLayout cDev = UiKit.card(activity, gDev, "开发者选项");
        TextView devHint = new TextView(activity);
        devHint.setText("面向调试的高级功能，日常使用无需打开。");
        devHint.setTextSize(13f);
        devHint.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
        devHint.setLineSpacing(2f, 1f);
        cDev.addView(devHint, UiKit.lp(activity, 0));

        LinearLayout devBody = new LinearLayout(activity);
        devBody.setOrientation(LinearLayout.VERTICAL);
        devBody.setVisibility(View.GONE);

        tvCrashPath = new TextView(activity);
        tvCrashPath.setTextSize(10f);
        tvCrashPath.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
        tvCrashPath.setPadding(0, UiKit.dp(activity, 4), 0, 0);
        tvCrashPath.setText("崩溃日志：" + CrashHandler.getCrashPath());
        devBody.addView(tvCrashPath, UiKit.lp(activity, 0));

        tvVoiceDiag = new TextView(activity);
        tvVoiceDiag.setTextSize(13f);
        tvVoiceDiag.setLineSpacing(2f, 1f);
        tvVoiceDiag.setPadding(0, UiKit.dp(activity, 6), 0, 0);
        tvVoiceDiag.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
        tvVoiceDiag.setText("桌宠运行中才能检测。如提示 TTS 失败，可点下方按钮到系统设置安装/启用语音合成数据。");
        devBody.addView(tvVoiceDiag, UiKit.lp(activity, 0));
        Button btnVoiceDiag = UiKit.button(activity, devBody, "检查语音引擎");
        btnVoiceDiag.setOnClickListener(v -> checkVoiceDiag());
        Button btnTtsInstall = UiKit.button(activity, devBody, "打开系统 TTS 安装/设置");
        btnTtsInstall.setOnClickListener(v -> openTtsInstall());

        tvMemoryDebug = new TextView(activity);
        tvMemoryDebug.setTextSize(13f);
        tvMemoryDebug.setLineSpacing(2f, 1f);
        tvMemoryDebug.setPadding(0, UiKit.dp(activity, 6), 0, 0);
        tvMemoryDebug.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
        tvMemoryDebug.setText("点击下方按钮查看当前记忆快照或执行本地自检。\n自检会写入一条测试 fact 和一条测试 summary。");
        devBody.addView(tvMemoryDebug, UiKit.lp(activity, 0));
        Button btnMemoryDebug = UiKit.button(activity, devBody, "查看记忆快照");
        btnMemoryDebug.setOnClickListener(v -> showMemoryDebug());
        Button btnMemorySelfCheck = UiKit.button(activity, devBody, "运行记忆自检");
        btnMemorySelfCheck.setOnClickListener(v -> runMemorySelfCheck());

        Button btnToggleDev = UiKit.secondaryButton(activity, cDev, "展开调试功能");
        btnToggleDev.setOnClickListener(v -> {
            boolean show = devBody.getVisibility() != View.VISIBLE;
            devBody.setVisibility(show ? View.VISIBLE : View.GONE);
            btnToggleDev.setText(show ? "收起调试功能" : "展开调试功能");
        });
        cDev.addView(devBody, UiKit.lp(activity, 4));

        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        refreshModelList();
    }

    // ==================== 逻辑 ====================

    public void onResume() {
        updateStatus();
        reportRestoreResultIfAny();
    }

    /**
     * 汇报「从备份恢复」的结果（由 {@code com.digitallife.App} 在启动恢复后写入
     * {@link Settings#getRestoreResult()}），同一结果只弹一次。
     */
    private void reportRestoreResultIfAny() {
        String r = settings.getRestoreResult();
        if (r == null || r.isEmpty()) return;
        settings.clearRestoreResult();

        int sep = r.indexOf('|');
        String kind = sep >= 0 ? r.substring(0, sep) : r;
        String detail = sep >= 0 ? r.substring(sep + 1) : "";
        final String title;
        final String msg;
        if ("ok".equals(kind)) {
            title = "恢复完成";
            msg = "已从备份恢复 " + detail + " 个文件，当前数据已是备份内容。";
        } else {
            title = "恢复失败";
            msg = "未能从备份恢复：" + detail + "\n\n原有数据未被覆盖，可换个备份重试。";
        }
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void saveConfig() {
        settings.setPetName(etPetName != null ? etPetName.getText().toString().trim() : settings.getPetName());
        settings.setVoiceEnabled(swVoice.isChecked());
        settings.setProactiveEnabled(swProactive.isChecked());
        settings.setEdgeDockEnabled(swEdgeDock.isChecked());
        settings.setAutoStartEnabled(swAutoStart.isChecked());
        modelSection.saveCurrentProfile();
        PetService svc = PetService.getInstance();
        if (svc != null) {
            apiManager.syncCurrentToSettings(ApiManager.SCOPE_CHAT, settings);
            svc.reconfigureBrain();
            Toast.makeText(activity, "配置已保存，大脑已热切换", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(activity, "配置已保存", Toast.LENGTH_SHORT).show();
        }
    }

    private void startPet() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(activity)) {
            Toast.makeText(activity, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        saveConfig();
        requestNotificationPermission();
        requestRecordPermission();
        Intent i = new Intent(activity, PetService.class);
        i.setAction(PetService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(i);
        } else {
            activity.startService(i);
        }
        Toast.makeText(activity, "数字生命正在醒来…", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::updateStatus, 1500);
    }

    private void stopPet() {
        Intent i = new Intent(activity, PetService.class);
        i.setAction(PetService.ACTION_STOP);
        activity.startService(i);
        Toast.makeText(activity, "数字生命已休息", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::updateStatus, 800);
    }

    private AICore getAiCore() {
        PetService svc = PetService.getInstance();
        return svc != null ? svc.getAiCore() : null;
    }

    private void sendChat() {
        String text = etChat.getText().toString().trim();
        if (text.isEmpty()) return;
        if (!settings.isConfigured()) {
            Toast.makeText(activity, "请先配置 API 并保存", Toast.LENGTH_LONG).show();
            return;
        }
        etChat.setText("");
        AICore ai = getAiCore();
        if (ai != null) {
            ai.onUserSays(text);
            Toast.makeText(activity, "已发送", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(activity, "请先启动桌宠", Toast.LENGTH_SHORT).show();
        }
    }

    private void startListening() {
        requestRecordPermission();
        PetService svc = PetService.getInstance();
        if (svc == null) {
            Toast.makeText(activity, "请先启动桌宠", Toast.LENGTH_SHORT).show();
            return;
        }
        svc.toggleListening();
    }

    private void stopListening() {
        PetService svc = PetService.getInstance();
        if (svc != null) svc.toggleListening();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(activity)) {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            try {
                activity.startActivity(intent);
            } catch (Exception e) {
                activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
            Toast.makeText(activity, "请打开开关授权悬浮窗", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(activity, "已具备悬浮窗权限", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            try {
                activity.startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开系统无障碍设置，请到 设置→无障碍 手动开启", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(activity, "请找到本应用并开启「无障碍感知」服务", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(activity, "无障碍感知已开启", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        String expected = activity.getPackageName() + "/" + "com.digitallife.service.PetAccessibilityService";
        String enabledServices = android.provider.Settings.Secure.getString(activity.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        return enabledServices.contains(expected);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            }
        }
    }

    private void requestRecordPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            }
        }
    }

    private void checkVoiceDiag() {
        PetService svc = PetService.getInstance();
        if (svc == null) {
            tvVoiceDiag.setTextColor(UiKit.color(activity, R.color.warning));
            tvVoiceDiag.setText("桌宠未启动，启动后才能检测语音引擎。");
            return;
        }
        tvVoiceDiag.setTextColor(UiKit.color(activity, R.color.success));
        tvVoiceDiag.setText(svc.getVoiceDiag());
    }

    private void openTtsInstall() {
        PetService svc = PetService.getInstance();
        if (svc == null) {
            Toast.makeText(activity, "请先启动桌宠", Toast.LENGTH_SHORT).show();
            return;
        }
        svc.openTtsInstall();
    }

    private void showMemoryDebug() {
        AICore ai = getAiCore();
        if (ai == null) {
            tvMemoryDebug.setTextColor(UiKit.color(activity, R.color.warning));
            tvMemoryDebug.setText("请先启动桌宠，再查看记忆快照。");
            return;
        }
        tvMemoryDebug.setTextColor(UiKit.color(activity, R.color.success));
        tvMemoryDebug.setText(ai.getMemory().buildDebugSnapshot());
    }

    private void runMemorySelfCheck() {
        AICore ai = getAiCore();
        if (ai == null) {
            tvMemoryDebug.setTextColor(UiKit.color(activity, R.color.warning));
            tvMemoryDebug.setText("请先启动桌宠，再运行记忆自检。");
            return;
        }
        String result = ai.getMemory().runLocalSelfCheck();
        tvMemoryDebug.setTextColor(UiKit.color(activity, R.color.success));
        tvMemoryDebug.setText("自检结果：" + result + "\n\n" + ai.getMemory().buildDebugSnapshot());
    }

    private String getCurrentModelName() {
        int count = Live2DNative.nativeGetModelCount();
        if (count <= 0) return "?";
        if (currentModelIndex >= count) currentModelIndex = 0;
        return Live2DNative.nativeGetModelDirName(currentModelIndex);
    }

    private void showModelPicker(Button btnSwitchModel) {
        PetService svc = PetService.getInstance();
        if (svc == null) {
            Toast.makeText(activity, "请先启动桌宠再切换形象", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = Live2DNative.nativeGetModelCount();
        if (count <= 0) return;
        final String[] names = new String[count];
        final String[] marks = new String[count];
        for (int i = 0; i < count; i++) {
            String n = Live2DNative.nativeGetModelDirName(i);
            if (n == null || n.isEmpty()) n = "?";
            names[i] = n;
            marks[i] = n;
            if (ModelManager.listImportedModelDirs(activity).contains(n)) {
                marks[i] += "（已导入）";
            }
        }
        new AlertDialog.Builder(activity)
                .setTitle("切换形象")
                .setSingleChoiceItems(marks, currentModelIndex, (d, which) -> {
                    currentModelIndex = which;
                    svc.switchToModel(which);
                    d.dismiss();
                    if (btnSwitchModel != null) {
                        btnSwitchModel.setText("切换形象（当前: " + getCurrentModelName() + "）");
                    }
                    refreshModelList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void refreshModelList() {
        if (tvModelList == null) return;
        syncModelIndexWithDefault();
        StringBuilder sb = new StringBuilder();
        int count = Live2DNative.nativeGetModelCount();
        for (int i = 0; i < count; i++) {
            String name = Live2DNative.nativeGetModelDirName(i);
            if (name == null || name.isEmpty()) continue;
            sb.append(i == currentModelIndex ? "● " : "○ ").append(name);
            if (ModelManager.listImportedModelDirs(activity).contains(name)) {
                sb.append("（已导入）");
            } else if (i < 2) {
                sb.append("（内置）");
            }
            sb.append("\n");
        }
        if (sb.length() == 0) sb.append("（无可用模型）");
        tvModelList.setText(sb.toString());
    }

    private void syncModelIndexWithDefault() {
        String def = settings.getDefaultModelDir();
        if (def == null || def.isEmpty()) return;
        int count = Live2DNative.nativeGetModelCount();
        for (int i = 0; i < count; i++) {
            String n = Live2DNative.nativeGetModelDirName(i);
            if (def.equals(n)) {
                currentModelIndex = i;
                return;
            }
        }
    }

    private void updateStatus() {
        if (tvStatus == null) return;
        boolean running = PetService.getInstance() != null;
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || android.provider.Settings.canDrawOverlays(activity);
        boolean configured = settings.isConfigured();
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(running ? "● 桌宠运行中" : "○ 未启动\n");
        sb.append("悬浮窗权限：").append(overlay ? "✓ 已授权" : "✗ 未授权\n");
        sb.append("无障碍感知：").append(isAccessibilityServiceEnabled() ? "✓ 已开启" : "○ 未开启\n");
        sb.append("API 配置：").append(configured ? "✓ 已配置" : "✗ 未配置\n");
        ApiProfile cur = apiManager.getCurrent(ApiManager.SCOPE_CHAT);
        if (cur != null) {
            sb.append("对话大脑：").append(cur.name.isEmpty() ? "（未命名）" : cur.name).append("\n");
            sb.append("模型：").append(cur.model.isEmpty() ? "（空）" : cur.model);
        } else {
            sb.append("模型：").append(settings.getModel().isEmpty() ? "（空）" : settings.getModel());
        }
        tvStatus.setText(sb.toString());
    }

    // ==================== 数据与隐私（v1.140.0） ====================

    private void toast(String msg) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
    }

    /** files / shared_prefs / databases 三个根目录（任一可能为 null，由 DataPort 容错） */
    private File[] dataDirs() {
        File filesDir = activity.getFilesDir();
        File parent = filesDir != null ? filesDir.getParentFile() : null;
        File prefsDir = parent != null ? new File(parent, "shared_prefs") : null;
        File dbFile = activity.getDatabasePath("memory.db");
        File dbDir = dbFile != null ? dbFile.getParentFile() : null;
        return new File[]{filesDir, prefsDir, dbDir};
    }

    private void exportData() {
        try {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, "digital-life-backup-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US)
                    .format(new java.util.Date()) + ".zip");
            activity.startActivityForResult(i, REQ_EXPORT_DATA);
        } catch (Exception e) {
            toast("无法打开保存对话框：" + UiKit.safeMsg(e));
        }
    }

    // ---------- 导出（v1.146.0：可选口令加密） ----------

    /** 导出入口：SAF 选好保存位置后，询问是否加口令 */
    private void askExportPassphrase(final Uri uri) {
        new AlertDialog.Builder(activity)
                .setTitle("导出备份")
                .setMessage("可以给备份设置一个口令：文件即使经云盘/聊天工具转发泄露，没有口令也打不开。\n\n"
                        + "口令不会保存在任何地方，请务必记牢——忘记将无法恢复。")
                .setPositiveButton("设置口令", (d, w) -> promptNewPassphrase(uri))
                .setNegativeButton("不加密", (d, w) -> doExport(uri, null))
                .setNeutralButton("取消", null)
                .show();
    }

    private void promptNewPassphrase(final Uri uri) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("口令（至少 6 位）");
        new AlertDialog.Builder(activity)
                .setTitle("设置备份口令")
                .setView(input)
                .setPositiveButton("确定", (d, w) -> {
                    String s = input.getText().toString();
                    if (s.length() < 6) {
                        toast("口令至少 6 位，请重试");
                        promptNewPassphrase(uri);
                        return;
                    }
                    doExport(uri, s.toCharArray());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doExport(final Uri uri, final char[] passphrase) {
        toast("正在导出…");
        new Thread(() -> {
            final File tmpZip = new File(activity.getCacheDir(), "export_tmp.zip");
            int count = 0;
            try {
                File[] d = dataDirs();
                try (OutputStream zos = new FileOutputStream(tmpZip)) {
                    count = DataPort.exportZip(zos, DataPort.exportEntries(d[0], d[1], d[2]));
                }
                byte[] payload = readFile(tmpZip);
                if (passphrase != null) {
                    payload = PassphraseCrypto.encrypt(passphrase, payload);
                }
                OutputStream os = activity.getContentResolver().openOutputStream(uri);
                if (os == null) throw new IOException("无法写入所选位置");
                try {
                    os.write(payload);
                    os.flush();
                } finally {
                    os.close();
                }
            } catch (final Exception e) {
                handler.post(() -> toast("导出失败：" + UiKit.safeMsg(e)));
                return;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmpZip.delete();
            }
            final int n = count;
            final boolean encrypted = passphrase != null;
            handler.post(() -> toast(encrypted
                    ? "已导出 " + n + " 个文件（加密备份，请牢记口令）"
                    : "已导出 " + n + " 个文件"));
        }, "data-export").start();
    }

    // ---------- 从备份恢复（v1.146.0） ----------

    private void pickBackup() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            activity.startActivityForResult(i, REQ_IMPORT_DATA);
        } catch (Exception e) {
            toast("无法打开文件选择器：" + UiKit.safeMsg(e));
        }
    }

    private void importBackup(final Uri uri) {
        toast("正在读取备份…");
        new Thread(() -> {
            final byte[] blob;
            try {
                blob = readUri(uri);
            } catch (final Exception e) {
                handler.post(() -> toast("读取备份失败：" + UiKit.safeMsg(e)));
                return;
            }
            handler.post(() -> {
                if (PassphraseCrypto.isEncrypted(blob)) {
                    promptImportPassphrase(blob);
                } else {
                    confirmRestore(blob);
                }
            });
        }, "backup-read").start();
    }

    private void promptImportPassphrase(final byte[] blob) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("导出时设置的口令");
        new AlertDialog.Builder(activity)
                .setTitle("加密备份")
                .setMessage("该备份已加密，请输入导出时设置的口令。")
                .setView(input)
                .setPositiveButton("解密", (d, w) ->
                        decryptBackup(blob, input.getText().toString().toCharArray()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void decryptBackup(final byte[] blob, final char[] passphrase) {
        toast("正在解密…");
        new Thread(() -> {
            final byte[] zip;
            try {
                zip = PassphraseCrypto.decrypt(passphrase, blob);
            } catch (final Exception e) {
                handler.post(() -> toast("口令错误或备份已损坏"));
                return;
            }
            handler.post(() -> confirmRestore(zip));
        }, "backup-decrypt").start();
    }

    private void confirmRestore(final byte[] zip) {
        new AlertDialog.Builder(activity)
                .setTitle("从备份恢复")
                .setMessage("将用备份内容覆盖：对话与记忆、角色设定、全部设置（含 API 配置）。\n"
                        + "不会动已导入的模型。\n恢复需重启应用后生效，是否继续？")
                .setPositiveButton("恢复并重启", (d, w) -> stageAndRestart(zip))
                .setNegativeButton("取消", null)
                .show();
    }

    private void stageAndRestart(final byte[] zip) {
        try {
            PendingRestore.stage(activity.getCacheDir(), zip);
        } catch (Exception e) {
            toast("准备恢复失败：" + UiKit.safeMsg(e));
            return;
        }
        toast("已准备恢复，应用将重启以生效…");
        restartApp();
    }

    /** 重启进程，让 {@code com.digitallife.App} 在存储打开前应用待恢复数据 */
    private void restartApp() {
        try {
            Intent i = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                activity.startActivity(i);
            }
        } catch (Exception ignored) {
            // 拉不起来就让用户手动重启
        }
        handler.postDelayed(() -> {
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }, 500);
    }

    private byte[] readUri(Uri uri) throws IOException {
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("无法读取所选文件");
            return readAll(in);
        }
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private void confirmClearData() {
        new AlertDialog.Builder(activity)
                .setTitle("清除全部数据")
                .setMessage("将删除：对话与记忆、角色设定、全部设置（含 API 配置）。\n"
                        + "不会删除已导入的模型。\n此操作不可撤销。")
                .setPositiveButton("继续", (d, w) -> confirmClearDataAgain())
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmClearDataAgain() {
        new AlertDialog.Builder(activity)
                .setTitle("再确认一次")
                .setMessage("真的要清除吗？清除后需要重新配置 API 才能继续对话。")
                .setPositiveButton("清除", (d, w) -> clearData())
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearData() {
        try {
            File[] d = dataDirs();
            int n = DataPort.clear(d[0], d[1], d[2]);
            toast("已清除 " + n + " 项，重启应用后生效");
        } catch (Exception e) {
            toast("清除失败：" + UiKit.safeMsg(e));
        }
    }

    private void showPrivacy() {
        new AlertDialog.Builder(activity)
                .setTitle("隐私说明")
                .setMessage(
                        "• 对话内容、记忆与角色设定，会在你发起对话时发送到你配置的第三方 AI 服务"
                                + "（即设置里的 API 地址），用于生成回复与提取记忆。本应用不运营任何中转服务器。\n\n"
                                + "• API Key 只保存在本机；支持的机型上用系统密钥库加密，且不随系统备份导出。\n\n"
                                + "• 对话与记忆只存在本机（配置与数据库中），可随时在上方导出或清除。\n\n"
                                + "• 无障碍感知为可选授权，仅用于判断前台应用与通知，在本机处理，不上传。")
                .setPositiveButton("知道了", null)
                .show();
    }
}
