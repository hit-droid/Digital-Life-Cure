package com.digitallife.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private com.digitallife.ui.shell.OperitDrawer operitDrawer;
    private View drawerScrim;
    private com.digitallife.ui.shell.OperitNavController navController;

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
        operitDrawer = new com.digitallife.ui.shell.OperitDrawer(this, route -> {
            closeDrawer();
            navController.navigate(route);
        });
        operitDrawer.setVisibility(View.GONE);
        operitDrawer.setTranslationX(-dp(280));
        shell.addView(operitDrawer, new FrameLayout.LayoutParams(
                dp(280), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

        // 路由控制器
        navController = new com.digitallife.ui.shell.OperitNavController(this, content,
                new com.digitallife.ui.shell.OperitContentView.Host() {
                    @Override public com.digitallife.ui.ConversationTabView getConversationTab() { return conversationTab; }
                    @Override public com.digitallife.ui.ContactsTabView getContactsTab() { return contactsTab; }
                    @Override public com.digitallife.ui.DiscoverTabView getDiscoverTab() { return discoverTab; }
                    @Override public com.digitallife.ui.PluginTabView getPluginTab() { return pluginTab; }
                    @Override public com.digitallife.ui.SettingsTabView getSettingsTab() { return settingsTab; }
                    @Override public android.content.Context getContext() { return MainActivity.this; }
                });
        navController.addListener((old, newRoute) -> {
            operitDrawer.setSelected(newRoute);
            // 同步底部 Tab 选中态
            for (int i = 0; i < TAB_TITLES.length; i++) {
                if (TAB_TITLES[i].equals(getString(newRoute.titleRes))) {
                    switchTab(i);
                    return;
                }
            }
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

        root.addView(shell, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== 底部导航（玻璃底 + 圆点指示 + 选中渐变胶囊） =====
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setBackgroundColor(getColorCompat(R.color.surface_glass));
        navBar.setElevation(dp(10));
        navBar.setPadding(dp(8), dp(4), dp(8), dp(6));
        int[] iconRes = {R.drawable.ic_tab_chat, R.drawable.ic_tab_contacts,
                R.drawable.ic_tab_discover, R.drawable.ic_tab_plugin, R.drawable.ic_tab_settings};
        for (int i = 0; i < 5; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(4), dp(3), dp(4), dp(3));

            View dot = new View(this);
            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(getColorCompat(R.color.brand));
            dot.setBackground(dotBg);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(4), dp(4));
            dotLp.bottomMargin = dp(2);
            dot.setVisibility(View.GONE);
            item.addView(dot, dotLp);

            ImageView icon = new ImageView(this);
            icon.setImageResource(iconRes[i]);
            icon.setScaleType(ImageView.ScaleType.CENTER);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), dp(22));

            TextView label = new TextView(this);
            label.setText(TAB_TITLES[i]);
            label.setTextSize(10f);
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(2);

            item.addView(icon, ilp);
            item.addView(label, llp);
            item.setOnClickListener(v -> switchTab(index));
            navItems[i] = item;
            navBar.addView(item, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
        root.addView(navBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        setContentView(root);
        switchTab(getSharedPreferences("main", MODE_PRIVATE).getInt("last_tab", 0));
    }

    private void switchTab(int index) {
        int prev = currentTab;
        currentTab = index;
        getSharedPreferences("main", MODE_PRIVATE).edit().putInt("last_tab", index).apply();
        tvTitle.setText(TAB_TITLES[index]);
        tvSubtitle.setText(TAB_SUBTITLES[index]);
        int active = getColorCompat(R.color.brand);
        int inactive = getColorCompat(R.color.text_hint);
        for (int i = 0; i < 5; i++) {
            boolean sel = i == index;
            LinearLayout item = navItems[i];
            View dot = item.getChildAt(0);
            ImageView icon = (ImageView) item.getChildAt(1);
            TextView label = (TextView) item.getChildAt(2);
            dot.setVisibility(sel ? View.VISIBLE : View.GONE);
            icon.setColorFilter(sel ? active : inactive);
            label.setTextColor(sel ? active : inactive);
            label.setTypeface(Typeface.DEFAULT, sel ? Typeface.BOLD : Typeface.NORMAL);
            if (sel) {
                GradientDrawable g = new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        new int[]{getColorCompat(R.color.brand_light),
                                getColorCompat(R.color.brand_soft)});
                g.setCornerRadius(dp(14));
                item.setBackground(g);
                item.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80)
                        .withEndAction(() -> item.animate().scaleX(1f).scaleY(1f)
                                .setDuration(160).start())
                        .start();
            } else {
                item.setBackground(null);
            }
        }
        content.setVisibility(View.VISIBLE);
        switchTabView(conversationTab, index == 0);
        switchTabView(contactsTab, index == 1);
        switchTabView(discoverTab, index == 2);
        switchTabView(pluginTab, index == 3);
        switchTabView(settingsTab, index == 4);
        // 切换到该 Tab 时刷新
        switch (index) {
            case 0: conversationTab.refresh(); break;
            case 1: contactsTab.refresh(); break;
            case 2: discoverTab.refresh(); break;
            case 3: pluginTab.refresh(); break;
            case 4: settingsTab.onResume(); break;
        }
    }

    /** 子视图淡入淡出切换 */
    private void switchTabView(View v, boolean show) {
        if (show) {
            v.setAlpha(0f);
            v.setVisibility(View.VISIBLE);
            v.animate().alpha(1f).setDuration(180).start();
        } else {
            v.setVisibility(View.GONE);
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
