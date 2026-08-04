package com.digitallife.ui;

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
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.util.ApiManager;
import com.digitallife.util.ApiProfile;
import com.digitallife.util.CrashHandler;
import com.digitallife.service.PetService;
import com.digitallife.brain.AICore;
import com.digitallife.brain.LLMClient;
import com.digitallife.model.ModelManager;
import com.digitallife.util.Settings;
import com.digitallife.render.Live2DNative;
import com.digitallife.render.Live2DGLView;

import java.util.ArrayList;
import java.util.List;

/**
 * 桌宠控制面板：悬浮窗授权引导 + API 配置 + 连接测试 + 语音引擎诊断 + 模型导入 + 启停控制。
 */
public class MainActivity extends Activity {

    private static final String TAG = "AI_PET";
    private static final int REQ_IMPORT_MODEL = 1001;
    private static final int REQ_INSTALL_PLUGIN = 1002;
    private Settings settings;
    private ApiManager apiManager;
    private com.digitallife.mcp.McpServerManager mcpManager;
    private com.digitallife.plugin.PluginManager pluginManager;
    private ProfileSection chatSection;   // 对话大脑
    private ProfileSection careSection;   // 护理大脑
    private Switch swVoice, swProactive;
    private Button btnOverlay, btnVoice, btnChat, btnStart, btnStop, btnClear, btnVoiceDiag, btnMemoryDebug, btnMemorySelfCheck, btnImportModel;
    private EditText etChat, etPetName;
    private TextView tvStatus, tvCrashPath, tvVoiceDiag, tvMemoryDebug, tvModelList, tvModelStatus, tvMcpList, tvPluginList;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        CrashHandler.init(this);
        // 初始化资产/内部模型目录，并恢复已导入的模型注册（供模型列表展示）
        Live2DNative.init(this);
        ModelManager.registerImportedModels(this);

        settings = new Settings(this);
        apiManager = new ApiManager(this);
        mcpManager = new com.digitallife.mcp.McpServerManager(this);
        pluginManager = new com.digitallife.plugin.PluginManager(this);
        // 迁移：旧版单配置尚未存入 Profile 时，以默认名导入
        if (apiManager.list(ApiManager.SCOPE_CHAT).isEmpty()
                && settings.isConfigured()) {
            ApiProfile legacy = new ApiProfile(apiManager.newId(), "默认配置",
                    settings.getApiBase(), settings.getApiKey(), settings.getModel());
            apiManager.save(ApiManager.SCOPE_CHAT, legacy);
        }
        // 把当前生效配置同步回 Settings，供桌宠启动时读取
        apiManager.syncCurrentToSettings(ApiManager.SCOPE_CHAT, settings);
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
        title.setText("数字生命");
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

        // ---------- 2. 对话大脑配置（多 Profile） ----------
        chatSection = new ProfileSection(root, "对话大脑配置", ApiManager.SCOPE_CHAT, "AI 大脑");
        chatSection.tvResult.setHint("填写后点「测试 API 连接」，这里会显示详细结果。");

        // ---------- 2.1 护理大脑配置（多 Profile） ----------
        careSection = new ProfileSection(root, "护理大脑配置", ApiManager.SCOPE_CARE, "护理大脑");
        careSection.tvResult.setHint("护理大脑负责模型校验修复/动作创作，可与对话大脑使用不同 API。");

        // ---------- 2.2 MCP 工具服务器 ----------
        buildMcpSection(root);

        // ---------- 2.3 插件安装器 ----------
        buildPluginSection(root);

        // ---------- 3. 功能开关 ----------
        LinearLayout cSwitch = card(root, "功能设置");
        etPetName = input(cSwitch, "角色名字（默认 小汐）", settings.getPetName());
        swVoice = switchRow(cSwitch, "语音互动（说话+发声）", settings.isVoiceEnabled());
        swProactive = switchRow(cSwitch, "自主行为（会主动找你说话）", settings.isProactiveEnabled());
        // 模型切换按钮（仅在桌宠启动后可用）
        Button btnSwitchModel = button(cSwitch, "切换模型（当前: " + getCurrentModelName() + "）");
        btnSwitchModel.setOnClickListener(v -> {
            switchModel();
            btnSwitchModel.setText("切换模型（当前: " + getCurrentModelName() + "）");
        });

