package com.digitallife.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.R;
import com.digitallife.brain.AICore;
import com.digitallife.service.PetService;
import com.digitallife.util.MemoryStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 对话大脑聊天界面（豆包式）。
 * 与屏幕悬浮窗气泡使用同一份对话历史（MemoryStore），
 * 在此发送的消息会推给 AICore，并与悬浮窗内容互相可见。
 */
public class ChatActivity extends Activity {

    private static final long POLL_INTERVAL = 800;
    private static final long MAX_RENDERED = 60;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            renderFromMemory();
            handler.postDelayed(this, POLL_INTERVAL);
        }
    };

    private ScrollView scroll;
    private LinearLayout listContainer;
    private EditText etInput;
    private MemoryStore memory;
    private long lastTimestampMs;
    private boolean thinking = false;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (thinking) renderThinking();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        memory = new MemoryStore(this);
        buildUi();
        renderFromMemory();
        handler.postDelayed(poller, POLL_INTERVAL);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.page_bg));

        // ===== 顶栏 =====
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_top_bar);
        topBar.setPadding(dp(6), dp(12), dp(6), dp(12));

        ImageButton btnBack = iconButton(R.drawable.ic_back);
        btnBack.setContentDescription("返回");
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, btnLp(40, 40));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("对话大脑");
        tvTitle.setTextSize(17f);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClear = new Button(this);
        btnClear.setText("清空");
        btnClear.setTextSize(13f);
        btnClear.setTextColor(Color.WHITE);
        btnClear.setAllCaps(false);
        btnClear.setBackgroundResource(R.drawable.bg_btn_primary);
        btnClear.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        clp.setMargins(dp(4), 0, dp(4), 0);
        btnClear.setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("清空对话")
                    .setMessage("确定清空全部对话历史吗？（桌宠记忆不受影响）")
                    .setPositiveButton("清空", (d, w) -> {
                        memory.clearContext();
                        listContainer.removeAllViews();
                        lastTimestampMs = 0;
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        topBar.addView(btnClear, clp);

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ===== 消息列表 =====
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(12), dp(8), dp(12), dp(8));
        scroll.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ===== 底部输入栏 =====
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setBackgroundColor(Color.WHITE);
        inputBar.setPadding(dp(8), dp(6), dp(8), dp(6));

        etInput = new EditText(this);
        etInput.setHint("与你的数字生命聊聊…");
        etInput.setTextSize(15f);
        etInput.setInputType(InputType.TYPE_CLASS_TEXT);
        etInput.setBackgroundResource(R.drawable.bg_input);
        etInput.setPadding(dp(12), dp(6), dp(12), dp(6));
        inputBar.addView(etInput, new LinearLayout.LayoutParams(0,
                dp(42), 1f));

        ImageButton btnSend = new ImageButton(this);
        btnSend.setImageResource(R.drawable.ic_send);
        btnSend.setBackgroundResource(R.drawable.bg_btn_primary);
        btnSend.setScaleType(ImageView.ScaleType.CENTER);
        btnSend.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(44), dp(44));
        slp.leftMargin = dp(8);
        btnSend.setOnClickListener(v -> send());
        inputBar.addView(btnSend, slp);

        root.addView(inputBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void send() {
        String text = etInput.getText().toString().trim();
        if (text.isEmpty()) return;
        etInput.setText("");
        hideKeyboard();
        appendUserBubble(text);
        scrollToBottom();
        renderFromMemory();
        AICore ai = getAiCore();
        if (ai != null) {
            ai.onUserSays(text);
            thinking = true;
            renderThinking();
            handler.postDelayed(ticker, 300);
        } else {
            appendAiBubble("桌宠尚未启动，无法回复。请先回到主界面启动桌宠。");
            scrollToBottom();
        }
    }

    /** 从 MemoryStore 增量渲染（与悬浮窗共享同一份历史） */
    private void renderFromMemory() {
        if (listContainer == null) return;
        boolean changed = false;
        List<MemoryStore.Message> msgs = memory.getRecentMessages((int) MAX_RENDERED);
        int childIndex = 0;
        boolean foundGap = false;

        for (MemoryStore.Message m : msgs) {
            boolean isUser = "user".equals(m.role);
            if (childIndex >= listContainer.getChildCount()) {
                addBubble(isUser, m.content, m.timestamp);
                changed = true;
                childIndex++;
                continue;
            }
            View v = listContainer.getChildAt(childIndex);
            if (v instanceof TextView && v.getTag() != null) {
                Object[] tag = (Object[]) v.getTag();
                boolean tagUser = (Boolean) tag[0];
                String tagContent = (String) tag[1];
                if (tagUser == isUser && tagContent.equals(m.content)) {
                    childIndex++;
                    continue;
                }
                // 历史里被修改或清空过 → 全部重建
                foundGap = true;
                break;
            }
            foundGap = true;
            break;
        }

        if (foundGap) {
            listContainer.removeAllViews();
            childIndex = 0;
            for (MemoryStore.Message m : msgs) {
                addBubble("user".equals(m.role), m.content, m.timestamp);
                childIndex++;
            }
            changed = true;
        }

        if (changed) scrollToBottom();
    }

    private void addBubble(boolean isUser, String content, long ts) {
        if (isUser) {
            appendUserBubble(content);
        } else {
            appendAiBubble(content);
        }
        maybeTimestamp(ts);
    }

    private void appendUserBubble(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);

        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextSize(15f);
        bubble.setTextColor(Color.WHITE);
        bubble.setLineSpacing(3f, 1f);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        bubble.setMaxWidth(dp(260));
        bubble.setBackgroundResource(R.drawable.bg_bubble_user);
        bubble.setTag(new Object[]{true, text});

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        row.addView(bubble, lp);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(2);
        listContainer.addView(row, rlp);
    }

    private void appendAiBubble(String text) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.START);

        TextView bubble = new TextView(this);
        bubble.setText(text);
        bubble.setTextSize(15f);
        bubble.setTextColor(getColorCompat(R.color.text_primary));
        bubble.setLineSpacing(3f, 1f);
        bubble.setPadding(dp(12), dp(8), dp(12), dp(8));
        bubble.setMaxWidth(dp(260));
        bubble.setBackgroundResource(R.drawable.bg_bubble_ai);
        bubble.setTag(new Object[]{false, text});

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        row.addView(bubble, lp);

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(2);
        listContainer.addView(row, rlp);
    }

    private void renderThinking() {
        // 顶部再刷新一次最新 assistant（流式增量已由 memory 覆盖）
        renderFromMemory();
    }

    private void maybeTimestamp(long ts) {
        if (ts <= 0) return;
        if (lastTimestampMs == 0 || ts - lastTimestampMs > 2 * 60 * 1000L) {
            addTimestamp(ts);
        }
        lastTimestampMs = ts;
    }

    private void addTimestamp(long ts) {
        TextView t = new TextView(this);
        String time = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
        t.setText(time);
        t.setTextSize(10f);
        t.setTextColor(getColorCompat(R.color.text_secondary));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(6), 0, dp(2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listContainer.addView(t, lp);
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private AICore getAiCore() {
        PetService svc = PetService.getInstance();
        return svc == null ? null : svc.getAiCore();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
    }

    private ImageButton iconButton(int res) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        return b;
    }

    private LinearLayout.LayoutParams btnLp(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(h));
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    private int getColorCompat(int res) {
        return getResources().getColor(res);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(poller);
        handler.removeCallbacks(ticker);
    }
}
