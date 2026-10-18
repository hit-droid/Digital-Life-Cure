package com.digitallife.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.R;
import com.digitallife.model.ModelManager;
import com.digitallife.render.LiquidGlassLayerView;
import com.digitallife.render.Live2DNative;
import com.digitallife.ui.glass.BackdropCapture;
import com.digitallife.ui.glass.GlassPanelStyle;
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

    private static final String TAG = "MainActivity";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private View rootView;
    private TextView tvTitle;
    private TextView tvSubtitle;
    private FrameLayout content;

    // 主壳底部 5 Tab 导航（仿 Operit AI）
    private com.digitallife.ui.shell.OperitBottomNav bottomNav;

    // v1.23.0 Operit 侧栏
    private FrameLayout shell;
    private OperitDrawer operitDrawer;
    private View drawerScrim;
    private OperitNavController navController;

    // 侧栏的真折射玻璃层：与抽屉同位置、压在抽屉**下层**，折射它身后的页面内容
    private LiquidGlassLayerView glassLayer;
    private boolean drawerOpen;

    // 底部导航的真折射玻璃层：与导航同矩形、压在导航下层，折射导航身后的页面环境光。
    // 导航常驻可见且内容区不与它重叠，故快照只画 root 背景（见 uploadNavBackdrop）。
    private LiquidGlassLayerView navGlassLayer;
    private FrameLayout navWrap;
    private int navBdX = -1, navBdY = -1, navBdW = -1, navBdH = -1;
    private boolean navBdEnabled;

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
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);

        // 上次启动崩过：本次只把崩溃内容显示出来，不做任何可能再次触发崩溃的初始化，
        // 保证「进得去」且用户能直接看到/复制原因（此前是静默闪退，无从排查）。
        String previousCrash = CrashHandler.previousCrash(this);
        if (previousCrash != null) {
            showStartupCrashDialog(previousCrash);
            return;
        }

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
        // 必须在 buildUi()（内部 setContentView）之后调用：此时 decorView 必然已创建
        applySystemBars();
        registerShortcuts();
    }

    /**
     * 沉浸式系统栏：状态栏透明、内容用浅色图标（顶栏是深紫）。
     *
     * 关键：先取一次 {@code getWindow().getDecorView()}（内部会 installDecor），再取
     * insetsController。Android 16 的 {@code PhoneWindow.getInsetsController()} 直接解引用
     * mDecor、不再内部补建 decor，decor 尚未创建时会抛 NullPointerException —— 这正是
     * v1.148.0 及以前「Android 16 点开即闪退、且时好时坏」的真凶（见 CrashHandler 抓到的日志：
     * PhoneWindow.getInsetsController → MainActivity.onCreate）。这里既保证顺序、也做空值兜底。
     */
    private void applySystemBars() {
        android.view.Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        android.view.View decor = window.getDecorView();   // 触发 installDecor，避免 mDecor 为空
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            android.view.WindowInsetsController c = window.getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(0,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        } else {
            decor.setSystemUiVisibility(
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    /**
     * 上次启动崩溃时的提示页：把崩溃原文显示出来（可滚动、可复制），
     * 用户复制或点「重试」后清掉记录再重新走一次正常启动。
     */
    private void showStartupCrashDialog(String crash) {
        TextView tip = new TextView(this);
        tip.setText("上次启动崩溃了。下面是可以直接复制发给开发者的原因：");
        tip.setTextSize(14f);
        tip.setPadding(dp(20), dp(28), dp(20), dp(8));
        setContentView(tip);

        TextView detail = new TextView(this);
        detail.setText(crash);
        detail.setTextSize(11f);
        detail.setTextIsSelectable(true);
        detail.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(detail, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("上次启动崩溃了")
                .setView(scroll)
                .setCancelable(false)
                .setPositiveButton("复制原因", (d, w) -> {
                    copyCrash(crash);
                    CrashHandler.clearPreviousCrash(this);
                    Toast.makeText(this, "已复制，可直接粘贴发给开发者", Toast.LENGTH_LONG).show();
                    recreate();
                })
                .setNegativeButton("重试", (d, w) -> {
                    CrashHandler.clearPreviousCrash(this);
                    recreate();
                })
                .show();
    }

    private void copyCrash(String crash) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("digitallife_crash", crash));
        } catch (Exception ignored) {
        }
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
        // v1.158.0 Glass v2：整页铺环境光（顶紫主光 + 右上副光 + 底部回光），
        // 上层的玻璃卡片/导航才有东西可"透"，不再是一块死灰。
        root.setBackgroundResource(R.drawable.bg_operit_page);
        rootView = root;

        // ===== 顶部 AppBar（Operit AI 风格：紫色自顶向下渐隐 + 圆形菜单按钮） =====
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_operit_topbar);
        topBar.setPadding(dp(8), statusBarHeight() + dp(10), dp(14), dp(12));

        // 顶栏左侧导航菜单按钮（矢量图标 + 圆形涟漪）
        ImageView btnMenu = new ImageView(this);
        btnMenu.setImageResource(R.drawable.ic_menu);
        btnMenu.setColorFilter(Color.WHITE);
        btnMenu.setScaleType(ImageView.ScaleType.CENTER);
        btnMenu.setContentDescription("打开侧栏导航");
        btnMenu.setBackgroundResource(R.drawable.operit_icon_button_ripple);
        topBar.addView(btnMenu, new LinearLayout.LayoutParams(dp(44), dp(44)));

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
        tvSubtitle.setTextSize(11.5f);
        tvSubtitle.setTextColor(0xCCFFFFFF);
        tvSubtitle.setIncludeFontPadding(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        titles.addView(tvSubtitle, slp);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.leftMargin = dp(6);
        topBar.addView(titles, titlesLp);

        // 顶栏右侧：小汐头像 + 在线点（与侧栏头部同一视觉语言）
        FrameLayout avatarWrap = new FrameLayout(this);
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.mipmap.ic_launcher);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setClipToOutline(true);
        avatar.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());
            }
        });
        avatarWrap.addView(avatar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View onlineDot = new View(this);
        onlineDot.setBackgroundResource(R.drawable.bg_operit_online_dot);
        avatarWrap.addView(onlineDot, new FrameLayout.LayoutParams(
                dp(11), dp(11), Gravity.BOTTOM | Gravity.END));
        topBar.addView(avatarWrap, new LinearLayout.LayoutParams(dp(38), dp(38)));

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ===== 内容 + 侧栏容器（FrameLayout 让侧栏浮在内容上） =====
        shell = new FrameLayout(this);
        // 透明：环境光只铺一次（root），子容器再铺会出现重复的假亮带（见 bg_operit_page 注释）
        shell.setBackgroundColor(Color.TRANSPARENT);

        content = new FrameLayout(this);
        content.setBackgroundColor(Color.TRANSPARENT);
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

        // 真折射玻璃层：与抽屉同矩形、压在抽屉**之下**（抽屉背景透明时透出它），折射的
        // 是打开抽屉前抓到的页面快照。压在遮罩之上、抽屉之下，所以它比被遮暗的页面更亮，
        // 呈"浮起的玻璃面板"。常驻可见、平移出屏外，保证 SurfaceTexture 一直存活，
        // 避免每次开抽屉重建 EGL 上下文闪一帧空白。
        glassLayer = new LiquidGlassLayerView(this);
        glassLayer.setStyle(GlassPanelStyle.drawer());
        glassLayer.setTranslationX(-dp(280));
        glassLayer.setListener(this::onGlassReady);
        shell.addView(glassLayer, new FrameLayout.LayoutParams(
                dp(280), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));

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
            if (bottomNav != null) bottomNav.setSelected(newRoute);
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

        // 底部 5 Tab 导航：主 Tab 的唯一入口；记忆/护理等高级路由仍在左侧侧栏
        // 左右 + 底部留边距，配合四角圆角背景，呈现为页面底部「浮起的一枚胶囊」
        bottomNav = new com.digitallife.ui.shell.OperitBottomNav(this, route -> {
            if (navController != null) navController.navigate(route);
        });

        // 导航的真折射玻璃层：垫在导航**下层**，折射导航身后的页面环境光。
        // 外层 FrameLayout 承担原来加在 bottomNav 上的边距，使「玻璃层矩形 == 导航胶囊矩形」。
        navGlassLayer = new LiquidGlassLayerView(this);
        navGlassLayer.setStyle(GlassPanelStyle.nav());
        navGlassLayer.setListener(ok -> {
            Log.i(TAG, "导航玻璃层就绪=" + ok
                    + (ok ? "" : "：" + com.digitallife.render.GlassNative.loadError()));
            if (ok) uploadNavBackdrop();
        });
        navWrap = new FrameLayout(this);
        // 先垫玻璃层、再叠导航（顺序决定前后）。玻璃层初始高度 0：FrameLayout 对 MATCH_PARENT
        // 子视图按「可用空间」测量，直接 MATCH_PARENT 会把 wrap_content 的 navWrap 撑满整屏
        // （导航被顶到顶栏下、内容区被压成 0）。改由导航决定 navWrap 高度，玻璃层高度在布局
        // 后按导航实测高度对齐——见 uploadNavBackdrop。
        navWrap.addView(navGlassLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
        navWrap.addView(bottomNav, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.leftMargin = dp(12);
        navLp.rightMargin = dp(12);
        navLp.bottomMargin = dp(10);
        root.addView(navWrap, navLp);
        // 布局（含旋转后重排，本页声明了 configChanges 不会重建）完成后刷新快照：
        // 布局会多次回调，uploadNavBackdrop 内部按导航矩形去重，仅在位置/尺寸变化时重传。
        navWrap.getViewTreeObserver().addOnGlobalLayoutListener(this::uploadNavBackdrop);

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
        if (glassLayer != null) glassLayer.release();
        if (navGlassLayer != null) navGlassLayer.release();
        super.onDestroy();
    }

    private int getColorCompat(int res) {
        return UiKit.color(this, res);
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
        // 必须在抽屉可见之前抓快照：此刻抽屉还是 GONE、玻璃层平移在屏外，
        // 画面里不会混入浮层自身（否则玻璃会折射自己，形成自反馈）。
        uploadGlassBackdrop();
        drawerOpen = true;
        operitDrawer.setVisibility(View.VISIBLE);
        operitDrawer.setTranslationX(0);
        glassLayer.setTranslationX(0);
        drawerScrim.setVisibility(View.VISIBLE);
        android.view.animation.DecelerateInterpolator decel = new android.view.animation.DecelerateInterpolator(1.8f);
        operitDrawer.animate().translationX(0).setDuration(280).setInterpolator(decel).start();
        drawerScrim.animate().alpha(1f).setDuration(280).setInterpolator(decel).start();
        content.animate().translationX(dp(20)).setDuration(280).setInterpolator(decel).start();
    }

    private void closeDrawer() {
        if (operitDrawer == null) return;
        drawerOpen = false;
        android.view.animation.DecelerateInterpolator decel = new android.view.animation.DecelerateInterpolator(1.8f);
        operitDrawer.animate().translationX(-operitDrawer.getWidth()).setDuration(220).setInterpolator(decel)
                .withEndAction(() -> operitDrawer.setVisibility(View.GONE))
                .start();
        // 玻璃层与抽屉同矩形，跟随同一段动画滑出（保持可见，以便 SurfaceTexture 常驻）
        if (glassLayer != null) {
            glassLayer.animate().translationX(-glassLayer.getWidth()).setDuration(220)
                    .setInterpolator(decel).start();
        }
        drawerScrim.animate().alpha(0f).setDuration(220).setInterpolator(decel)
                .withEndAction(() -> drawerScrim.setVisibility(View.GONE))
                .start();
        content.animate().translationX(0).setDuration(220).setInterpolator(decel).start();
    }

    /**
     * 用当前页面内容刷新玻璃层的背景快照与面板矩形。
     *
     * <p>约定：面板矩形取玻璃层自身尺寸，快照区域取 root 上同一矩形（左上角在 shell 顶部），
     * 因此 panel 内坐标与快照像素严格 1:1、origin 恒为 (0,0)——这是折射不错位的前提。</p>
     */
    private void uploadGlassBackdrop() {
        if (glassLayer == null || operitDrawer == null) return;
        int w = glassLayer.getWidth();
        int h = glassLayer.getHeight();
        if (w <= 0 || h <= 0) return;   // 尚未布局，跳过（下次打开再试）
        if (!glassLayer.isAvailable()) {
            // native 未加载 / 着色器未就绪：保留抽屉原有不透明面板
            operitDrawer.setGlassEnabled(false);
            glassLayer.setPanel(0, 0, 0, 0, 0, 0);
            return;
        }
        Bitmap bmp = BackdropCapture.captureRegion(rootView, 0, shell.getTop(), w, h);
        if (bmp == null) {
            operitDrawer.setGlassEnabled(false);
            glassLayer.setPanel(0, 0, 0, 0, 0, 0);
            return;
        }
        // 先给背景纹理、再亮面板：两次调用各自独立加锁，GL 线程可能在中间被唤醒；
        // 若先亮面板后传背景，中间那一帧会因纹理为空（texId=0）画出黑块。
        glassLayer.setBackdrop(bmp);
        glassLayer.setPanel(0, 0, w, h, 0, 0);
        operitDrawer.setGlassEnabled(true);
    }

    /** 玻璃层就绪回调（首次开抽屉时 GL 还在初始化，就绪后补一次快照） */
    private void onGlassReady(boolean ok) {
        Log.i(TAG, "液态玻璃层就绪=" + ok + (ok ? "" : "：" + com.digitallife.render.GlassNative.loadError()));
        if (ok && drawerOpen) uploadGlassBackdrop();
    }

    /**
     * 用导航身后的页面背景刷新底部导航玻璃层的快照与面板矩形。
     *
     * <p>导航是 root（LinearLayout）里独立的一行，内容区在它上方、与它不重叠，故它身后
     * 只有 root 的页面背景（{@code bg_operit_page} 的三层环境光）。这里只画 root 的背景
     * drawable、**不画任何子 View**，避免把导航自身画进快照（自反馈）。面板矩形 ==
     * 玻璃层矩形 == 导航胶囊矩形，故 origin 恒为 (0,0)、面板内坐标与快照严格 1:1。</p>
     */
    private void uploadNavBackdrop() {
        if (navGlassLayer == null || navWrap == null || bottomNav == null || rootView == null) return;
        // 玻璃层高度对齐导航的实测高度：首帧导航尚未量出高度（或刚 setLayoutParams）时先返回，
        // 下一轮布局回调再继续——这样玻璃层矩形与导航胶囊矩形严格一致。
        int navH = bottomNav.getHeight();
        FrameLayout.LayoutParams glp = (FrameLayout.LayoutParams) navGlassLayer.getLayoutParams();
        if (navH > 0 && glp.height != navH) {
            glp.height = navH;
            navGlassLayer.setLayoutParams(glp);
            return;
        }
        int w = navGlassLayer.getWidth();
        int h = navGlassLayer.getHeight();
        if (w <= 0 || h <= 0 || rootView.getWidth() <= 0 || rootView.getHeight() <= 0) return;  // 尚未布局
        int navX = navWrap.getLeft();
        int navY = navWrap.getTop();
        // 布局会多次回调：仅在矩形变化（首帧、旋转重排）或尚未启用时重传
        if (navBdEnabled && navX == navBdX && navY == navBdY && w == navBdW && h == navBdH) return;

        if (!navGlassLayer.isAvailable()) {
            // native 未加载 / 着色器未就绪：保留导航原有的半透明玻璃面板
            setNavGlass(false);
            navGlassLayer.setPanel(0, 0, 0, 0, 0, 0);
            return;
        }
        Bitmap bmp = BackdropCapture.captureDrawableRegion(rootView.getBackground(),
                rootView.getWidth(), rootView.getHeight(), navX, navY, w, h);
        if (bmp == null) {
            setNavGlass(false);
            navGlassLayer.setPanel(0, 0, 0, 0, 0, 0);
            return;
        }
        // 先给背景纹理、再亮面板：中间那一帧若纹理为空（texId=0）会画出黑块
        navGlassLayer.setBackdrop(bmp);
        navGlassLayer.setPanel(0, 0, w, h, 0, 0);
        setNavGlass(true);
        navBdX = navX;
        navBdY = navY;
        navBdW = w;
        navBdH = h;
    }

    /** 切换导航玻璃态（幂等：状态未变时不重复改背景） */
    private void setNavGlass(boolean enabled) {
        if (navBdEnabled == enabled) return;
        navBdEnabled = enabled;
        bottomNav.setGlassEnabled(enabled);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }
}
