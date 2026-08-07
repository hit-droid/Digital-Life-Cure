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

import com.digitallife.R;
import com.digitallife.brain.AICore;
import com.digitallife.brain.LLMClient;
import com.digitallife.care.CareModelsActivity;
import com.digitallife.model.ModelManager;
import com.digitallife.render.Live2DNative;
import com.digitallife.service.PetService;
import com.digitallife.util.ApiManager;
import com.digitallife.util.ApiProfile;
import com.digitallife.util.CrashHandler;
import com.digitallife.util.Settings;

/**
 * 微信式「设置」Tab：
 * API 配置（对话/护理多 Profile）、桌宠启停、悬浮窗/无障碍/TTS、语音互动、
 * 模型管理入口、记忆调试、关于。
 */
public class SettingsTabView extends LinearLayout {

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Settings settings;
    private final ApiManager apiManager;

    private Switch swVoice, swProactive;
    private EditText etPetName, etChat;
    private TextView tvStatus, tvVoiceDiag, tvMemoryDebug, tvCrashPath, tvModelList;

    private ApiProfileSection chatSection, careSection;
    private int currentModelIndex = 0;

    public SettingsTabView(Activity activity) {
        super(activity);
        this.activity = activity;
        this.settings = new Settings(activity);
        this.apiManager = new ApiManager(activity);
        setOrientation(LinearLayout.VERTICAL);
        setBackgroundColor(UiKit.color(activity, R.color.page_bg));
        buildUi();
        updateStatus();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(activity, 16), UiKit.dp(activity, 8),
                UiKit.dp(activity, 16), UiKit.dp(activity, 20));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 状态 ----------
        tvStatus = new TextView(activity);
        tvStatus.setTextSize(12f);
        tvStatus.setTextColor(Color.rgb(90, 150, 100));
        tvStatus.setPadding(0, UiKit.dp(activity, 4), 0, 0);
        root.addView(tvStatus, UiKit.lp(activity, 0));

        tvCrashPath = new TextView(activity);
        tvCrashPath.setTextSize(10f);
        tvCrashPath.setTextColor(Color.rgb(150, 140, 160));
        tvCrashPath.setPadding(0, UiKit.dp(activity, 4), 0, 0);
        tvCrashPath.setText("崩溃日志：" + CrashHandler.getCrashPath());
        root.addView(tvCrashPath, UiKit.lp(activity, 0));

        // ---------- 启停控制 ----------
        LinearLayout cCtrl = UiKit.card(activity, root, "启停控制");
        LinearLayout rowBtn = new LinearLayout(activity);
        rowBtn.setOrientation(LinearLayout.HORIZONTAL);
        Button btnStart = new Button(activity);
        btnStart.setText("启动桌宠");
        btnStart.setTextSize(14f);
        btnStart.setOnClickListener(v -> startPet());
        rowBtn.addView(btnStart, new LinearLayout.LayoutParams(0, UiKit.dp(activity, 52), 1));
        Button btnStop = new Button(activity);
        btnStop.setText("停止桌宠");
        btnStop.setTextSize(14f);
        btnStop.setOnClickListener(v -> stopPet());
        rowBtn.addView(btnStop, new LinearLayout.LayoutParams(0, UiKit.dp(activity, 52), 1));
        cCtrl.addView(rowBtn, UiKit.lp(activity, 0));

        // ---------- 悬浮窗权限 ----------
        LinearLayout cOverlay = UiKit.card(activity, root, "悬浮窗权限");
        Button btnOverlay = UiKit.button(activity, cOverlay, "授予悬浮窗权限 / 检查授权");
        btnOverlay.setOnClickListener(v -> requestOverlayPermission());

        // ---------- API 配置 ----------
        chatSection = new ApiProfileSection(activity, root, "对话大脑配置", ApiManager.SCOPE_CHAT, "AI 大脑");
        chatSection.tvResult.setHint("填写后点「测试 API 连接」，这里会显示详细结果。");
        careSection = new ApiProfileSection(activity, root, "护理大脑配置", ApiManager.SCOPE_CARE, "护理大脑");
        careSection.tvResult.setHint("护理大脑负责模型校验修复/动作创作，可与对话大脑使用不同 API。");

        // ---------- 功能设置 ----------
        LinearLayout cSwitch = UiKit.card(activity, root, "功能设置");
        etPetName = UiKit.input(activity, cSwitch, "角色名字（默认 小汐）", settings.getPetName());
        swVoice = UiKit.switchRow(activity, cSwitch, "语音互动（说话+发声）", settings.isVoiceEnabled());
        swProactive = UiKit.switchRow(activity, cSwitch, "自主行为（会主动找你说话）", settings.isProactiveEnabled());

        Button btnSwitchModel = UiKit.button(activity, cSwitch, "选择模型（当前: " + getCurrentModelName() + "）");
        btnSwitchModel.setOnClickListener(v -> showModelPicker(btnSwitchModel));

        final TextView tvModelListTmp = new TextView(activity);
        tvModelListTmp.setTextSize(11f);
        tvModelListTmp.setLineSpacing(2f, 1f);
        tvModelListTmp.setPadding(0, UiKit.dp(activity, 2), 0, 0);
        tvModelListTmp.setTextColor(Color.rgb(150, 140, 160));
        tvModelList = tvModelListTmp;
        cSwitch.addView(tvModelListTmp, UiKit.lp(activity, 0));