        // ---------- 模型管理（导入模型） ----------
        btnImportModel = button(cSwitch, "导入模型（zip）");
        btnImportModel.setOnClickListener(v -> pickModelZip());
        tvModelStatus = new TextView(this);
        tvModelStatus.setTextSize(12f);
        tvModelStatus.setLineSpacing(2f, 1f);
        tvModelStatus.setPadding(0, dp(4), 0, 0);
        tvModelStatus.setTextColor(Color.rgb(130, 125, 150));
        tvModelStatus.setText("导入 Live2D 模型 zip 包，解压后立即可切换使用。");
        cSwitch.addView(tvModelStatus, lp(0));
        tvModelList = new TextView(this);
        tvModelList.setTextSize(11f);
        tvModelList.setLineSpacing(2f, 1f);
        tvModelList.setPadding(0, dp(2), 0, 0);
        tvModelList.setTextColor(Color.rgb(150, 140, 160));
        cSwitch.addView(tvModelList, lp(0));
        refreshModelList();

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
        tvAccessibility.setText("授权后她也能感知你正在用什么 App、来了什么通知，从而主动搭话。不授权也不影响主功能。");
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
        etChat.setHint("输入一句话和她聊天…");
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
    private void buildMcpSection(LinearLayout root) {
        LinearLayout cMcp = card(root, "MCP 工具服务器（Smithery 等）");
        TextView tvHint = new TextView(this);
        tvHint.setText("连接外部 MCP 工具服务器（如你在 Smithery 注册的工具）。"
                + "连接后其工具自动加入 AI 可用工具列表。\n"
                + "Smithery 端点格式：https://server.smithery.ai/<namespace>/mcp");
        tvHint.setTextSize(12f);
        tvHint.setLineSpacing(2f, 1f);
        tvHint.setTextColor(Color.rgb(130, 125, 150));
        cMcp.addView(tvHint, lp(0));

        EditText etName = input(cMcp, "服务器名称（如：我的天气工具）", "");
        EditText etEndpoint = input(cMcp, "端点 URL（含 /mcp）", "");
        EditText etHeaderName = input(cMcp, "请求头名称（如 Authorization）", "");
        EditText etHeaderValue = input(cMcp, "请求头值（如 Bearer sk-xxx）", "");
        etHeaderValue.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText etNs = input(cMcp, "命名空间前缀（如 weather，用于工具名分组）", "");

        Button btnAdd = button(cMcp, "添加并连接服务器");
        btnAdd.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String endpoint = etEndpoint.getText().toString().trim();
            String headerName = etHeaderName.getText().toString().trim();
            String headerValue = etHeaderValue.getText().toString().trim();
            String ns = etNs.getText().toString().trim();
            if (name.isEmpty() || endpoint.isEmpty()) {
                Toast.makeText(this, "请填写服务器名称和端点 URL", Toast.LENGTH_SHORT).show();
                return;
            }
            com.digitallife.mcp.McpServerManager.McpServerConfig cfg =
                    new com.digitallife.mcp.McpServerManager.McpServerConfig(
                            mcpManager.newId(), name, endpoint, headerName, headerValue, ns);
            mcpManager.save(cfg);
            Toast.makeText(this, "配置已保存，桌宠下次启动时自动连接", Toast.LENGTH_SHORT).show();
            etName.setText("");
            etEndpoint.setText("");
            etHeaderName.setText("");
            etHeaderValue.setText("");
            etNs.setText("");
        });

        tvMcpList = new TextView(this);
        tvMcpList.setTextSize(12f);
        tvMcpList.setLineSpacing(2f, 1f);
        tvMcpList.setPadding(0, dp(6), 0, 0);
        tvMcpList.setTextColor(Color.rgb(130, 125, 150));
        cMcp.addView(tvMcpList, lp(0));
        refreshMcpList();
    }

    private void refreshMcpList() {
        if (tvMcpList == null) return;
        List<com.digitallife.mcp.McpServerManager.McpServerConfig> list = mcpManager.list();
        if (list.isEmpty()) {
            tvMcpList.setText("尚未添加 MCP 服务器。");
            return;
        }
        StringBuilder sb = new StringBuilder("已保存：\n");
        for (com.digitallife.mcp.McpServerManager.McpServerConfig c : list) {
            sb.append("- ").append(c.name)
              .append("  [").append(c.endpoint).append("]\n");
        }
        // 显示当前已注册的远程工具数量
        int toolCount = com.digitallife.tools.ToolRegistry.getInstance().all().size();
        sb.append("当前远程工具：").append(toolCount).append(" 个");
        tvMcpList.setText(sb.toString());
    }

    private void buildPluginSection(LinearLayout root) {
        LinearLayout cPlugin = card(root, "插件安装器（工具包）");
        TextView tvHint = new TextView(this);
        tvHint.setText("安装声明式插件 zip 包，自动注册工具到 AI 可用工具列表。"
                + "插件包内需包含 plugin.json 描述工具集。");
        tvHint.setTextSize(12f);
        tvHint.setLineSpacing(2f, 1f);
        tvHint.setTextColor(Color.rgb(130, 125, 150));
        cPlugin.addView(tvHint, lp(0));

        Button btnInstall = button(cPlugin, "选择插件 zip 安装");
        btnInstall.setOnClickListener(v -> pickPluginZip());

        tvPluginList = new TextView(this);
        tvPluginList.setTextSize(12f);
        tvPluginList.setLineSpacing(2f, 1f);
        tvPluginList.setPadding(0, dp(6), 0, 0);
        tvPluginList.setTextColor(Color.rgb(130, 125, 150));
        cPlugin.addView(tvPluginList, lp(0));
        refreshPluginList();
    }

    private void refreshPluginList() {
        if (tvPluginList == null) return;
        List<com.digitallife.plugin.PluginManager.InstalledPlugin> list = pluginManager.listInstalled();
        if (list.isEmpty()) {
            tvPluginList.setText("尚未安装插件。");
            return;
        }
        StringBuilder sb = new StringBuilder("已安装：\n");
        for (com.digitallife.plugin.PluginManager.InstalledPlugin p : list) {
            sb.append("✦ ").append(p.name)
              .append(" v").append(p.version)
              .append(" (").append(p.toolCount).append(" 工具)\n");
        }
        tvPluginList.setText(sb.toString());
    }

    private void pickPluginZip() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed"});
            startActivityForResult(i, REQ_INSTALL_PLUGIN);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveConfig() {
        // 角色名与功能开关为全局设置，独立于大脑 Profile
        settings.setPetName(etPetName != null ? etPetName.getText().toString().trim() : settings.getPetName());
        settings.setVoiceEnabled(swVoice.isChecked());
        settings.setProactiveEnabled(swProactive.isChecked());
        chatSection.saveProfile();
        careSection.saveProfile();
        // 桌宠运行中则热切换大脑配置
        PetService svc = PetService.getInstance();
        if (svc != null) {
            apiManager.syncCurrentToSettings(ApiManager.SCOPE_CHAT, settings);
            svc.reconfigureBrain();
            Toast.makeText(this, "配置已保存，大脑已热切换", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        }
    }

    private void testConnection() {
        chatSection.testConnection();
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
        if (count <= 0) return;
        currentModelIndex = (currentModelIndex + 1) % count;
        final int idx = currentModelIndex;
        // 在 GL 线程执行切换，避免主线程/GL 线程纹理资源竞争
        Live2DGLView glView = svc.getOverlayView().getLive2DView();
        if (glView != null) {
            glView.queueEvent(() -> Live2DNative.nativeChangeScene(idx));
        } else {
            Live2DNative.nativeChangeScene(idx);
        }
        Toast.makeText(this, "已切换至: " + getCurrentModelName(), Toast.LENGTH_SHORT).show();
    }

    // ================= 模型导入 =================

    /** 打开系统文件选择器挑选模型 zip */
    private void pickModelZip() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed"});
            startActivityForResult(i, REQ_IMPORT_MODEL);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_MODEL && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) return;
            tvModelStatus.setTextColor(Color.rgb(90, 150, 100));
            tvModelStatus.setText("正在导入模型，请稍候…");
            new Thread(() -> {
                final ModelManager.ImportResult result = ModelManager.importFromUri(this, uri);
                handler.post(() -> {
                    if (result.ok) {
                        tvModelStatus.setTextColor(Color.rgb(60, 160, 80));
                        tvModelStatus.setText(result.message + "\n已加入可用模型列表，可点击「切换模型」查看。");
                        // 若桌宠已启动，立即切换到新导入的模型
                        PetService svc = PetService.getInstance();
                        if (svc != null) {
                            int last = Live2DNative.nativeGetModelCount() - 1;
                            currentModelIndex = last;
                            Live2DGLView glView = svc.getOverlayView().getLive2DView();
                            if (glView != null) {
                                glView.queueEvent(() -> Live2DNative.nativeChangeScene(last));
                            }
                            Toast.makeText(this, "已切换至新模型: " + result.modelDir, Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        tvModelStatus.setTextColor(Color.rgb(200, 70, 70));
                        tvModelStatus.setText(result.message);
                    }
refreshModelList();
                });
            }).start();
        } else if (requestCode == REQ_INSTALL_PLUGIN && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) return;
            Toast.makeText(this, "正在安装插件…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    com.digitallife.plugin.PluginManager.InstallResult r =
                            pluginManager.installFromStream(getContentResolver().openInputStream(uri));
                    handler.post(() -> {
                        if (r.ok) {
                            Toast.makeText(this, r.message, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, "安装失败: " + r.message, Toast.LENGTH_LONG).show();
                        }
                        refreshPluginList();
                    });
                } catch (Exception e) {
                    handler.post(() -> {
                        Toast.makeText(this, "安装异常: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        }
    }

    /** 刷新模型列表显示（内置 + 已导入） */
    private void refreshModelList() {
        if (tvModelList == null) return;
        StringBuilder sb = new StringBuilder();
        int count = Live2DNative.nativeGetModelCount();
        for (int i = 0; i < count; i++) {
            String name = Live2DNative.nativeGetModelDirName(i);
            if (name == null || name.isEmpty()) continue;
            sb.append(i == currentModelIndex ? "● " : "○ ").append(name);
            if (ModelManager.listImportedModelDirs(this).contains(name)) {
                sb.append("（已导入）");
            } else if (i < 2) {
                sb.append("（内置）");
            }
            sb.append("\n");
        }
        if (sb.length() == 0) sb.append("（无可用模型）");
        tvModelList.setText(sb.toString());
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
        String expected = getPackageName() + "/" + "com.digitallife.service.PetAccessibilityService";
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
        Toast.makeText(this, "数字生命正在醒来…", Toast.LENGTH_SHORT).show();
        handler.postDelayed(this::updateStatus, 1500);
    }

    private void stopPet() {
        Intent i = new Intent(this, PetService.class);
        i.setAction(PetService.ACTION_STOP);
        startService(i);
        Toast.makeText(this, "数字生命已休息", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "已发送", Toast.LENGTH_SHORT).show();
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
        ApiProfile cur = apiManager.getCurrent(ApiManager.SCOPE_CHAT);
        if (cur != null) {
            sb.append("对话大脑：").append(cur.name.isEmpty() ? "（未命名）" : cur.name).append("\n");
            sb.append("模型：").append(cur.model.isEmpty() ? "（空）" : cur.model);
        } else {
            sb.append("模型：").append(settings.getModel().isEmpty() ? "（空）" : settings.getModel());
        }
        tvStatus.setText(sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        refreshMcpList();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /**
     * 一个大脑的 API Profile 编辑区。
     * 顶部下拉选择已有配置，下方表单编辑；支持保存/新建/删除/测试。
     */
    private class ProfileSection {
        final String scope;
        final LinearLayout card;
        final Spinner spinner;
        final EditText etName, etBase, etKey, etModel;
        final TextView tvResult;
        final List<ApiProfile> profiles = new ArrayList<>();
        boolean fromUser = false;

        ProfileSection(LinearLayout root, String title, String scope, String roleLabel) {
            this.scope = scope;
            card = card(root, title);
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            spinner = new Spinner(MainActivity.this);
            row.addView(spinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button btnNew = new Button(MainActivity.this);
            btnNew.setText("新建");
            btnNew.setTextSize(13f);
            btnNew.setOnClickListener(v -> newProfile());
            row.addView(btnNew, lp(4));
            Button btnDel = new Button(MainActivity.this);
            btnDel.setText("删除");
            btnDel.setTextSize(13f);
            btnDel.setOnClickListener(v -> deleteProfile());
            row.addView(btnDel, lp(4));
            card.addView(row, lp(0));
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
            etName = input(card, roleLabel + " 配置名称（如：主用 DeepSeek）", "");
            etBase = input(card, "API Base URL（如 https://api.deepseek.com/v1）", "");
            etKey = input(card, "API Key", "");
            etKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            etModel = input(card, "模型名（如 deepseek-chat）", "");
            Button btnSave = button(card, "保存此配置");
            btnSave.setOnClickListener(v -> saveProfile());
            Button btnTest = button(card, "测试此 API 连接");
            btnTest.setOnClickListener(v -> testConnection());
            tvResult = new TextView(MainActivity.this);
            tvResult.setTextSize(12f);
            tvResult.setLineSpacing(2f, 1f);
            tvResult.setPadding(0, dp(6), 0, 0);
            tvResult.setTextColor(Color.rgb(130, 125, 150));
            card.addView(tvResult, lp(0));
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
            spinner.setAdapter(new android.widget.ArrayAdapter<>(MainActivity.this,
                    android.R.layout.simple_spinner_dropdown_item, names));
            // 定位到当前生效配置
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
                tvResult.setTextColor(Color.rgb(200, 70, 70));
                tvResult.setText("没有可删除的配置");
                return;
            }
            apiManager.remove(scope, curId);
            if (ApiManager.SCOPE_CHAT.equals(scope)) {
                apiManager.syncCurrentToSettings(scope, settings);
            }
            refreshSpinner();
            tvResult.setTextColor(Color.rgb(90, 150, 100));
            tvResult.setText("已删除配置");
        }

        void saveProfile() {
            String name = etName.getText().toString().trim();
            String base = etBase.getText().toString().trim();
            String key = etKey.getText().toString().trim();
            String model = etModel.getText().toString().trim();
            if (name.isEmpty()) {
                tvResult.setTextColor(Color.rgb(200, 70, 70));
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
            tvResult.setTextColor(Color.rgb(60, 160, 80));
            tvResult.setText("已保存：" + name);
        }

        void testConnection() {
            String base = etBase.getText().toString().trim();
            String key = etKey.getText().toString().trim();
            String model = etModel.getText().toString().trim();
            if (base.isEmpty() || key.isEmpty() || model.isEmpty()) {
                tvResult.setTextColor(Color.rgb(200, 70, 70));
                tvResult.setText("请先填写 Base URL / API Key / 模型名");
                return;
            }
            tvResult.setTextColor(Color.rgb(130, 125, 150));
            tvResult.setText("正在测试，请稍候…");
            LLMClient.testConnection(base, key, model, (text, err) -> handler.post(() -> {
                if (err == null) {
                    tvResult.setTextColor(Color.rgb(60, 160, 80));
                    tvResult.setText("✓ " + text);
                } else {
                    tvResult.setTextColor(Color.rgb(200, 70, 70));
                    tvResult.setText("✗ " + err + "\n" + friendlyApiError(err, base, model));
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
}
