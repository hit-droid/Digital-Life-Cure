package com.maiden.pet.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.maiden.pet.CrashHandler;
import com.maiden.pet.PetService;
import com.maiden.pet.brain.AICore;
import com.maiden.pet.brain.LLMClient;
import com.maiden.pet.memory.Settings;
import com.maiden.pet.render.Live2DNative;

/**
 * 桌宠控制面板：悬浮窗授权引导 + API 配置 + 连接测试 + 语音引擎诊断 + 启停控制。
 */
public class MainActivity extends Activity {

    private static final String TAG = "AI_PET";
    private Settings settings;
    private EditText etBase, etKey, etName;
    private AutoCompleteTextView etModel;
    private Switch swVoice, swProactive;
    private Button btnOverlay, btnVoice, btnChat, btnStart, btnStop, btnSave, btnClear, btnTest, btnVoiceDiag, btnMemoryDebug, btnMemorySelfCheck;
    private EditText etChat;
    private TextView tvStatus, tvCrashPath, tvTestResult, tvVoiceDiag, tvMemoryDebug;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CrashHandler.init(this);

        settings = new Settings(this);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(247, 246, 251));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题
        TextView title = new TextView(this);
        title.setText("AI 桌宠 · 小汐");
        title.setTextSize(26f);
        title.setTextColor(Color.rgb(96, 74, 210));
        title.setGravity(Gravity.CENTER);
        root.addView(title, lp(0));

        TextView subtitle = new TextView(this);
        subtitle.setText("住在桌面上的二次元生命。配置好后点击「启动桌宠」，她就会出现在桌面上。");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(Color.rgb(120, 110, 140));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, dp(6), 0, dp(10));
        root.addView(subtitle, lp(0));

        // ---------- 1. 悬浮窗权限 ----------
        LinearLayout cOverlay = card(root, "悬浮窗权限");
        btnOverlay = button(cOverlay, "授予悬浮窗权限 / 检查授权");
        btnOverlay.setOnClickListener(v -> requestOverlayPermission());

        // ---------- 2. AI 大脑配置 ----------
        LinearLayout cApi = card(root, "AI 大脑配置");
        etBase = input(cApi, "API Base URL（如 https://api.deepseek.com/v1）", settings.getApiBase());
        etKey = input(cApi, "API Key", settings.getApiKey());
        etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // 模型名：AutoCompleteTextView，支持常用模型下拉 + 自定义输入
        etModel = new AutoCompleteTextView(this);
        etModel.setHint("模型名（可下拉选择或自定义）");
        if (settings.getModel() != null) etModel.setText(settings.getModel());
        etModel.setTextSize(14f);
        etModel.setBackground(getEditBg());
        etModel.setPadding(dp(10), dp(8), dp(10), dp(8));
        cApi.addView(etModel, lp(8));
        // 常用模型下拉提示（可自由输入自定义模型名）
        String[] commonModels = {
                "deepseek-chat",
                "deepseek-reasoner",
                "deepseek-v4-flash-0731",
                "gpt-4o",
                "gpt-4o-mini",
                "gpt-4.1",
                "gpt-4.1-mini",
                "claude-3-5-sonnet",
                "claude-3-5-haiku",
                "qwen-plus",
                "qwen-turbo",
                "glm-4",
                "glm-4-flash",
                "moonshot-v1-8k",
                "kimi-k2"
        };
        etModel.setAdapter(new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, commonModels));
        etModel.setThreshold(0);
        etName = input(cApi, "角色名字（默认 小汐）", settings.getPetName());
        btnSave = button(cApi, "保存配置");
        btnSave.setOnClickListener(v -> saveConfig());
        btnTest = button(cApi, "测试 API 连接");
        btnTest.setOnClickListener(v -> testConnection());
        tvTestResult = new TextView(this);
        tvTestResult.setTextSize(12f);
        tvTestResult.setLineSpacing(2f, 1f);
        tvTestResult.setPadding(0, dp(6), 0, 0);
        tvTestResult.setText("填写后点「测试 API 连接」，这里会显示详细结果。");
        tvTestResult.setTextColor(Color.rgb(130, 125, 150));
        cApi.addView(tvTestResult, lp(0));

        // ---------- 3. 功能开关 ----------
        LinearLayout cSwitch = card(root, "功能设置");
        swVoice = switchRow(cSwitch, "语音互动（说话+发声）", settings.isVoiceEnabled());
        swProactive = switchRow(cSwitch, "自主行为（会主动找你说话）", settings.isProactiveEnabled());
        // 模型切换按钮（仅在桌宠启动后可用）
        Button btnSwitchModel = button(cSwitch, "切换模型（当前: " + getCurrentModelName() + "）");
        btnSwitchModel.setOnClickListener(v -> {
            switchModel();
            btnSwitchModel.setText("切换模型（当前: " + getCurrentModelName() + "）");
        });

        btnVoiceDiag = button(cSwitch, "检查语音引擎");
        btnVoiceDiag.setOnClickListener(v -> checkVoiceDiag());
        tvVoiceDiag = new TextView(this);
        tvVoiceDiag.setTextSize(12f);
        tvVoiceDiag.setLineSpacing(2f, 1f);
        tvVoiceDiag.setPadding(0, dp(6), 0, 0);
        tvVoiceDiag.setTextColor(Color.rgb(130, 125, 150));
        tvVoiceDiag.setText("桌宠运行中才能检测。如提示 TTS 失败，可点下方按钮到系统设置安装/启用语音合成数据。");
        cSwitch.addView(tvVoiceDiag, lp(0));
        Button btnTtsInstall = button(cSwitch, "打开系统 TTS 安装/设置");
        btnTtsInstall.setOnClickListener(v -> openTtsInstall());

        Button btnAccessibility = button(cSwitch, "无障碍感知权限（可选，增强互动）");
        btnAccessibility.setOnClickListener(v -> requestAccessibilityPermission());
        TextView tvAccessibility = new TextView(this);
        tvAccessibility.setTextSize(12f);
        tvAccessibility.setLineSpacing(2f, 1f);
        tvAccessibility.setPadding(0, dp(4), 0, 0);
        tvAccessibility.setTextColor(Color.rgb(130, 125, 150));
        tvAccessibility.setText("授权后小汐能感知你正在用什么 App、来了什么通知，从而主动搭话。不授权也不影响主功能。");
        cSwitch.addView(tvAccessibility, lp(0));

        // ---------- 4. 启停控制 ----------
        LinearLayout cCtrl = card(root, "启停控制");
        LinearLayout rowBtn = new LinearLayout(this);
        rowBtn.setOrientation(LinearLayout.HORIZONTAL);
        btnStart = new Button(this);
        btnStart.setText("启动桌宠");
        btnStart.setTextSize(14f);
        btnStart.setOnClickListener(v -> startPet());
        rowBtn.addView(btnStart, new LinearLayout.LayoutParams(0, dp(52), 1));
        btnStop = new Button(this);
        btnStop.setText("停止桌宠");
        btnStop.setTextSize(14f);
        btnStop.setOnClickListener(v -> stopPet());
        rowBtn.addView(btnStop, new LinearLayout.LayoutParams(0, dp(52), 1));
        cCtrl.addView(rowBtn, lp(0));

        // ---------- 5. 互动 ----------
        LinearLayout cChat = card(root, "互动");
        btnVoice = button(cChat, "按住说话（语音对话）");
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

        LinearLayout chatRow = new LinearLayout(this);
        chatRow.setOrientation(LinearLayout.HORIZONTAL);
        etChat = new EditText(this);
        etChat.setHint("输入一句话和小汐聊天…");
        etChat.setSingleLine(true);
        chatRow.addView(etChat, new LinearLayout.LayoutParams(0, dp(48), 1));
        btnChat = new Button(this);
        btnChat.setText("发送");
        btnChat.setOnClickListener(v -> sendChat());
        chatRow.addView(btnChat, new LinearLayout.LayoutParams(dp(80), dp(48)));
        cChat.addView(chatRow, lp(0));

        btnClear = button(cChat, "清空对话记忆");
        btnClear.setOnClickListener(v -> {
            AICore ai = getAiCore();
            if (ai != null) ai.getMemory().clearContext();
            Toast.makeText(this, "记忆已清空", Toast.LENGTH_SHORT).show();
        });

        LinearLayout cMemory = card(root, "记忆调试");
        btnMemoryDebug = button(cMemory, "查看记忆快照");
        btnMemoryDebug.setOnClickListener(v -> showMemoryDebug());
        btnMemorySelfCheck = button(cMemory, "运行记忆自检");
        btnMemorySelfCheck.setOnClickListener(v -> runMemorySelfCheck());
        tvMemoryDebug = new TextView(this);
        tvMemoryDebug.setTextSize(12f);
        tvMemoryDebug.setLineSpacing(2f, 1f);
        tvMemoryDebug.setPadding(0, dp(6), 0, 0);
        tvMemoryDebug.setTextColor(Color.rgb(130, 125, 150));
        tvMemoryDebug.setText("点击上方按钮查看当前记忆快照或执行本地自检。\n自检会写入一条测试 fact 和一条测试 summary。\n");
        cMemory.addView(tvMemoryDebug, lp(0));

        // ---------- 状态 ----------
        tvStatus = new TextView(this);
        tvStatus.setTextSize(12f);
        tvStatus.setTextColor(Color.rgb(90, 150, 100));
        tvStatus.setPadding(0, dp(12), 0, 0);
        root.addView(tvStatus, lp(0));

        tvCrashPath = new TextView(this);
        tvCrashPath.setTextSize(10f);
        tvCrashPath.setTextColor(Color.rgb(150, 140, 160));
        tvCrashPath.setPadding(0, dp(4), 0, dp(20));
        tvCrashPath.setText("崩溃日志：" + CrashHandler.getCrashPath());
        root.addView(tvCrashPath, lp(0));

        setContentView(scroll);
        updateStatus();
    }

    // ================= UI 构建辅助 =================
    private LinearLayout.LayoutParams lp(int extra) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (extra > 0) p.topMargin = dp(extra);
        return p;
    }

    /** 圆角卡片容器 */
    private LinearLayout card(LinearLayout root, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(16));
        bg.setColor(Color.WHITE);
        bg.setStroke(dp(1), Color.argb(60, 180, 170, 200));
        c.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(14);
        root.addView(c, clp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15f);
        t.setTextColor(Color.rgb(96, 74, 210));
        t.setPadding(0, 0, 0, dp(8));
        c.addView(t, lp(0));
        return c;
    }

    private EditText input(LinearLayout root, String hint, String value) {
        EditText et = new EditText(this);
        et.setHint(hint);
        if (value != null) et.setText(value);
        et.setTextSize(14f);
        et.setBackground(getEditBg());
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        root.addView(et, lp(8));
        return et;
    }

    private GradientDrawable getEditBg() {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.rgb(248, 247, 252));
        bg.setStroke(dp(1), Color.argb(60, 160, 140, 200));
        return bg;
    }

    private Button button(LinearLayout root, String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(14f);
        root.addView(b, lp(8));
        return b;
    }

    private Switch switchRow(LinearLayout root, String label, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14f);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        row.addView(sw);
        root.addView(row, lp(8));
        return sw;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    // ================= 逻辑 =================
    private void saveConfig() {
        settings.setApiBase(etBase.getText().toString().trim());
        settings.setApiKey(etKey.getText().toString().trim());
        settings.setModel(etModel.getText().toString().trim());
        settings.setPetName(etName.getText().toString().trim());
        settings.setVoiceEnabled(swVoice.isChecked());
        settings.setProactiveEnabled(swProactive.isChecked());
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void testConnection() {
        String base = etBase.getText().toString().trim();
        String key = etKey.getText().toString().trim();
        String model = etModel.getText().toString().trim();
        if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
            tvTestResult.setTextColor(Color.rgb(200, 70, 70));
            tvTestResult.setText("请先填写 Base URL / API Key / 模型名");
            return;
        }
        tvTestResult.setTextColor(Color.rgb(130, 125, 150));
        tvTestResult.setText("正在测试，请稍候…");
        LLMClient.testConnection(base, key, model, (text, err) -> handler.post(() -> {
            if (err == null) {
                tvTestResult.setTextColor(Color.rgb(60, 160, 80));
                tvTestResult.setText("✓ " + text);
            } else {
                tvTestResult.setTextColor(Color.rgb(200, 70, 70));
                String hint = friendlyApiError(err, base, model);
                tvTestResult.setText("✗ " + err + "\n" + hint);
            }
        }));
    }

    /** 将常见 HTTP 错误转换为可操作的排查提示 */
    private String friendlyApiError(String err, String base, String model) {
        String lower = err == null ? "" : err.toLowerCase();
        String hint;
        if (lower.contains("400")) {
            hint = "提示：HTTP 400 通常是「模型名不支持」或「请求参数不被服务端接受」。\n"
                    + "1) 确认模型名「" + model + "」在 " + base + " 上存在；"
                    + "下拉列表里是常用模型，可点选后重试。\n"
                    + "2) 若服务端是本地/自建网关，确认它实现的是 OpenAI 兼容 /chat/completions 接口。\n"
                    + "3) 部分服务需在请求体带 temperature 等参数，或改用流式(stream=true)再试。";
        } else if (lower.contains("401") || lower.contains("403")) {
            hint = "提示：HTTP 401/403 通常是 API Key 无效或无权限，请检查 Key 是否正确、余额是否充足。";
        } else if (lower.contains("404")) {
            hint = "提示：HTTP 404 通常是 Base URL 路径不对。确认地址以 /v1 结尾（如 https://api.deepseek.com/v1），且服务支持 /chat/completions。";
        } else if (lower.contains("timeout") || lower.contains("connect")) {
            hint = "提示：连接超时。确认 Base URL 可访问、网络畅通；若在国内环境访问海外服务，可能需要可达的镜像地址。";
        } else {
            hint = "提示：请检查 Base URL、API Key、模型名三项是否都正确，必要时在下方「清空对话记忆」后重试。";
        }
        return hint;
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

    private int currentModelIndex = 0;

    private String getCurrentModelName() {
        int count = Live2DNative.nativeGetModelCount();
        if (count <= 0) return "?";
        if (currentModelIndex >= count) currentModelIndex = 0;
        return Live2DNative.nativeGetModelDirName(currentModelIndex);
    }

    private void switchModel() {
        PetService svc = PetService.getInstance();
        if (svc == null) {
            Toast.makeText(this, "请先启动桌宠再切换模型", Toast.LENGTH_SHORT).show();
            return;
        }
        int count = Live2DNative.nativeGetModelCount();
        currentModelIndex = (currentModelIndex + 1) % count;
        final int idx = currentModelIndex;
        // 在 GL 线程执行切换，避免主线程/GL 线程纹理资源竞争
        com.maiden.pet.render.Live2DGLView glView = svc.getOverlayView().getLive2DView();
        if (glView != null) {
            glView.queueEvent(() -> Live2DNative.nativeChangeScene(idx));
        } else {
            Live2DNative.nativeChangeScene(idx);
        }
        Toast.makeText(this, "已切换至: " + getCurrentModelName(), Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "请先启动桌宠", Toast.LENGTH_SHORT).show();
            return;
        }
        svc.openTtsInstall();
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
            } catch (Exception e) {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
            }
            Toast.makeText(this, "请打开开关授权悬浮窗", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "已具备悬浮窗权限", Toast.LENGTH_SHORT).show();
        }
    }

    /** 引导用户到系统设置里开启无障碍服务 */
    private void requestAccessibilityPermission() {
        boolean enabled = isAccessibilityServiceEnabled();
        if (!enabled) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception e) {
                Toast.makeText(this, "无法打开系统无障碍设置，请到 设置→无障碍 手动开启", Toast.LENGTH_LONG).show();
                return;
            }
            Toast.makeText(this, "请找到本应用并开启「无障碍感知」服务", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "无障碍感知已开启", Toast.LENGTH_SHORT).show();
        }
    }

    /** 检测本应用的无障碍服务是否已启用 */
    private boolean isAccessibilityServiceEnabled() {
        String expected = getPackageName() + "/" + "com.maiden.pet.accessibility.PetAccessibilityService";
        String enabledServices = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabledServices == null) return false;
        return enabledServices.contains(expected);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            }
        }
    }

    private void requestRecordPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            }
        }
    }

    private void startPet() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        saveConfig();
        requestNotificationPermission();
        requestRecordPermission();
        Intent i = new Intent(this, PetService.class);
        i.setAction(PetService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        Toast.makeText(this, "小汐正在醒来…", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::updateStatus, 1500);
    }

    private void stopPet() {
        Intent i = new Intent(this, PetService.class);
        i.setAction(PetService.ACTION_STOP);
        startService(i);
        Toast.makeText(this, "小汐已休息", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "请先配置 API 并保存", Toast.LENGTH_LONG).show();
            return;
        }
        etChat.setText("");
        AICore ai = getAiCore();
        if (ai != null) {
            ai.onUserSays(text);
            Toast.makeText(this, "已发送给小汐", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "请先启动桌宠", Toast.LENGTH_SHORT).show();
        }
    }

    private void startListening() {
        requestRecordPermission();
        PetService svc = PetService.getInstance();
        if (svc == null) {
            Toast.makeText(this, "请先启动桌宠", Toast.LENGTH_SHORT).show();
            return;
        }
        svc.toggleListening();
    }

    private void stopListening() {
        PetService svc = PetService.getInstance();
        if (svc != null) {
            svc.toggleListening();
        }
    }

    private void updateStatus() {
        if (tvStatus == null) return;
        boolean running = PetService.getInstance() != null;
        boolean overlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this);
        boolean configured = settings.isConfigured();
        StringBuilder sb = new StringBuilder();
        sb.append("状态：").append(running ? "● 桌宠运行中" : "○ 未启动\n");
        sb.append("悬浮窗权限：").append(overlay ? "✓ 已授权" : "✗ 未授权\n");
        sb.append("无障碍感知：").append(isAccessibilityServiceEnabled() ? "✓ 已开启" : "○ 未开启\n");
        sb.append("API 配置：").append(configured ? "✓ 已配置" : "✗ 未配置\n");
        sb.append("Base URL：").append(settings.getApiBase().isEmpty() ? "（空）" : settings.getApiBase()).append("\n");
        sb.append("模型：").append(settings.getModel().isEmpty() ? "（空）" : settings.getModel());
        tvStatus.setText(sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
