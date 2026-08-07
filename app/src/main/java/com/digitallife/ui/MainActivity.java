package com.digitallife.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.R;
import com.digitallife.model.ModelManager;
import com.digitallife.render.Live2DNative;
import com.digitallife.util.ApiManager;
import com.digitallife.util.ApiProfile;
import com.digitallife.util.ChatStore;
import com.digitallife.util.CrashHandler;
import com.digitallife.util.Settings;

/**
 * 微信式主界面：底部 5 Tab 导航（对话 / 通讯录 / 发现 / 插件 / 设置）。
 */
public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView tvTitle;
    private FrameLayout content;
    private LinearLayout navBar;
    private LinearLayout[] navItems = new LinearLayout[5];

    private ConversationTabView conversationTab;
    private ContactsTabView contactsTab;
    private DiscoverTabView discoverTab;
    private PluginTabView pluginTab;
    private SettingsTabView settingsTab;

    private final String[] TAB_TITLES = {"对话", "通讯录", "发现", "插件", "设置"};
    private int currentTab = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashHandler.init(this);
        Live2DNative.init(this);
        ModelManager.registerImportedModels(this);

        // 迁移：旧版单配置尚未存入 Profile 时，以默认名导入
        Settings settings = new Settings(this);
        ApiManager apiManager = new ApiManager(this);
        if (apiManager.list(ApiManager.SCOPE_CHAT).isEmpty()
                && settings.isConfigured()) {
            ApiProfile legacy = new ApiProfile(apiManager.newId(), "默认配置",
                    settings.getApiBase(), settings.getApiKey(), settings.getModel());
            apiManager.save(ApiManager.SCOPE_CHAT, legacy);
        }
        apiManager.syncCurrentToSettings(ApiManager.SCOPE_CHAT, settings);

        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.page_bg));

        // ===== 顶部标题栏 =====
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_top_bar);
        topBar.setPadding(dp(16), dp(12), dp(16), dp(12));

        tvTitle = new TextView(this);
        tvTitle.setText(TAB_TITLES[0]);
        tvTitle.setTextSize(18f);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setGravity(Gravity.START);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ===== 内容区 =====
        content = new FrameLayout(this);
        content.setBackgroundColor(getColorCompat(R.color.page_bg));

        conversationTab = new ConversationTabView(this, new ConversationTabView.Listener() {
            @Override
            public void onOpenSession(String sessionKey, String title, String type, String modelName) {
                openChat(sessionKey, title, type, modelName);
            }
        });
        contactsTab = new ContactsTabView(this, modelName -> {
            // 每个模型一个独立会话（session_key=model_<name>）
            ChatStore cs = new ChatStore(MainActivity.this);
            String key = "model_" + modelName;
            cs.ensureSession(key, modelName, ChatStore.TYPE_MODEL, "model", modelName);
            openChat(key, modelName, ChatStore.TYPE_MODEL, modelName);
        });
        discoverTab = new DiscoverTabView(this);
        pluginTab = new PluginTabView(this);
        settingsTab = new SettingsTabView(this);

        content.addView(conversationTab, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(contactsTab, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(discoverTab, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(pluginTab, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(settingsTab, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== 底部导航 =====
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(Color.WHITE);
        navBar.setPadding(dp(4), dp(4), dp(4), dp(4));
        String[] icons = {"对话", "通讯录", "发现", "插件", "设置"};
        for (int i = 0; i < 5; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, dp(4), 0, dp(4));

            TextView icon = new TextView(this);
            icon.setText(icons[i]);
            icon.setTextSize(13f);
            icon.setTypeface(Typeface.DEFAULT_BOLD);
            icon.setGravity(Gravity.CENTER);

            TextView label = new TextView(this);
            label.setText(icons[i]);
            label.setTextSize(9f);
            label.setGravity(Gravity.CENTER);

            item.addView(icon);
            item.addView(label);
            item.setOnClickListener(v -> switchTab(index));
            navItems[i] = item;
            navBar.addView(item, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        root.addView(navBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        setContentView(root);
        switchTab(0);
    }

    private void switchTab(int index) {
        currentTab = index;
        tvTitle.setText(TAB_TITLES[index]);
        for (int i = 0; i < 5; i++) {
            int color = i == index ? getColorCompat(R.color.brand) : Color.rgb(150, 150, 160);
            for (int j = 0; j < navItems[i].getChildCount(); j++) {
                ((TextView) navItems[i].getChildAt(j)).setTextColor(color);
            }
        }
        content.setVisibility(View.VISIBLE);
        conversationTab.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        contactsTab.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        discoverTab.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        pluginTab.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        settingsTab.setVisibility(index == 4 ? View.VISIBLE : View.GONE);
        // 切换到该 Tab 时刷新
        switch (index) {
            case 0: conversationTab.refresh(); break;
            case 1: contactsTab.refresh(); break;
            case 2: discoverTab.refresh(); break;
            case 3: pluginTab.refresh(); break;
            case 4: settingsTab.onResume(); break;
        }
    }

    private void openChat(String sessionKey, String title, String type, String modelName) {
        try {
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra("session_key", sessionKey);
            i.putExtra("title", title);
            i.putExtra("type", type);
            i.putExtra("model_name", modelName);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开对话: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PluginTabView.REQ_INSTALL_PLUGIN && resultCode == RESULT_OK && data != null) {
            pluginTab.handlePluginResult(data.getData());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentTab == 0) conversationTab.refresh();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int getColorCompat(int res) {
        return getResources().getColor(res);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
