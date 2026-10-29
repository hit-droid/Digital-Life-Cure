package com.digitallife.ui.chat;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;

/**
 * v1.189.0：把一条消息渲染成「可分享的长图」。
 *
 * <p>聊天气泡里的内容其实已经是富文本（{@link com.digitallife.ui.MarkdownRenderer}
 * 的产物：表格是位图 span、代码块有配色、引用/加粗都有 span），所以这里不重新
 * 解析 Markdown，直接拿那份 {@code CharSequence} 用 {@link StaticLayout} 画一遍，
 * 表格位图与代码配色都能原样保留。</p>
 *
 * <p>只做纯绘制，不碰 Android 之外的东西，方便 Robolectric 下出图自查。</p>
 */
public final class MessageCardRenderer {

    /** 整张图的最大高度（px）。超长消息按行截断，避免一张图十几 MB 分享失败 */
    public static final int MAX_CARD_HEIGHT_PX = 8000;

    /** 卡片配色（全部来自深色主题） */
    public static final class Style {
        public final int pageBg;
        public final int cardBg;
        public final int textColor;
        public final int mutedColor;
        public final int accentColor;
        public final float density;

        public Style(int pageBg, int cardBg, int textColor, int mutedColor,
                     int accentColor, float density) {
            this.pageBg = pageBg;
            this.cardBg = cardBg;
            this.textColor = textColor;
            this.mutedColor = mutedColor;
            this.accentColor = accentColor;
            this.density = density <= 0 ? 1f : density;
        }
    }

    private MessageCardRenderer() {
    }

    /**
     * 渲染一张消息长图。
     *
     * @param body          已渲染好的富文本正文（可含表格位图 span）
     * @param cardWidthPx   卡片宽度（不含页面外边距）
     * @param textSizePx    正文字号（px）
     * @param style         配色
     * @param header        顶部标题（如角色名 + 时间），可为 null
     * @param footer        底部落款，可为 null
     * @return 位图；body 为空时返回 null
     */
    public static Bitmap render(CharSequence body, int cardWidthPx, float textSizePx,
                                Style style, String header, String footer) {
        if (body == null || body.length() == 0) return null;
        if (cardWidthPx <= 0 || textSizePx <= 0) return null;

        float density = style.density;
        int margin = Math.round(14 * density);
        int pad = Math.round(18 * density);
        int radius = Math.round(18 * density);
        int gap = Math.round(10 * density);

        int contentWidth = Math.max(1, cardWidthPx - pad * 2);
        TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setTextSize(textSizePx);
        bodyPaint.setColor(style.textColor);

        TextPaint headerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        headerPaint.setTextSize(textSizePx * 0.82f);
        headerPaint.setColor(style.accentColor);
        TextPaint footerPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        footerPaint.setTextSize(textSizePx * 0.72f);
        footerPaint.setColor(style.mutedColor);

        int headerH = (header == null || header.isEmpty())
                ? 0 : Math.round(headerPaint.getFontMetrics().descent
                - headerPaint.getFontMetrics().ascent) + gap;
        int footerH = (footer == null || footer.isEmpty())
                ? 0 : Math.round(footerPaint.getFontMetrics().descent
                - footerPaint.getFontMetrics().ascent) + gap;

        // 正文可用高度：整图上限扣掉页边距、卡片内边距、标题与落款
        int maxBodyHeight = Math.max(64, MAX_CARD_HEIGHT_PX - margin * 2 - pad * 2
                - headerH - footerH);

        CharSequence shown = body;
        StaticLayout layout = layout(shown, bodyPaint, contentWidth);
        // 超长消息按行边界截断：保留原有 span（表格位图不受影响），末尾补一行提示
        if (layout.getHeight() > maxBodyHeight) {
            int line = layout.getLineForVertical(maxBodyHeight);
            int cut = layout.getLineEnd(line);
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(body, 0, Math.min(Math.max(0, cut), body.length()));
            sb.append("\n…（内容过长，已截断）");
            shown = sb;
            layout = layout(shown, bodyPaint, contentWidth);
        }
        // 还超高（例如整段只有一行超长词）就按比例收；切的是 Spannable 子串，span 仍在
        for (int guard = 0; guard < 12 && layout.getHeight() > maxBodyHeight; guard++) {
            int keep = (int) ((double) shown.length() * maxBodyHeight / layout.getHeight());
            if (keep >= shown.length()) keep = shown.length() - 1;
            if (keep <= 0) break;
            SpannableStringBuilder sb = new SpannableStringBuilder();
            sb.append(shown, 0, keep);
            sb.append("…");
            shown = sb;
            layout = layout(shown, bodyPaint, contentWidth);
        }

        int cardH = pad + headerH + layout.getHeight() + footerH + pad;
        int bitmapW = cardWidthPx + margin * 2;
        int bitmapH = cardH + margin * 2;

        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, bitmapW), Math.max(1, bitmapH),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(style.pageBg);

        Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardPaint.setColor(style.cardBg);
        RectF cardRect = new RectF(margin, margin, margin + cardWidthPx, margin + cardH);
        canvas.drawRoundRect(cardRect, radius, radius, cardPaint);

        int y = margin + pad;
        if (headerH > 0) {
            canvas.drawText(header, margin + pad,
                    y + headerPaint.getFontMetrics().descent, headerPaint);
            y += headerH;
        }
        canvas.save();
        canvas.translate(margin + pad, y);
        layout.draw(canvas);
        canvas.restore();
        if (footerH > 0) {
            canvas.drawText(footer, margin + pad,
                    margin + cardH - pad + footerPaint.getFontMetrics().descent, footerPaint);
        }
        return bitmap;
    }

    private static StaticLayout layout(CharSequence text, TextPaint paint, int width) {
        // 旧构造器（API 1+）没有 Builder 的 API 23 限制，minSdk 21 也能用
        return new StaticLayout(text, paint, Math.max(1, width),
                Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false);
    }
}
