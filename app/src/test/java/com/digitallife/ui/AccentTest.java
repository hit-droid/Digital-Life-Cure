package com.digitallife.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.digitallife.R;

import org.junit.Test;

/**
 * 强调色预设元数据的边界单测（v1.179.0，纯 JVM，不依赖 Android 运行时）。
 * 重点是「任何脏输入都被收敛到合法值」——主题键来自用户配置，缺省/写坏都不能崩。
 */
public class AccentTest {

    @Test
    public void count_andKeys_areStable() {
        assertEquals(6, Accent.count());
        assertEquals("purple", Accent.DEFAULT);
        assertEquals("purple", Accent.keyAt(0));
        assertEquals("blue", Accent.keyAt(1));
        assertEquals("pink", Accent.keyAt(5));
    }

    @Test
    public void keyAt_outOfRange_fallsBackToDefault() {
        assertEquals("purple", Accent.keyAt(-1));
        assertEquals("purple", Accent.keyAt(999));
    }

    @Test
    public void indexOf_isCaseInsensitiveAndTrimmed() {
        assertEquals(1, Accent.indexOf("blue"));
        assertEquals(1, Accent.indexOf("BLUE"));
        assertEquals(1, Accent.indexOf("  Blue "));
        assertEquals(3, Accent.indexOf("green"));
    }

    @Test
    public void indexOf_unknownBlankNull_returnsDefault() {
        assertEquals(0, Accent.indexOf("nope"));
        assertEquals(0, Accent.indexOf(""));
        assertEquals(0, Accent.indexOf("   "));
        assertEquals(0, Accent.indexOf(null));
    }

    @Test
    public void normalize_alwaysReturnsValidKey() {
        assertEquals("green", Accent.normalize("green"));
        assertEquals("purple", Accent.normalize("Σ"));
        assertEquals("purple", Accent.normalize(null));
    }

    @Test
    public void label_isChinese_andFallsBackForUnknown() {
        assertEquals("幻紫", Accent.label("purple"));
        assertEquals("星蓝", Accent.label("blue"));
        assertEquals("樱粉", Accent.label("pink"));
        assertEquals("幻紫", Accent.label("bogus"));
    }

    @Test
    public void styleRes_defaultIsZero_othersMapped() {
        // 默认紫＝不叠加任何 AccentStyle（沿用 AppTheme 字面色）
        assertEquals(0, Accent.styleRes("purple"));
        assertEquals(R.style.AccentStyleBlue, Accent.styleRes("blue"));
        assertEquals(R.style.AccentStyleCyan, Accent.styleRes("cyan"));
        assertEquals(R.style.AccentStyleGreen, Accent.styleRes("green"));
        assertEquals(R.style.AccentStyleOrange, Accent.styleRes("orange"));
        assertEquals(R.style.AccentStylePink, Accent.styleRes("pink"));
        assertEquals(0, Accent.styleRes("bogus"));
    }

    @Test
    public void swatchRes_isLiteralPerPreset_distinct() {
        assertEquals(R.color.brand_operit, Accent.swatchRes("purple"));
        assertEquals(R.color.accent_blue, Accent.swatchRes("blue"));
        assertEquals(R.color.accent_pink, Accent.swatchRes("pink"));
        assertEquals(R.color.brand_operit, Accent.swatchRes("bogus"));
        // 六个色卡互不相同——否则主题页会出现「同色六连」的假选择
        for (int i = 0; i < Accent.count(); i++) {
            for (int j = i + 1; j < Accent.count(); j++) {
                assertNotEquals(Accent.swatchRes(Accent.keyAt(i)), Accent.swatchRes(Accent.keyAt(j)));
            }
        }
    }

    @Test
    public void everyPreset_hasNonEmptyLabel() {
        for (int i = 0; i < Accent.count(); i++) {
            String label = Accent.label(Accent.keyAt(i));
            assertTrue(label != null && !label.isEmpty());
        }
    }
}
