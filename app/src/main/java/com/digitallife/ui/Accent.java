package com.digitallife.ui;

import com.digitallife.R;

/**
 * 强调色预设（v1.179.0「主题设置」）：纯元数据 + 归一化，供主题页与
 * {@link ThemeManager} 复用，不持有任何状态。
 *
 * <p>默认「紫」＝不叠加任何 AccentStyle（即 AppTheme 的字面色，与改造前一致）；
 * 其余预设各对应一个 {@code AccentStyle*}（见 res/values/styles.xml），
 * 由 {@link ThemeManager#apply} 在运行时叠加到外观主题上。</p>
 */
public final class Accent {

    /** 默认强调色键（Operit 紫） */
    public static final String DEFAULT = "purple";

    private static final String[] KEYS = {"purple", "blue", "cyan", "green", "orange", "pink"};
    private static final String[] LABELS = {"幻紫", "星蓝", "青碧", "森绿", "暖橙", "樱粉"};
    /** 与 KEYS 一一对应的样式（默认紫为 0＝不叠加） */
    private static final int[] STYLES = {
            0,
            R.style.AccentStyleBlue,
            R.style.AccentStyleCyan,
            R.style.AccentStyleGreen,
            R.style.AccentStyleOrange,
            R.style.AccentStylePink,
    };
    /** 与 KEYS 一一对应的色卡（供主题页画选择圆点） */
    private static final int[] SWATCHES = {
            R.color.brand_operit,
            R.color.accent_blue,
            R.color.accent_cyan,
            R.color.accent_green,
            R.color.accent_orange,
            R.color.accent_pink,
    };

    private Accent() {
    }

    public static int count() {
        return KEYS.length;
    }

    public static String keyAt(int index) {
        if (index < 0 || index >= KEYS.length) return DEFAULT;
        return KEYS[index];
    }

    /** 取某 key 在预设表中的下标；未知 / null / 空白一律归为默认（0） */
    public static int indexOf(String key) {
        if (key != null) {
            String k = key.trim().toLowerCase(java.util.Locale.ROOT);
            for (int i = 0; i < KEYS.length; i++) {
                if (KEYS[i].equals(k)) return i;
            }
        }
        return 0;
    }

    /** 归一化：返回一个**一定合法**的强调色 key */
    public static String normalize(String key) {
        return keyAt(indexOf(key));
    }

    /** 强调色中文名 */
    public static String label(String key) {
        return LABELS[indexOf(key)];
    }

    /** 该强调色对应的样式资源；默认紫返回 0（表示不叠加任何样式） */
    public static int styleRes(String key) {
        return STYLES[indexOf(key)];
    }

    /** 该强调色的色卡颜色资源（选择圆点用） */
    public static int swatchRes(String key) {
        return SWATCHES[indexOf(key)];
    }
}