        // 模型管理入口（护理大脑模型面板）
        Button btnModels = UiKit.secondaryButton(activity, cSwitch, "打开模型管理（详情/删除）");
        btnModels.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(activity, CareModelsActivity.class));
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开模型管理: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        tvVoiceDiag = new TextView(activity);
        tvVoiceDiag.setTextSize(12f);
        tvVoiceDiag.setLineSpacing(2f, 1f);
        tvVoiceDiag.setPadding(0, UiKit.dp(activity, 6), 0, 0);
        tvVoiceDiag.setTextColor(Color.rgb(130, 125, 150));
        tvVoiceDiag.setText("桌宠运行中才能检测。如提示 TTS 失败，可点下方按钮到系统设置安装/启用语音合成数据。");
        cSwitch.addView(tvVoiceDiag, UiKit.lp(activity, 0));
        Button btnVoiceDiag = UiKit.button(activity, cSwitch, "检查语音引擎");
        btnVoiceDiag.setOnClickListener(v -> checkVoiceDiag());
        Button btnTtsInstall = UiKit.button(activity, cSwitch, "打开系统 TTS 安装/设置");
        btnTtsInstall.setOnClickListener(v -> openTtsInstall());

        Button btnAccessibility = UiKit.button(activity, cSwitch, "无障碍感知权限（可选，增强互动）");
        btnAccessibility.setOnClickListener(v -> requestAccessibilityPermission());
        TextView tvAccessibility = new TextView(activity);
        tvAccessibility.setTextSize(12f);
        tvAccessibility.setTextColor(Color.rgb(130, 125, 150));
        tvAccessibility.setText("授权后她也能感知你正在用什么 App、来了什么通知，从而主动搭话。不授权也不影响主功能。");
        cSwitch.addView(tvAccessibility, UiKit.lp(activity, 4));

        // ---------- 互动（语音 + 快速聊天） ----------
        LinearLayout cChat = UiKit.card(activity, root, "互动");
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
        chatRow.addView(etChat, new LinearLayout.LayoutParams(0, UiKit.dp(activity, 48), 1));
        Button btnChat = new Button(activity);
        btnChat.setText("发送");
        btnChat.setOnClickListener(v -> sendChat());
        chatRow.addView(btnChat, new LinearLayout.LayoutParams(UiKit.dp(activity, 80), UiKit.dp(activity, 48)));
        cChat.addView(chatRow, UiKit.lp(activity, 0));

        Button btnClear = UiKit.button(activity, cChat, "清空对话记忆");
        btnClear.setOnClickListener(v -> {
            AICore ai = getAiCore();
            if (ai != null) ai.getMemory().clearContext();
            Toast.makeText(activity, "记忆已清空", Toast.LENGTH_SHORT).show();
        });

        // ---------- 记忆调试 ----------
        LinearLayout cMemory = UiKit.card(activity, root, "记忆调试");
        tvMemoryDebug = new TextView(activity);
        tvMemoryDebug.setTextSize(12f);
        tvMemoryDebug.setLineSpacing(2f, 1f);
        tvMemoryDebug.setPadding(0, UiKit.dp(activity, 6), 0, 0);
        tvMemoryDebug.setTextColor(Color.rgb(130, 125, 150));
        tvMemoryDebug.setText("点击上方按钮查看当前记忆快照或执行本地自检。\n自检会写入一条测试 fact 和一条测试 summary。\n");
        cMemory.addView(tvMemoryDebug, UiKit.lp(activity, 0));
        Button btnMemoryDebug = UiKit.button(activity, cMemory, "查看记忆快照");
        btnMemoryDebug.setOnClickListener(v -> showMemoryDebug());
        Button btnMemorySelfCheck = UiKit.button(activity, cMemory, "运行记忆自检");
        btnMemorySelfCheck.setOnClickListener(v -> runMemorySelfCheck());

        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        refreshModelList();
    }

    // ==================== 逻辑 ====================

    public void onResume() {
        updateStatus();
    }

    private void saveConfig() {
        settings.setPetName(etPetName != null ? etPetName.getText().toString().trim() : settings.getPetName());
        settings.setVoiceEnabled(swVoice.isChecked());
        settings.setProactiveEnabled(swProactive.isChecked());
        chatSection.saveProfile();
        careSection.saveProfile();
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
            tvVoiceDiag.setTextColor(Color.rgb(200, 160, 40));
            tvVoiceDiag.setText("桌宠未启动，启动后才能检测语音引擎。");
            return;
        }
        tvVoiceDiag.setTextColor(Color.rgb(90, 150, 100));
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
            tvMemoryDebug.setTextColor(Color.rgb(200, 160, 40));
            tvMemoryDebug.setText("请先启动桌宠，再查看记忆快照。");
            return;
        }
        tvMemoryDebug.setTextColor(Color.rgb(90, 150, 100));
        tvMemoryDebug.setText(ai.getMemory().buildDebugSnapshot());
    }

    private void runMemorySelfCheck() {
        AICore ai = getAiCore();
        if (ai == null) {
            tvMemoryDebug.setTextColor(Color.rgb(200, 160, 40));
            tvMemoryDebug.setText("请先启动桌宠，再运行记忆自检。");
            return;
        }
        String result = ai.getMemory().runLocalSelfCheck();
        tvMemoryDebug.setTextColor(Color.rgb(90, 150, 100));
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
            Toast.makeText(activity, "请先启动桌宠再选择模型", Toast.LENGTH_SHORT).show();
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
                .setTitle("选择模型")
                .setSingleChoiceItems(marks, currentModelIndex, (d, which) -> {
                    currentModelIndex = which;
                    svc.switchToModel(which);
                    d.dismiss();
                    if (btnSwitchModel != null) {
                        btnSwitchModel.setText("选择模型（当前: " + getCurrentModelName() + "）");
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
}
