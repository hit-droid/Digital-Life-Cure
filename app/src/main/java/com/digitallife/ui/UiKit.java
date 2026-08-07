package com.digitallife.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.digitallife.R;

/**
 * 微信式 UI 共享构建工具：卡片/输入框/按钮/开关等统一样式。
 */
public final class UiKit {

    private UiKit() {
    }

    public static int dp(Context c, float v) {
        return Math.round(c.getResources().getDisplayMetrics().density * v);
    }

    public static int color(Context c, int res) {
        return c.getResources().getColor(res);
    }

    public static LinearLayout.LayoutParams lp(Context c, int extra) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (extra > 0) p.topMargin = dp(c, extra);
        return p;
    }

    /** 圆角卡片容器 */
    public static LinearLayout card(Context c, LinearLayout root, String title) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 16));
        box.setElevation(dp(c, 2));
        box.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(c, 12);
        root.addView(box, blp);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(15f);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setLetterSpacing(0.03f);
        t.setTextColor(color(c, R.color.brand));
        t.setPadding(0, 0, 0, dp(c, 10));
        box.addView(t, lp(c, 0));
        return box;
    }

    public static EditText input(Context c, LinearLayout root, String hint, String value) {
        EditText et = new EditText(c);
        et.setHint(hint);
        et.setHintTextColor(color(c, R.color.text_hint));
        if (value != null) et.setText(value);
        et.setTextSize(14f);
        et.setTextColor(color(c, R.color.text_primary));
        et.setBackgroundResource(R.drawable.bg_input);
        et.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        root.addView(et, lp(c, 8));
        LinearLayout.LayoutParams elp = (LinearLayout.LayoutParams) et.getLayoutParams();
        elp.height = dp(c, 44);
        return et;
    }

    public static Button button(Context c, LinearLayout root, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(14f);
        b.setTextColor(Color.WHITE);
        b.setBackgroundResource(R.drawable.bg_btn_primary);
        b.setAllCaps(false);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        pressScale(b);
        root.addView(b, lp(c, 8));
        LinearLayout.LayoutParams blp = (LinearLayout.LayoutParams) b.getLayoutParams();
        blp.height = dp(c, 44);
        return b;
    }

    /** 次级按钮（浅色底） */
    public static Button secondaryButton(Context c, LinearLayout root, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(13f);
        b.setTextColor(color(c, R.color.brand));
        b.setBackgroundResource(R.drawable.bg_btn_secondary);
        b.setAllCaps(false);
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        pressScale(b);
        root.addView(b, lp(c, 8));
        LinearLayout.LayoutParams slp = (LinearLayout.LayoutParams) b.getLayoutParams();
        slp.height = dp(c, 42);
        return b;
    }

    /** 按下缩放反馈（不消费点击事件） */
    public static void pressScale(View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    break;
            }
            return false;
        });
    }

    /** 给可点击容器套品牌色涟漪反馈（content 背景 + 圆角 mask） */
    public static void ripple(Context c, View v, Drawable content, float radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(c, radiusDp));
        mask.setColor(0xFF000000);
        android.graphics.drawable.RippleDrawable rd = new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(color(c, R.color.ripple)), content, mask);
        v.setBackground(rd);
        v.setClickable(true);
    }

    public static Switch switchRow(Context c, LinearLayout root, String label, boolean checked) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(14f);
        t.setTextColor(color(c, R.color.text_primary));
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(c);
        sw.setChecked(checked);
        row.addView(sw);
        root.addView(row, lp(c, 8));
        return sw;
    }

    /** 将常见 HTTP 错误转换为可操作的排查提示 */
    public static String friendlyApiError(String err, String base, String model) {
        String lower = err == null ? "" : err.toLowerCase();
        String hint;
        if (lower.contains("400")) {
            hint = "提示：HTTP 400 通常是「模型名不支持」或「请求参数不被服务端接受」。\n"
                    + "1) 确认模型名「" + model + "」在 " + base + " 上存在；"
                    + "下拉列表里是常用模型，可点选后重试。\n"
                    + "2) 若服务端是本地/自建网关，确认它实现的是 OpenAI 兼容 /chat/completions 接口。\n"
                    + "3) 部分服务需在请求体带 temperature 等参数，或改用流式(stream=true)再试。";
        } else if (lower.contains("401") || lower.contains("403")) {
            hint = "提示：HTTP 401/403 通常是 API Key 无效或无权限，请检查 Key 是否正确、余额是否充足。";
        } else if (lower.contains("404")) {
            hint = "提示：HTTP 404 通常是 Base URL 路径不对。确认地址以 /v1 结尾（如 https://api.deepseek.com/v1），且服务支持 /chat/completions。";
        } else if (lower.contains("timeout") || lower.contains("connect")) {
            hint = "提示：连接超时。确认 Base URL 可访问、网络畅通；若在国内环境访问海外服务，可能需要可达的镜像地址。";
        } else {
            hint = "提示：请检查 Base URL、API Key、模型名三项是否都正确，必要时清空对话记忆后重试。";
        }
        return hint;
    }
}
