package com.digitallife.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import com.digitallife.care.CareModelsActivity;
import com.digitallife.ui.shell.OperitDrawer;
import com.digitallife.ui.shell.OperitRoute;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 截图自查工具（开发用）：不开模拟器、不连真机，纯 JVM 用 Robolectric 的 NATIVE 图形模式
 * 把界面真渲染成 PNG，输出到 app/build/screenshots/。改 UI 后可先看这里再推 CI。
 *
 * <p>边界：Robolectric 里没有真实系统状态栏/导航栏，也没有 native 库（Live2DNative），
 * 所以它看得到「排版 / 层次 / 配色 / 白底白字」这类静态问题，看不到沉浸式状态栏叠字、
 * 动画、真机字体差异。渲染是近似的，不作为像素级基准。</p>
 *
 * <p>视口取 411dp×891dp@xxhdpi（≈ Pixel 尺寸），只截 app 内容区（android.R.id.content）。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w411dp-h891dp-xxhdpi",
        shadows = {ShadowLive2DNative.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenshotRenderTest {

    private static final int DENSITY = 3;
    private static final int W = 411 * DENSITY;
    private static final int H = 891 * DENSITY;

    /** 护理页（护理路由 → 模型管理）：应看到列表空态 + 底部详情区 */
    @Test
    public void careModelsPage() throws Exception {
        ActivityController<CareModelsActivity> c =
                Robolectric.buildActivity(CareModelsActivity.class).setup();
        View content = c.get().findViewById(android.R.id.content);
        capture(content, W, H, "care_models.png");
    }

    /** 侧栏抽屉：280dp 宽，选中「对话」 */
    @Test
    public void drawer() throws Exception {
        ActivityController<CareModelsActivity> c =
                Robolectric.buildActivity(CareModelsActivity.class).setup();
        Activity host = c.get();
        OperitDrawer drawer = new OperitDrawer(host, route -> { });
        drawer.build(OperitRoute.CHAT);
        capture(drawer, 280 * DENSITY, H, "drawer_chat.png");
    }

    /**
     * 全 App 逐页截图：「全 App 统一过一遍」的自查入口。
     *
     * <p>每个页面单独 try/catch —— Robolectric 里个别页会因为缺 native 库 / 缺权限 / 缺
     * Intent extra 起不来，但不能让一个页面挂掉就吞掉其余截图。失败会以 [skip] 打印原因，
     * 其余页面照常出图。</p>
     */
    @Test
    public void allScreens() {
        String[][] screens = {
                {"main_shell", "com.digitallife.ui.MainActivity"},
                {"chat", "com.digitallife.ui.ChatActivity"},
                {"memory_manage", "com.digitallife.ui.MemoryManageActivity"},
                {"persona", "com.digitallife.ui.PersonaActivity"},
                {"themes", "com.digitallife.ui.ThemesActivity"},
                {"tool_market", "com.digitallife.ui.ToolMarketActivity"},
                {"about", "com.digitallife.ui.AboutActivity"},
                {"developer", "com.digitallife.ui.DeveloperActivity"},
                {"agent_console", "com.digitallife.ui.AgentConsoleActivity"},
        };
        for (String[] s : screens) {
            String name = s[0];
            try {
                Class<? extends Activity> cls =
                        Class.forName(s[1]).asSubclass(Activity.class);
                ActivityController<? extends Activity> c =
                        Robolectric.buildActivity(cls).setup();
                View content = c.get().findViewById(android.R.id.content);
                if (content == null) {
                    System.out.println("[skip] " + name + "：找不到 android.R.id.content");
                    continue;
                }
                capture(content, W, H, name + ".png");
            } catch (Throwable t) {
                System.out.println("[skip] " + name + "：" + t);
            }
        }
    }

    private void capture(View v, int w, int h, String name) throws Exception {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, h);

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        v.draw(new Canvas(bmp));

        File dir = new File("build/screenshots");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建截图目录: " + dir.getAbsolutePath());
        }
        File out = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        System.out.println("[screenshot] " + out.getAbsolutePath() + " (" + out.length() + " bytes)");
    }
}
