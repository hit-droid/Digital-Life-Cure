package com.digitallife.ui.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;

import com.digitallife.ui.MarkdownRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * v1.189.0 {@link MessageCardRenderer} 的回归。
 *
 * <p>用 Robolectric 的 NATIVE 图形模式真出一张图，锁住三件事：尺寸算得对、
 * 空内容不炸、超长消息被截断（否则一张图十几 MB，分享出去也是坑）。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MessageCardRendererTest {

    private static final float DENSITY = 3f;

    private static MessageCardRenderer.Style style() {
        return new MessageCardRenderer.Style(
                0xFF0F0D17, // 页底
                0xFF1F1C2C, // 卡片底
                0xFFF1EDFB, // 正文
                0xFFA9A1C4, // 次要文字
                0xFFB9A6FF, // 强调
                DENSITY);
    }

    @Test
    public void render_shortText_producesCardWithExpectedWidth() {
        Bitmap bmp = MessageCardRenderer.render("你好，世界", 600, 45f, style(), "数字生命", "落款");
        assertNotNull(bmp);
        // 图宽 = 卡片宽 + 两侧页边距
        assertEquals(600 + 2 * Math.round(14 * DENSITY), bmp.getWidth());
        assertTrue("卡片应有一定高度：" + bmp.getHeight(), bmp.getHeight() > 100);
    }

    @Test
    public void render_emptyBody_returnsNull() {
        assertNull(MessageCardRenderer.render("", 600, 45f, style(), "h", "f"));
        assertNull(MessageCardRenderer.render(null, 600, 45f, style(), "h", "f"));
    }

    @Test
    public void render_invalidSize_returnsNull() {
        assertNull(MessageCardRenderer.render("内容", 0, 45f, style(), "h", "f"));
        assertNull(MessageCardRenderer.render("内容", 600, 0f, style(), "h", "f"));
    }

    @Test
    public void render_longerText_isTaller() {
        Bitmap shortBmp = MessageCardRenderer.render("短", 600, 45f, style(), "h", "f");
        Bitmap longBmp = MessageCardRenderer.render("很长的一段文字\n第二行\n第三行\n第四行",
                600, 45f, style(), "h", "f");
        assertNotNull(shortBmp);
        assertNotNull(longBmp);
        assertTrue("多行内容应更高", longBmp.getHeight() > shortBmp.getHeight());
    }

    @Test
    public void render_veryLongText_isTruncatedToMaxHeight() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            sb.append("第").append(i).append("行内容\n");
        }
        Bitmap bmp = MessageCardRenderer.render(sb.toString(), 600, 45f, style(), "h", "f");
        assertNotNull(bmp);
        assertTrue("高度必须受上限约束，实际 " + bmp.getHeight(),
                bmp.getHeight() <= MessageCardRenderer.MAX_CARD_HEIGHT_PX);
    }

    /** 气泡里的表格是位图 span，渲染长图时要能把它画出来（不能抛异常） */
    @Test
    public void render_tableRichText_doesNotCrash() {
        MarkdownRenderer renderer = new MarkdownRenderer(
                0xFF2A2740, 0xFFE6EDF3, 0xFFA9A1C4, 0xFFF1EDFB, 0xFFB9A6FF, 0xFF322D45);
        CharSequence rich = renderer.renderRich(
                "| 名称 | 数值 |\n|---|---|\n| 甲 | 12 |\n| 乙 | 34 |\n", 520, 45f);
        Bitmap bmp = MessageCardRenderer.render(rich, 600, 45f, style(), "数字生命", "落款");
        assertNotNull(bmp);
        assertTrue(bmp.getHeight() > 100);
    }

    /** 带代码块的富文本同样要能画（代码块是等宽 span + 背景色 span） */
    @Test
    public void render_codeRichText_doesNotCrash() {
        MarkdownRenderer renderer = new MarkdownRenderer(
                0xFF2A2740, 0xFFE6EDF3, 0xFFA9A1C4, 0xFFF1EDFB, 0xFFB9A6FF, 0xFF322D45);
        CharSequence rich = renderer.renderRich("```java\npublic class A {}\n```\n", 520, 45f);
        Bitmap bmp = MessageCardRenderer.render(rich, 600, 45f, style(), "数字生命", "落款");
        assertNotNull(bmp);
        assertTrue(bmp.getHeight() > 100);
    }
}
