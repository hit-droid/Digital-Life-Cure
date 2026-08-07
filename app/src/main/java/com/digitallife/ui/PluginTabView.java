package com.digitallife.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.R;
import com.digitallife.mcp.McpServerManager;
import com.digitallife.plugin.PluginManager;
import com.digitallife.tools.ToolRegistry;

import java.util.List;

/**
 * 微信式「插件」Tab：外部插件工具 + MCP 工具服务器。
 * 支持 zip 插件安装/卸载、MCP 服务器添加/删除。
 */
public class PluginTabView extends LinearLayout {

    public static final int REQ_INSTALL_PLUGIN = 1002;

    private final Activity activity;
    private final PluginManager pluginManager;
    private final McpServerManager mcpManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LinearLayout container;

    public PluginTabView(Activity activity) {
        super(activity);
        this.activity = activity;
        this.pluginManager = new PluginManager(activity);
        this.mcpManager = new McpServerManager(activity);
        setOrientation(LinearLayout.VERTICAL);
        setBackgroundColor(UiKit.color(activity, R.color.page_bg));
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 8),
                UiKit.dp(activity, 12), UiKit.dp(activity, 16));
        scroll.addView(container, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void refresh() {
        if (container == null) return;
        container.removeAllViews();
        renderPlugins();
        renderMcp();
    }

    // ==================== 插件 ====================

    private void renderPlugins() {
        LinearLayout card = UiKit.card(activity, container, "插件（外部工具包）");
        TextView hint = new TextView(activity);
        hint.setText("安装声明式插件 zip 包，自动注册工具到 AI 可用工具列表。插件包内需包含 plugin.json 描述工具集。");
        hint.setTextSize(13f);
        hint.setTextColor(UiKit.color(activity, R.color.text_secondary));
        hint.setLineSpacing(3f, 1f);
        card.addView(hint, UiKit.lp(activity, 0));

        Button btnInstall = UiKit.button(activity, card, "选择插件 zip 安装");
        btnInstall.setOnClickListener(v -> pickPluginZip());

        List<PluginManager.InstalledPlugin> list = pluginManager.listInstalled();
        if (list.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("尚未安装插件。");
            empty.setTextSize(13f);
            empty.setTextColor(UiKit.color(activity, R.color.text_secondary));
            card.addView(empty, UiKit.lp(activity, 6));
        } else {
            for (PluginManager.InstalledPlugin p : list) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = new TextView(activity);
                name.setText(p.name + " v" + p.version + "（" + p.toolCount + " 工具）");
                name.setTextSize(13f);
                name.setTextColor(UiKit.color(activity, R.color.text_primary));
                row.addView(name, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button btnUninstall = new Button(activity);
                btnUninstall.setText("卸载");
                btnUninstall.setTextSize(11f);
                btnUninstall.setAllCaps(false);
                btnUninstall.setTextColor(UiKit.color(activity, R.color.brand));
                btnUninstall.setBackgroundResource(R.drawable.bg_btn_secondary);
                btnUninstall.setPadding(UiKit.dp(activity, 10), 0, UiKit.dp(activity, 10), 0);
                LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(activity, 30));
                btnUninstall.setOnClickListener(v -> {
                    pluginManager.uninstall(p.name);
                    Toast.makeText(activity, "已卸载：" + p.name, Toast.LENGTH_SHORT).show();
                    refresh();
                });
                row.addView(btnUninstall, ulp);
                card.addView(row, UiKit.lp(activity, 4));
            }
        }
    }

    private void pickPluginZip() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed"});
            activity.startActivityForResult(i, REQ_INSTALL_PLUGIN);
        } catch (Exception e) {
            Toast.makeText(activity, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    /** 插件 zip 安装结果回调（由 MainActivity.onActivityResult 转发） */
    public void handlePluginResult(Uri uri) {
        if (uri == null) return;
        Toast.makeText(activity, "正在安装插件…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                PluginManager.InstallResult r =
                        pluginManager.installFromStream(activity.getContentResolver().openInputStream(uri));
                handler.post(() -> {
                    Toast.makeText(activity,
                            r.ok ? r.message : "安装失败: " + r.message, Toast.LENGTH_LONG).show();
                    refresh();
                });
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(activity,
                        "安装异常: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    // ==================== MCP ====================

    private void renderMcp() {
        LinearLayout card = UiKit.card(activity, container, "MCP 工具服务器（Smithery 等）");
        TextView hint = new TextView(activity);
        hint.setText("连接外部 MCP 工具服务器（如你在 Smithery 注册的工具）。"
                + "连接后其工具自动加入 AI 可用工具列表。\n"
                + "Smithery 端点格式：https://server.smithery.ai/<namespace>/mcp");
        hint.setTextSize(13f);
        hint.setTextColor(UiKit.color(activity, R.color.text_secondary));
        hint.setLineSpacing(3f, 1f);
        card.addView(hint, UiKit.lp(activity, 0));

        EditText etName = UiKit.input(activity, card, "服务器名称（如：我的天气工具）", "");
        EditText etEndpoint = UiKit.input(activity, card, "端点 URL（含 /mcp）", "");
        EditText etHeaderName = UiKit.input(activity, card, "请求头名称（如 Authorization）", "");
        EditText etHeaderValue = UiKit.input(activity, card, "请求头值（如 Bearer sk-xxx）", "");
        etHeaderValue.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText etNs = UiKit.input(activity, card, "命名空间前缀（如 weather，用于工具名分组）", "");

        Button btnAdd = UiKit.button(activity, card, "添加并连接服务器");
        btnAdd.setOnClickListener(v -> {
            String name = etName.getText().toString().trim();
            String endpoint = etEndpoint.getText().toString().trim();
            String headerName = etHeaderName.getText().toString().trim();
            String headerValue = etHeaderValue.getText().toString().trim();
            String ns = etNs.getText().toString().trim();
            if (name.isEmpty() || endpoint.isEmpty()) {
                Toast.makeText(activity, "请填写服务器名称和端点 URL", Toast.LENGTH_SHORT).show();
                return;
            }
            McpServerManager.McpServerConfig cfg = new McpServerManager.McpServerConfig(
                    mcpManager.newId(), name, endpoint, headerName, headerValue, ns);
            mcpManager.save(cfg);
            Toast.makeText(activity, "配置已保存，桌宠下次启动时自动连接", Toast.LENGTH_SHORT).show();
            etName.setText("");
            etEndpoint.setText("");
            etHeaderName.setText("");
            etHeaderValue.setText("");
            etNs.setText("");
            refresh();
        });

        List<McpServerManager.McpServerConfig> servers = mcpManager.list();
        if (servers.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("尚未添加 MCP 服务器。");
            empty.setTextSize(13f);
            empty.setTextColor(UiKit.color(activity, R.color.text_secondary));
            card.addView(empty, UiKit.lp(activity, 6));
        } else {
            for (McpServerManager.McpServerConfig c : servers) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = new TextView(activity);
                name.setText(c.name + "\n   " + c.endpoint);
                name.setTextSize(13f);
                name.setTextColor(UiKit.color(activity, R.color.text_primary));
                name.setLineSpacing(2f, 1f);
                row.addView(name, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                Button btnDel = new Button(activity);
                btnDel.setText("删除");
                btnDel.setTextSize(11f);
                btnDel.setAllCaps(false);
                btnDel.setTextColor(UiKit.color(activity, R.color.brand));
                btnDel.setBackgroundResource(R.drawable.bg_btn_secondary);
                btnDel.setPadding(UiKit.dp(activity, 10), 0, UiKit.dp(activity, 10), 0);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(activity, 30));
                btnDel.setOnClickListener(v -> {
                    mcpManager.remove(c.id);
                    Toast.makeText(activity, "已删除：" + c.name, Toast.LENGTH_SHORT).show();
                    refresh();
                });
                row.addView(btnDel, dlp);
                card.addView(row, UiKit.lp(activity, 4));
            }
        }
        int toolCount = ToolRegistry.getInstance().all().size();
        TextView foot = new TextView(activity);
        foot.setText("当前远程工具：" + toolCount + " 个");
        foot.setTextSize(11f);
        foot.setTextColor(UiKit.color(activity, R.color.text_secondary));
        card.addView(foot, UiKit.lp(activity, 6));
    }
}
