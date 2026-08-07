package com.digitallife.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.R;
import com.digitallife.util.ChatStore;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 微信式「对话」Tab：会话列表。
 * 支持新建对话、删除会话、点击进入聊天；护理大脑会话固定置顶。
 */
public class ConversationTabView extends LinearLayout {

    public interface Listener {
        void onOpenSession(String sessionKey, String title, String type, String modelName);
    }

    private final Activity activity;
    private final Listener listener;
    private final ChatStore chatStore;
    private LinearLayout listContainer;

    public ConversationTabView(Activity activity, Listener listener) {
        super(activity);
        this.activity = activity;
        this.listener = listener;
        this.chatStore = new ChatStore(activity);
        setOrientation(LinearLayout.VERTICAL);
        setBackgroundColor(UiKit.color(activity, R.color.page_bg));
        buildUi();
    }

    private void buildUi() {
        // 新建会话入口
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 8),
                UiKit.dp(activity, 12), UiKit.dp(activity, 4));

        TextView tip = new TextView(activity);
        tip.setText("对话列表");
        tip.setTextSize(13f);
        tip.setTextColor(UiKit.color(activity, R.color.text_secondary));
        bar.addView(tip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button btnNew = new Button(activity);
        btnNew.setText("＋ 新建对话");
        btnNew.setTextSize(13f);
        btnNew.setTextColor(Color.WHITE);
        btnNew.setAllCaps(false);
        btnNew.setBackgroundResource(R.drawable.bg_btn_primary);
        btnNew.setPadding(UiKit.dp(activity, 14), UiKit.dp(activity, 4),
                UiKit.dp(activity, 14), UiKit.dp(activity, 4));
        btnNew.setOnClickListener(v -> showNewSessionDialog());
        bar.addView(btnNew);
        addView(bar, UiKit.lp(activity, 0));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 6),
                UiKit.dp(activity, 12), UiKit.dp(activity, 12));
        scroll.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** 刷新会话列表（在切换 Tab / onResume 时调用） */
    public void refresh() {
        if (listContainer == null) return;
        listContainer.removeAllViews();
        // 护理大脑会话始终存在并置顶
        chatStore.ensureSession(ChatStore.SESSION_CARE, "护理大脑", ChatStore.TYPE_CARE, "care", null);
        List<ChatStore.SessionInfo> sessions = chatStore.getSessions();
        if (sessions.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText("还没有对话，点右上角「新建对话」开始。\n或打开通讯录，与某个模型单独聊聊。");
            empty.setTextSize(13f);
            empty.setTextColor(UiKit.color(activity, R.color.text_secondary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UiKit.dp(activity, 40), 0, 0);
            listContainer.addView(empty);
            return;
        }
        for (ChatStore.SessionInfo s : sessions) {
            listContainer.addView(buildSessionCard(s));
        }
    }

    private View buildSessionCard(ChatStore.SessionInfo s) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 10),
                UiKit.dp(activity, 8), UiKit.dp(activity, 10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = UiKit.dp(activity, 8);
        card.setLayoutParams(clp);

        // 头像（色块 + 首字/类型图标）
        TextView avatar = new TextView(activity);
        avatar.setTextSize(20f);
        avatar.setTypeface(Typeface.DEFAULT_BOLD);
        avatar.setTextColor(Color.WHITE);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(makeAvatarBg(s));
        avatar.setText(avatarChar(s));
        LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(
                UiKit.dp(activity, 44), UiKit.dp(activity, 44));
        card.addView(avatar, avLp);

        // 中间信息列
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(UiKit.dp(activity, 10), 0, 0, 0);

        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(s.title == null || s.title.isEmpty() ? "未命名对话" : s.title);
        title.setTextSize(15f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(UiKit.color(activity, R.color.text_primary));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView tag = new TextView(activity);
        tag.setText(typeLabel(s.type));
        tag.setTextSize(10f);
        tag.setTextColor(typeColor(s.type));
        tag.setBackground(typeTagBg(typeColor(s.type)));
        tag.setPadding(UiKit.dp(activity, 6), UiKit.dp(activity, 1),
                UiKit.dp(activity, 6), UiKit.dp(activity, 1));
        titleRow.addView(tag);
        info.addView(titleRow);

        ChatStore.StoredMsg last = chatStore.getLastMessage(s.id);
        TextView lastTv = new TextView(activity);
        if (last != null && last.content != null && !last.content.isEmpty()) {
            lastTv.setText("assistant".equals(last.role) ? "她：" + last.content : "我：" + last.content);
        } else {
            lastTv.setText("开始一段对话吧…");
        }
        lastTv.setTextSize(13f);
        lastTv.setTextColor(UiKit.color(activity, R.color.text_secondary));
        lastTv.setMaxLines(1);
        info.addView(lastTv, UiKit.lp(activity, 2));
        card.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // 时间 + 删除
        LinearLayout right = new LinearLayout(activity);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.CENTER_VERTICAL);

        TextView time = new TextView(activity);
        time.setText(formatTime(s.updatedAt));
        time.setTextSize(10f);
        time.setTextColor(UiKit.color(activity, R.color.text_secondary));
        time.setGravity(Gravity.END);
        right.addView(time);

        Button btnDel = new Button(activity);
        btnDel.setText("删除");
        btnDel.setTextSize(11f);
        btnDel.setAllCaps(false);
        btnDel.setTextColor(Color.rgb(200, 80, 80));
        btnDel.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnDel.setPadding(UiKit.dp(activity, 10), 0, UiKit.dp(activity, 10), 0);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(activity, 30));
        dlp.topMargin = UiKit.dp(activity, 4);
        btnDel.setOnClickListener(v -> confirmDelete(s));
        right.addView(btnDel, dlp);
        card.addView(right);

        card.setOnClickListener(v -> {
            if (listener != null) {
                listener.onOpenSession(s.id, s.title, s.type, s.modelName);
            }
        });
        return card;
    }

    private void showNewSessionDialog() {
        final EditText et = new EditText(activity);
        et.setHint("给对话起个名字（如：关于今天的心情）");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        new android.app.AlertDialog.Builder(activity)
                .setTitle("新建对话")
                .setView(et)
                .setPositiveButton("创建", (d, w) -> {
                    String title = et.getText().toString().trim();
                    if (title.isEmpty()) title = "新对话";
                    String id = chatStore.createSession(title, ChatStore.TYPE_CHAT, "chat", null);
                    if (listener != null) listener.onOpenSession(id, title, ChatStore.TYPE_CHAT, null);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(ChatStore.SessionInfo s) {
        if (ChatStore.SESSION_CARE.equals(s.id)) {
            Toast.makeText(activity, "护理大脑会话是系统内置的，不能删除。可清空其对话。", Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(activity)
                .setTitle("删除会话")
                .setMessage("确定删除「" + s.title + "」及其全部消息吗？")
                .setPositiveButton("删除", (d, w) -> {
                    chatStore.deleteSession(s.id);
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String typeLabel(String type) {
        if (ChatStore.TYPE_CARE.equals(type)) return "护理";
        if (ChatStore.TYPE_MODEL.equals(type)) return "模型";
        return "对话";
    }

    private int typeColor(String type) {
        if (ChatStore.TYPE_CARE.equals(type)) return Color.rgb(230, 150, 60);
        if (ChatStore.TYPE_MODEL.equals(type)) return Color.rgb(90, 160, 100);
        return Color.rgb(80, 150, 220);
    }

    private String avatarChar(ChatStore.SessionInfo s) {
        if (ChatStore.TYPE_CARE.equals(s.type)) return "护";
        if (s.title != null && !s.title.isEmpty()) return s.title.substring(0, 1);
        return "话";
    }

    private android.graphics.drawable.GradientDrawable makeAvatarBg(ChatStore.SessionInfo s) {
        int color;
        if (ChatStore.TYPE_CARE.equals(s.type)) {
            color = Color.rgb(220, 150, 70);
        } else if (ChatStore.TYPE_MODEL.equals(s.type)) {
            color = Color.rgb(90, 170, 110);
        } else {
            color = Color.rgb(100, 150, 220);
        }
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(UiKit.dp(activity, 10));
        g.setColor(color);
        return g;
    }

    private android.graphics.drawable.GradientDrawable typeTagBg(int color) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(UiKit.dp(activity, 8));
        g.setColor(Color.argb(30, Color.red(color), Color.green(color), Color.blue(color)));
        g.setStroke(1, color);
        return g;
    }

    private String formatTime(long ts) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(new Date(ts));
        Calendar now = Calendar.getInstance();
        SimpleDateFormat fmt;
        if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
            fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
        } else {
            fmt = new SimpleDateFormat("MM-dd", Locale.getDefault());
        }
        return fmt.format(new Date(ts));
    }
}
