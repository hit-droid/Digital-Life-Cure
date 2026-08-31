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
import android.widget.ImageView;
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
        setBackgroundColor(UiKit.color(activity, R.color.operit_bg));
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
        tip.setText("会话列表");
        tip.setTextSize(13f);
        tip.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
        bar.addView(tip, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button btnNew = new Button(activity);
        btnNew.setHapticFeedbackEnabled(true);   // 自动生成：haptic
        btnNew.setContentDescription("新建");   // 自动生成：a11y
        btnNew.setText("＋ 新建会话");
        btnNew.setTextSize(13f);
        btnNew.setTextColor(Color.WHITE);
        btnNew.setAllCaps(false);
        btnNew.setBackgroundResource(R.drawable.bg_btn_primary);
        btnNew.setPadding(UiKit.dp(activity, 14), 0,
                UiKit.dp(activity, 14), 0);
        UiKit.pressScale(btnNew);
        btnNew.setOnClickListener(v -> showNewSessionDialog());
        bar.addView(btnNew, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(activity, 34)));
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
            LinearLayout emptyBox = new LinearLayout(activity);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER);
            emptyBox.setPadding(0, UiKit.dp(activity, 56), 0, 0);

            ImageView emptyIcon = new ImageView(activity);
            emptyIcon.setImageResource(R.drawable.ic_empty);
            emptyIcon.setAlpha(0.9f);
            emptyBox.addView(emptyIcon);

            TextView empty = new TextView(activity);
            empty.setText("还没有对话\n点右上角「＋ 新建对话」开始\n或打开通讯录，与某个模型单独聊聊");
            empty.setTextSize(13f);
            empty.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
            empty.setGravity(Gravity.CENTER);
            empty.setLineSpacing(4f, 1f);
            empty.setPadding(0, UiKit.dp(activity, 14), 0, 0);
            emptyBox.addView(empty);
            listContainer.addView(emptyBox);
            return;
        }
        for (int i = 0; i < sessions.size(); i++) {
            View card = buildSessionCard(sessions.get(i));
            card.setAlpha(0f);
            card.setTranslationY(UiKit.dp(activity, 10));
            card.animate().alpha(1f).translationY(0f)
                    .setDuration(220).setStartDelay(i * 45L).start();
            listContainer.addView(card);
        }
    }

    private View buildSessionCard(ChatStore.SessionInfo s) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setElevation(UiKit.dp(activity, 2));
        card.setPadding(UiKit.dp(activity, 12), UiKit.dp(activity, 10),
                UiKit.dp(activity, 8), UiKit.dp(activity, 10));
        UiKit.ripple(activity, card, activity.getDrawable(R.drawable.bg_card), 20);
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
        title.setTextColor(UiKit.color(activity, R.color.operit_text_primary));
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
        lastTv.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
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
        time.setTextColor(UiKit.color(activity, R.color.operit_text_secondary));
        time.setGravity(Gravity.END);
        right.addView(time);

        Button btnDel = new Button(activity);
        btnDel.setContentDescription("btnDel");   // 自动生成：a11y
        btnDel.setText("删除");
        btnDel.setTextSize(11f);
        btnDel.setAllCaps(false);
        btnDel.setTextColor(UiKit.color(activity, R.color.danger));
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
        card.setOnLongClickListener(v -> {
            showRenameDialog(s);
            return true;
        });
        return card;
    }

    private void showRenameDialog(ChatStore.SessionInfo s) {
        if (ChatStore.SESSION_CARE.equals(s.id)) {
            Toast.makeText(activity, "护理大脑会话是系统内置的，不能改名。", Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText et = new EditText(activity);
        et.setText(s.title == null || "未命名对话".equals(s.title) ? "" : s.title);
        et.setSelection(et.getText().length());
        new android.app.AlertDialog.Builder(activity)
                .setTitle("重命名会话")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String title = et.getText().toString().trim();
                    if (title.isEmpty()) {
                        Toast.makeText(activity, "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (chatStore.renameSession(s.id, title)) {
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showNewSessionDialog() {
        final EditText et = new EditText(activity);
        et.setHint("给会话起个名字（如：关于今天的心情）");
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        final android.widget.RadioButton rbChat = new android.widget.RadioButton(activity);
        rbChat.setText("对话大脑（日常聊天）");
        rbChat.setChecked(true);
        final android.widget.RadioButton rbCare = new android.widget.RadioButton(activity);
        rbCare.setText("护理大脑（模型体检 / 动作创作）");
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(UiKit.dp(activity, 20), UiKit.dp(activity, 4),
                UiKit.dp(activity, 20), 0);
        body.addView(et);
        body.addView(rbChat, UiKit.lp(activity, 0));
        body.addView(rbCare, UiKit.lp(activity, 0));
        new android.app.AlertDialog.Builder(activity)
                .setTitle("新建会话")
                .setView(body)
                .setPositiveButton("创建", (d, w) -> {
                    String title = et.getText().toString().trim();
                    if (title.isEmpty()) title = rbCare.isChecked() ? "护理会话" : "新对话";
                    String type = rbCare.isChecked() ? ChatStore.TYPE_CARE : ChatStore.TYPE_CHAT;
                    String id = chatStore.createSession(title, type,
                            rbCare.isChecked() ? "care" : "chat", null);
                    if (listener != null) listener.onOpenSession(id, title, type, null);
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
        if (ChatStore.TYPE_CARE.equals(type)) return UiKit.color(activity, R.color.tag_care);
        if (ChatStore.TYPE_MODEL.equals(type)) return UiKit.color(activity, R.color.tag_model);
        return UiKit.color(activity, R.color.tag_chat);
    }

    private String avatarChar(ChatStore.SessionInfo s) {
        if (ChatStore.TYPE_CARE.equals(s.type)) return "护";
        if (s.title != null && !s.title.isEmpty()) return s.title.substring(0, 1);
        return "话";
    }

    private android.graphics.drawable.GradientDrawable makeAvatarBg(ChatStore.SessionInfo s) {
        int color;
        if (ChatStore.TYPE_CARE.equals(s.type)) {
            color = UiKit.color(activity, R.color.tag_care);
        } else if (ChatStore.TYPE_MODEL.equals(s.type)) {
            color = UiKit.color(activity, R.color.tag_model);
        } else {
            color = UiKit.color(activity, R.color.tag_chat);
        }
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(UiKit.dp(activity, 12));
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
