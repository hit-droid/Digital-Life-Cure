package com.digitallife.ui.pet;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 桌宠长按快捷菜单纯逻辑回归。
 *
 * <p>这些值看着简单，但表情循环越界会直接抛异常、居中坐标算错会把桌宠推出屏幕外，
 * 而悬浮窗场景没法在单测里截图验证，所以把边界都钉住。</p>
 */
public class PetQuickMenuTest {

    @Test
    public void nextExpression_cyclesInOrder() {
        assertEquals("F02", PetQuickMenu.nextExpression("F01"));
        assertEquals("F03", PetQuickMenu.nextExpression("F02"));
        assertEquals("F06", PetQuickMenu.nextExpression("F05"));
    }

    @Test
    public void nextExpression_wrapsAtEnd() {
        assertEquals("F01", PetQuickMenu.nextExpression("F06"));
    }

    @Test
    public void nextExpression_unknownStartsFromSecond() {
        // 未知当前表情时不能原地不动（否则长按换表情像失灵），从 F01 的下一个开始
        assertEquals("F02", PetQuickMenu.nextExpression(null));
        assertEquals("F02", PetQuickMenu.nextExpression(""));
        assertEquals("F02", PetQuickMenu.nextExpression("happy"));
        assertEquals("F02", PetQuickMenu.nextExpression("F99"));
    }

    @Test
    public void nextExpression_neverReturnsNull() {
        String cur = "F01";
        for (int i = 0; i < 12; i++) {
            cur = PetQuickMenu.nextExpression(cur);
            assertTrue("循环内不应出现空表情", cur != null && !cur.isEmpty());
        }
    }

    @Test
    public void expressionLabel_mapsKnownAndFallsBack() {
        assertEquals("平静", PetQuickMenu.expressionLabel("F01"));
        assertEquals("生气", PetQuickMenu.expressionLabel("F04"));
        assertEquals("害羞", PetQuickMenu.expressionLabel("F06"));
        assertEquals("平静", PetQuickMenu.expressionLabel(null));
        assertEquals("平静", PetQuickMenu.expressionLabel("bogus"));
    }

    @Test
    public void items_unlockedOffersLock() {
        String[] items = PetQuickMenu.items(false);
        assertEquals(5, items.length);
        assertEquals("换表情", items[PetQuickMenu.ITEM_EXPRESSION]);
        assertEquals("一键贴边", items[PetQuickMenu.ITEM_DOCK_EDGE]);
        assertEquals("回到中间", items[PetQuickMenu.ITEM_CENTER]);
        assertEquals("锁定位置", items[PetQuickMenu.ITEM_LOCK]);
        assertEquals("打开设置", items[PetQuickMenu.ITEM_SETTINGS]);
    }

    @Test
    public void items_lockedOffersUnlock() {
        // 已经锁着时菜单里必须是「解除锁定」，否则用户会以为点了没反应
        String[] items = PetQuickMenu.items(true);
        assertEquals(5, items.length);
        assertEquals("解除锁定", items[PetQuickMenu.ITEM_LOCK]);
    }

    @Test
    public void centerX_centersWithinScreen() {
        assertEquals(450, PetQuickMenu.centerX(100, 1000));
        assertEquals(300, PetQuickMenu.centerX(400, 1000));
    }

    @Test
    public void center_clampsWhenViewIsLargerOrDirty() {
        // 窗口比屏幕宽 / 尺寸是脏数据时必须夹回 0，不能给出负坐标把窗口推到屏外
        assertEquals(0, PetQuickMenu.centerX(1000, 1000));
        assertEquals(0, PetQuickMenu.centerX(1200, 1000));
        assertEquals(0, PetQuickMenu.centerX(100, 0));
        assertEquals(0, PetQuickMenu.centerY(900, 800));
        assertEquals(0, PetQuickMenu.centerX(0, 0));
    }

    @Test
    public void centerY_centersWithinScreen() {
        assertEquals(300, PetQuickMenu.centerY(200, 800));
        assertEquals(350, PetQuickMenu.centerY(100, 800));
    }
}
