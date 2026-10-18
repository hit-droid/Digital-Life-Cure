package com.digitallife.ui;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.content.Context;

import com.digitallife.R;
import com.digitallife.util.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * 主题运行时单测（v1.179.0，Robolectric）。
 *
 * <p>锁三件事：</p>
 * <ol>
 *   <li>默认主题下取色＝资源字面色（改造前后零回归）；</li>
 *   <li>选了强调色后，`brand_operit` 等主题 token 解析到该预设的色值；</li>
 *   <li>非主题色（语义色等）不随主题变化；外观切 OLED 后页面底色变纯黑。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ThemeManagerTest {

    private Activity themedActivity() {
        ActivityController<ThemesActivity> c =
                Robolectric.buildActivity(ThemesActivity.class).setup();
        return c.get();
    }

    @Test
    public void normalizeAppearance_unknownFallsBackToDark() {
        assertEquals("oled", ThemeManager.normalizeAppearance("oled"));
        assertEquals("dark", ThemeManager.normalizeAppearance("dark"));
        assertEquals("dark", ThemeManager.normalizeAppearance(null));
        assertEquals("dark", ThemeManager.normalizeAppearance("light"));
    }

    @Test
    public void appearanceLabel_isHumanReadable() {
        assertEquals("纯黑（OLED）", ThemeManager.appearanceLabel("oled"));
        assertEquals("深紫", ThemeManager.appearanceLabel("dark"));
        assertEquals("深紫", ThemeManager.appearanceLabel("???"));
    }

    @Test
    public void defaultTheme_resolvesToLiteralColor() {
        Context app = RuntimeEnvironment.getApplication();
        new Settings(app).setAccent("purple");
        new Settings(app).setAppearance("dark");
        Activity a = themedActivity();
        // 主题 token 在默认主题下应等于资源字面色（零回归）
        assertEquals(a.getResources().getColor(R.color.brand_operit),
                ThemeManager.resolveColor(a, R.color.brand_operit));
        assertEquals(a.getResources().getColor(R.color.operit_bg),
                ThemeManager.resolveColor(a, R.color.operit_bg));
    }

    @Test
    public void accentTheme_overridesAccentTokens() {
        Context app = RuntimeEnvironment.getApplication();
        new Settings(app).setAccent("green");
        new Settings(app).setAppearance("dark");
        Activity a = themedActivity();
        assertEquals(a.getResources().getColor(R.color.accent_green),
                ThemeManager.resolveColor(a, R.color.brand_operit));
        assertEquals(a.getResources().getColor(R.color.accent_green_text),
                ThemeManager.resolveColor(a, R.color.operit_accent));
        assertEquals(a.getResources().getColor(R.color.accent_green_light),
                ThemeManager.resolveColor(a, R.color.brand_operit_light));
    }

    @Test
    public void accentTheme_doesNotTouchBaseOrSemanticColors() {
        Context app = RuntimeEnvironment.getApplication();
        new Settings(app).setAccent("orange");
        Activity a = themedActivity();
        // 语义色不随强调色变化
        assertEquals(a.getResources().getColor(R.color.success),
                ThemeManager.resolveColor(a, R.color.success));
        // 页面底也不随强调色变化
        assertEquals(a.getResources().getColor(R.color.operit_bg),
                ThemeManager.resolveColor(a, R.color.operit_bg));
    }

    @Test
    public void oledAppearance_overridesPageBackground() {
        Context app = RuntimeEnvironment.getApplication();
        new Settings(app).setAccent("purple");
        new Settings(app).setAppearance("oled");
        Activity a = themedActivity();
        assertEquals(a.getResources().getColor(R.color.oled_bg),
                ThemeManager.resolveColor(a, R.color.operit_bg));
        assertEquals(a.getResources().getColor(R.color.oled_surface),
                ThemeManager.resolveColor(a, R.color.operit_surface));
    }

    @Test
    public void resolveColor_neverThrows_forUnthemedContext() {
        // 应用上下文（服务侧取色场景）：不得抛异常，退回可用值即可
        Context app = RuntimeEnvironment.getApplication();
        int v = ThemeManager.resolveColor(app, R.color.brand_operit);
        assertEquals(app.getResources().getColor(R.color.brand_operit), v);
    }

    @Test
    public void resolveColor_handlesNullContext() {
        assertEquals(0, ThemeManager.resolveColor(null, R.color.brand_operit));
    }
}
