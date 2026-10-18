package com.digitallife.ui;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;

import com.digitallife.R;
import com.digitallife.util.Settings;

/**
 * 主题运行时（v1.179.0「主题设置」）。
 *
 * <p>全 App 是单套深色色板（{@code values/} 唯一来源，{@code values-night/} 被
 * {@code tools/check_color_parity.sh} 禁止重建，见 issue #98）。要让「强调色 / 外观」
 * 在**不加第二套 XML 色板**的前提下全站生效，本项目把「会变的颜色」提升为主题属性
 * （{@link com.digitallife.R.attr} {@code theme_*}，见 res/values/attrs.xml）：</p>
 *
 * <ul>
 *   <li>Java 取色统一走 {@link UiKit#color(Context, int)} → {@link #resolveColor}，
 *       按当前主题解析属性值；</li>
 *   <li>drawable 里把 {@code @color/xxx} 写成 {@code ?attr/theme_xxx}，绘制时按所在主题解析。</li>
 * </ul>
 *
 * <p>{@link #apply(Activity)} 负责在 {@code onCreate} 最早期套用「外观主题 + 强调色叠加」；
 * 默认（深紫 + 紫）不叠加任何 AccentStyle，属性值即 AppTheme 里的字面色，
 * 故默认主题下渲染与改造前完全一致（零回归）。</p>
 */
public final class ThemeManager {

    /** 外观：默认深紫底 */
    public static final String APPEARANCE_DARK = "dark";
    /** 外观：纯黑 OLED 底（仍为深色，不新增浅色板） */
    public static final String APPEARANCE_OLED = "oled";

    private ThemeManager() {
    }

    /**
     * 在 {@code Activity.onCreate} 的**第一句**调用（{@code super.onCreate} 之前），
     * 读出用户选择并套用主题。之后再去 buildUi / setContentView，属性才会生效。
     */
    public static void apply(Activity a) {
        if (a == null) return;
        Settings s = new Settings(a);
        String appearance = normalizeAppearance(s.getAppearance());
        a.setTheme(APPEARANCE_OLED.equals(appearance) ? R.style.AppThemeOled : R.style.AppTheme);
        int accentStyle = Accent.styleRes(s.getAccent());
        if (accentStyle != 0) {
            try {
                a.getTheme().applyStyle(accentStyle, true);
            } catch (Exception ignored) {
                // 套用失败退回默认强调色，不影响其它功能
            }
        }
    }

    /** 外观取值归一化：非 "oled" 一律视为默认深紫 */
    public static String normalizeAppearance(String v) {
        return APPEARANCE_OLED.equals(v) ? APPEARANCE_OLED : APPEARANCE_DARK;
    }

    /** 外观的中文名（供设置页展示） */
    public static String appearanceLabel(String v) {
        return APPEARANCE_OLED.equals(normalizeAppearance(v)) ? "纯黑（OLED）" : "深紫";
    }

    /**
     * 把「颜色资源」映射到「主题属性」；非主题色返回 0。
     * <p>只有这里登记的颜色才会随主题变化，其余颜色（语义色 / 标签色 / 玻璃白等）
     * 保持字面值不变——它们本来就不该跟着强调色跑。</p>
     */
    private static int attrFor(int colorRes) {
        if (colorRes == R.color.brand_operit) return R.attr.theme_accent;
        if (colorRes == R.color.brand_operit_light) return R.attr.theme_accent_light;
        if (colorRes == R.color.brand_operit_dark) return R.attr.theme_accent_dark;
        if (colorRes == R.color.operit_accent) return R.attr.theme_accent_text;
        if (colorRes == R.color.operit_header_start) return R.attr.theme_header_start;
        if (colorRes == R.color.operit_header_fade) return R.attr.theme_header_fade;
        if (colorRes == R.color.operit_primary_container) return R.attr.theme_accent_container;
        if (colorRes == R.color.glass_accent_top) return R.attr.theme_accent_glass_top;
        if (colorRes == R.color.glass_border_accent) return R.attr.theme_accent_glass_border;
        if (colorRes == R.color.operit_bg) return R.attr.theme_page_bg;
        if (colorRes == R.color.operit_surface) return R.attr.theme_surface;
        if (colorRes == R.color.operit_surface_variant) return R.attr.theme_surface_variant;
        if (colorRes == R.color.operit_divider) return R.attr.theme_divider;
        if (colorRes == R.color.operit_glow_top) return R.attr.theme_glow_top;
        if (colorRes == R.color.operit_glow_edge) return R.attr.theme_glow_edge;
        if (colorRes == R.color.operit_glow_bottom) return R.attr.theme_glow_bottom;
        return 0;
    }

    /**
     * 解析当前主题下某颜色资源的最终取值：主题色走属性解析，其余走资源字面值。
     * <p>取不到主题或属性缺失时，一律退回资源字面值，**永不抛异常**——
     * 这样服务侧等「未套主题」的上下文也能安全取色（退回默认色板）。</p>
     */
    public static int resolveColor(Context c, int colorRes) {
        int attr = attrFor(colorRes);
        if (attr == 0 || c == null) {
            return literalColor(c, colorRes);
        }
        try {
            TypedValue tv = new TypedValue();
            if (c.getTheme().resolveAttribute(attr, tv, true)
                    && tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
        } catch (Exception ignored) {
            // 上下文无主题 / 属性未定义 → 退回字面色
        }
        return literalColor(c, colorRes);
    }

    private static int literalColor(Context c, int colorRes) {
        if (c == null) return 0;
        try {
            return c.getResources().getColor(colorRes);
        } catch (Exception e) {
            return 0;
        }
    }
}
