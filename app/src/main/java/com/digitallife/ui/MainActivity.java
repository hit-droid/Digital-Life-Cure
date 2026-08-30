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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.R;
import com.digitallife.model.ModelManager;
import com.digitallife.render.Live2DNative;
import com.digitallife.ui.shell.OperitContentView;
import com.digitallife.ui.shell.OperitDrawer;
import com.digitallife.ui.shell.OperitNavController;
import com.digitallife.ui.shell.OperitRoute;
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
    private TextView tvSubtitle;
    private FrameLayout content;
    private LinearLayout navBar;
    private LinearLayout[] navItems = new LinearLayout[5];

    // v1.23.0 Operit 侧栏
    private OperitDrawer operitDrawer;
    private View drawerScrim;
    private OperitNavController navController;

    private ConversationTabView conversationTab;
    private ContactsTabView contactsTab;
    private DiscoverTabView discoverTab;
    private PluginTabView pluginTab;
    private SettingsTabView settingsTab;

    private final String[] TAB_TITLES = {"对话", "通讯录", "发现", "插件", "设置"};
    private final String[] TAB_SUBTITLES = {
            "和她说说话，聊聊今天",
            "每个模型都有自己的小房间",
            "她的内心世界与记忆",
            "扩展工具，让她更强大",
            "配置你的数字生命"};
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

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(0);

        buildUi();
        registerShortcuts();
    }

    // v1.23.0: 长按桌面图标显示快捷菜单 (打开悬浮窗 / 停止桌宠)
    private void registerShortcuts() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N_MR1) return;
        try {
            android.content.pm.ShortcutManager sm = getSystemService(android.content.pm.ShortcutManager.class);
            if (sm == null) return;

            // 1) 打开悬浮窗
            android.content.Intent startOverlay = new android.content.Intent(this, com.digitallife.service.PetService.class);
            startOverlay.setAction(com.digitallife.service.PetService.ACTION_START_OVERLAY);
            android.graphics.drawable.Icon startIcon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tab_chat);
            android.content.pm.ShortcutInfo startSi = new android.content.pm.ShortcutInfo.Builder(this, "start_overlay")
                    .setShortLabel(getString(R.string.action_start_overlay))
                    .setLongLabel(getString(R.string.action_start_overlay))
                    .setIcon(startIcon)
                    .setIntent(startOverlay)
                    .build();

            // 2) 停止桌宠
            android.content.Intent stopOverlay = new android.content.Intent(this, com.digitallife.service.PetService.class);
            stopOverlay.setAction(com.digitallife.service.PetService.ACTION_STOP_OVERLAY);
            android.graphics.drawable.Icon stopIcon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_tab_settings);
            android.content.pm.ShortcutInfo stopSi = new android.content.pm.ShortcutInfo.Builder(this, "stop_overlay")
                    .setShortLabel(getString(R.string.action_stop_overlay))
                    .setLongLabel(getString(R.string.action_stop_overlay))
                    .setIcon(stopIcon)
                    .setIntent(stopOverlay)
                    .build();

            sm.setDynamicShortcuts(java.util.Arrays.asList(startSi, stopSi));
        } catch (Exception ignored) {
            // 某些设备/ROM 限制，失败不影响主功能
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.page_bg));

        // ===== 顶部标题栏（大标题 + 副标题） =====
        // v1.23.0: 仿 Operit AI 深色紫色顶栏 56dp
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(getColorCompat(R.color.brand_operit));
        topBar.setElevation(dp(4));
        topBar.setPadding(dp(8), statusBarHeight() + dp(8), dp(20), dp(12));

        // v1.23.0 顶栏左侧汉堡按钮（仿 Operit TopAppBar navigationIcon）
        TextView btnMenu = new TextView(this);
        btnMenu.setText("\u2630");
        btnMenu.setTextSize(22f);
        btnMenu.setTextColor(Color.WHITE);
        btnMenu.setPadding(dp(8), dp(8), dp(16), dp(8));
        btnMenu.setIncludeFontPadding(false);
        topBar.addView(btnMenu, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        tvTitle = new TextView(this);
        tvTitle.setText(TAB_TITLES[0]);
        tvTitle.setTextSize(22f);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setIncludeFontPadding(false);
        titles.addView(tvTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        tvSubtitle = new TextView(this);
        tvSubtitle.setText(TAB_SUBTITLES[0]);
        tvSubtitle.setTextSize(11f);
        tvSubtitle.setTextColor(Color.WHITE);
        tvSubtitle.setAlpha(0.82f);
        tvSubtitle.setIncludeFontPadding(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(3);
        titles.addView(tvSubtitle, slp);
        topBar.addView(titles, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ===== 内容 + 侧栏容器（FrameLayout 让侧栏浮在内容上） =====
        FrameLayout shell = new FrameLayout(this);
        shell.setBackgroundColor(getColorCompat(R.color.page_bg));

        content = new FrameLayout(this);
        content.setBackgroundColor(getColorCompat(R.color.page_bg));
        shell.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 半透明遮罩
        drawerScrim = new View(this);
        drawerScrim.setBackgroundColor(0x99000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setAlpha(0f);
        drawerScrim.setOnClickListener(v -> closeDrawer());
        shell.addView(drawerScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // v1.23.0 Operit 侧栏
        operitDrawer = new OperitDrawer(this, route -> {
            closeDrawer();
            navController.navigate(route);
        });
        operitDrawer.setVisibility(View.GONE);
        operitDrawer.setTranslationX(-dp(280));
        shell.addView(operitDrawer, new FrameLayout.LayoutParams(
                dp(280), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        // 路由控制器
        navController = new OperitNavController(this, content,
                new OperitContentView.Host() {
                    @Override public com.digitallife.ui.ConversationTabView getConversationTab() { return conversationTab; }
                    @Override public com.digitallife.ui.ContactsTabView getContactsTab() { return contactsTab; }
                    @Override public com.digitallife.ui.DiscoverTabView getDiscoverTab() { return discoverTab; }
                    @Override public com.digitallife.ui.PluginTabView getPluginTab() { return pluginTab; }
                    @Override public com.digitallife.ui.SettingsTabView getSettingsTab() { return settingsTab; }
                    @Override public android.content.Context getContext() { return MainActivity.this; }
                    @Override public android.app.Activity getActivity() { return MainActivity.this; }
                });
        navController.addListener((old, newRoute) -> {
            operitDrawer.setSelected(newRoute);
            tvTitle.setText(newRoute.titleRes);
            // 副标题：5 个主壳 Tab 用原副标题，高级路由显示简短提示
            switch (newRoute) {
                case CHAT: tvSubtitle.setText(TAB_SUBTITLES[0]); break;
                case CONTACTS: tvSubtitle.setText(TAB_SUBTITLES[1]); break;
                case DISCOVER: tvSubtitle.setText(TAB_SUBTITLES[2]); break;
                case PLUGIN: tvSubtitle.setText(TAB_SUBTITLES[3]); break;
                case SETTINGS: tvSubtitle.setText(TAB_SUBTITLES[4]); break;
                default: tvSubtitle.setText("高级");
            }
            getSharedPreferences("main", MODE_PRIVATE).edit()
                    .putString("last_route", newRoute.id).apply();
        });

        // 汉堡按钮打开侧栏
        btnMenu.setOnClickListener(v -> openDrawer());

        conversationTab = new ConversationTabView(this, new ConversationTabView.Listener() {
            @Override
            public void onOpenSession(String sessionKey, String title, String type, String modelName) {
                openChat(sessionKey, title, type, modelName);
            }
        });
        contactsTab = new ContactsTabView(this, modelName -> {
            ChatStore cs = new ChatStore(MainActivity.this);
            String key = "model_" + modelName;
            cs.ensureSession(key, modelName, ChatStore.TYPE_MODEL, "model", modelName);
            openChat(key, modelName, ChatStore.TYPE_MODEL, modelName);
        });
        discoverTab = new DiscoverTabView(this);
        pluginTab = new PluginTabView(this);
        settingsTab = new SettingsTabView(this);

        root.addView(shell, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        // v1.23.0: 初次按 last_route 启动（默认 CHAT）
        String last = getSharedPreferences("main", MODE_PRIVATE).getString("last_route", OperitRoute.CHAT.id);
        OperitRoute startRoute = OperitRoute.CHAT;
        for (OperitRoute r : OperitRoute.values()) {
            if (r.id.equals(last)) { startRoute = r; break; }
        }
        navController.navigate(startRoute);
    }

    private void switchTab(int index) {
        // v1.23.0: 改为 route 切换，保留此方法占位以防外部调用崩溃
        OperitRoute[] primary = {OperitRoute.CHAT, OperitRoute.CONTACTS, OperitRoute.DISCOVER,
                OperitRoute.PLUGIN, OperitRoute.SETTINGS};
        if (index >= 0 && index < primary.length && navController != null) {
            navController.navigate(primary[index]);
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
            Toast.makeText(this, "无法打开对话: " + com.digitallife.ui.UiKit.safeMsg(e), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (settingsTab != null && settingsTab.handleActivityResult(requestCode, resultCode, data)) {
            return;
        }
        if (requestCode == PluginTabView.REQ_INSTALL_PLUGIN && resultCode == RESULT_OK && data != null) {
            pluginTab.handlePluginResult(data.getData());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (navController != null && OperitRoute.CHAT == navController.current()) {
            conversationTab.refresh();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private int getColorCompat(int res) {
        return getResources().getColor(res);
    }

    public ConversationTabView getConversationTab() { return conversationTab; }
    public ContactsTabView getContactsTab() { return contactsTab; }
    public DiscoverTabView getDiscoverTab() { return discoverTab; }
    public PluginTabView getPluginTab() { return pluginTab; }
    public SettingsTabView getSettingsTab() { return settingsTab; }

    // v1.23.0 侧栏控制
    private void openDrawer() {
        if (operitDrawer == null) return;
        operitDrawer.build(navController.current());
        operitDrawer.setVisibility(View.VISIBLE);
        operitDrawer.setTranslationX(0);
        drawerScrim.setVisibility(View.VISIBLE);
        operitDrawer.animate().translationX(0).setDuration(220).start();
        drawerScrim.animate().alpha(1f).setDuration(220).start();
    }

    private void closeDrawer() {
        if (operitDrawer == null) return;
        operitDrawer.animate().translationX(-operitDrawer.getWidth()).setDuration(180)
                .withEndAction(() -> operitDrawer.setVisibility(View.GONE))
                .start();
        drawerScrim.animate().alpha(0f).setDuration(180)
                .withEndAction(() -> drawerScrim.setVisibility(View.GONE))
                .start();
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }
}
