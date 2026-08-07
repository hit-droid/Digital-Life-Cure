package com.digitallife.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
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
import com.digitallife.care.CareAI;
import com.digitallife.service.PetService;
import com.digitallife.util.ChatStore;
import com.digitallife.util.MemoryStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 统一聊天页（豆包/微信式）。
 * 会话绑定大脑：
 * - care：护理大脑（工具型，支持上传模型 zip）
 * - chat/model：对话大脑（走 AICore，与悬浮窗共享记忆）
 */
public class ChatActivity extends Activity {

    private static final String EXTRA_SESSION = "session_key";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_TYPE = "type";
    private static final String EXTRA_MODEL = "model_name";
    private static final long POLL_INTERVAL = 900;

    private static final int REQ_ATTACH = 3001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScrollView scroll;
    private LinearLayout listContainer;
    private EditText etInput;
    private Button btnAttach;
    private CareAI careAI;

    // 待发送附件（选文件后不立即发，与消息一起提交）
    private LinearLayout attachBar;
    private TextView tvAttachName;
    private String pendingFileName;
    private String pendingFilePath;

    private String sessionKey;
    private String title;
    private String type;
    private String modelName;
    private boolean isCare;

    private ChatStore chatStore;
    private MemoryStore memory;

    private TextView curAssistantBubble;   // care 流式回复气泡
    private TextView curToolBubble;        // care 工具过程卡片
    private String curAssistantText = "";
    private String curToolName = "";       // 工具卡片名称
    private String curToolFull = "";       // 工具卡片完整结果（点击展开/收起）
    private long lastMemoryTs;
    private long lastTsLabel = 0;

    private boolean thinking = false;

    private final Runnable poller = new Runnable() {
        @Override
        public void run() {
            if (!isCare) pollMemoryIncrements();
            handler.postDelayed(this, POLL_INTERVAL);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        chatStore = new ChatStore(this);
        memory = new MemoryStore(this);
        sessionKey = getIntent().getStringExtra(EXTRA_SESSION);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        type = getIntent().getStringExtra(EXTRA_TYPE);
        modelName = getIntent().getStringExtra(EXTRA_MODEL);
        if (sessionKey == null) sessionKey = ChatStore.SESSION_CARE;
        isCare = ChatStore.TYPE_CARE.equals(type);
        buildUi();
        restoreHistory();
        lastMemoryTs = System.currentTimeMillis();
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
        topBar.setElevation(dp(4));
        topBar.setPadding(dp(4), dp(12), dp(4), dp(12));

        ImageButton btnBack = iconButton(R.drawable.ic_back);
        btnBack.setContentDescription("返回");
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, btnLp(40, 40));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title == null || title.isEmpty() ? "对话" : title);
        tvTitle.setTextSize(17f);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClear = new Button(this);
        btnClear.setText("清空");
        btnClear.setTextSize(13f);
        btnClear.setTextColor(Color.WHITE);
        btnClear.setAllCaps(false);
        btnClear.setBackgroundResource(R.drawable.bg_btn_glass);
        btnClear.setPadding(dp(12), dp(4), dp(12), dp(4));
        UiKit.pressScale(btnClear);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        clp.setMargins(dp(4), 0, dp(4), 0);
        btnClear.setOnClickListener(v -> confirmClear());
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

        // ===== 底部输入栏（玻璃感容器） =====
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setBackgroundColor(getColorCompat(R.color.surface_glass));
        inputBar.setElevation(dp(10));
        inputBar.setPadding(dp(8), dp(6), dp(8), dp(8));

        // 待发送附件条（选文件后先暂存，与文字一起发送）
        attachBar = new LinearLayout(this);
        attachBar.setOrientation(LinearLayout.HORIZONTAL);
        attachBar.setGravity(Gravity.CENTER_VERTICAL);
        attachBar.setBackgroundColor(getColorCompat(R.color.card_bg));
        attachBar.setPadding(dp(12), dp(2), dp(12), dp(2));
        attachBar.setVisibility(View.GONE);
        tvAttachName = new TextView(this);
        tvAttachName.setTextSize(13f);
        tvAttachName.setTextColor(getColorCompat(R.color.text_primary));
        tvAttachName.setSingleLine(true);
        tvAttachName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        attachBar.addView(tvAttachName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tvRemove = new TextView(this);
        tvRemove.setText("✕");
        tvRemove.setTextSize(15f);
        tvRemove.setTextColor(getColorCompat(R.color.text_hint));
        tvRemove.setPadding(dp(10), dp(2), dp(2), dp(2));
        tvRemove.setOnClickListener(v -> clearPendingFile());
        attachBar.addView(tvRemove, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(attachBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (isCare) {
            btnAttach = new Button(this);
            btnAttach.setText("＋");
            btnAttach.setTextSize(18f);
            btnAttach.setAllCaps(false);
            btnAttach.setTextColor(getColorCompat(R.color.brand));
            btnAttach.setBackgroundResource(R.drawable.bg_btn_secondary);
            UiKit.pressScale(btnAttach);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(44), dp(44));
            alp.rightMargin = dp(6);
            btnAttach.setOnClickListener(v -> pickAttach());
            inputBar.addView(btnAttach, alp);
        }

        etInput = new EditText(this);
        etInput.setHint(isCare ? "向护理大脑提问，点 ＋ 可附带模型 zip…" : "说点什么…");
        etInput.setTextSize(15f);
        etInput.setInputType(InputType.TYPE_CLASS_TEXT);
        etInput.setBackgroundResource(R.drawable.bg_input);
        etInput.setPadding(dp(12), dp(6), dp(12), dp(6));
        etInput.setOnFocusChangeListener((v, has) -> v.setBackgroundResource(
                has ? R.drawable.bg_input_focused : R.drawable.bg_input));
        inputBar.addView(etInput, new LinearLayout.LayoutParams(0, dp(42), 1f));

        ImageButton btnSend = new ImageButton(this);
        btnSend.setImageResource(R.drawable.ic_send);
        btnSend.setBackgroundResource(R.drawable.bg_send);
        btnSend.setScaleType(ImageView.ScaleType.CENTER);
        btnSend.setPadding(dp(10), dp(10), dp(10), dp(10));
        btnSend.setElevation(dp(2));
        UiKit.pressScale(btnSend);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(44), dp(44));
        slp.leftMargin = dp(8);
        btnSend.setOnClickListener(v -> send());
        inputBar.addView(btnSend, slp);

        root.addView(inputBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    // ==================== 历史恢复 ====================

    private void restoreHistory() {
        List<ChatStore.StoredMsg> msgs = chatStore.getMessages(sessionKey, 100);
        if (msgs.isEmpty() && isCare) {
            appendAiBubble("你好，我是护理大脑。\n\n可以给我发模型 zip 压缩包（点左下角 ＋），我会自动解压体检、分析完整性、修复缺失文件，还能创作和编辑动作。");
            return;
        }
        if (msgs.isEmpty()) {
            if (ChatStore.TYPE_MODEL.equals(type) && modelName != null) {
                appendAiBubble("你好，我是「" + modelName + "」。\n我们单独开了一个小房间，跟我说说话吧。");
            } else {
                appendAiBubble("你好，开始我们的对话吧。");
            }
            return;
        }
        long lastTs = 0;
        for (ChatStore.StoredMsg m : msgs) {
            if ("user".equals(m.role)) {
                appendUserBubble(m.content);
            } else if ("tool".equals(m.role)) {
                markLastToolResult(m.toolCallId, null, m.content);
            } else if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                appendToolBubble(parseToolName(m.toolCalls), parseToolArgs(m.toolCalls));
            } else if (m.content != null && !m.content.isEmpty()) {
                appendAiBubble(m.content);
            }
            lastTs = Math.max(lastTs, m.timestamp);
        }
        lastMemoryTs = Math.max(lastTs, System.currentTimeMillis() - 3600_000L);
    }

    // ==================== 发送与分发 ====================

    private void send() {
        String text = etInput.getText().toString().trim();
        boolean hasFile = pendingFilePath != null && !pendingFilePath.isEmpty();
        if (text.isEmpty() && !hasFile) return;
        etInput.setText("");
        hideKeyboard();

        if (isCare && hasFile) {
            String display = pendingFileName == null || pendingFileName.isEmpty() ? "model.zip" : pendingFileName;
            String bubble = text.isEmpty() ? "📦 " + display : text + "\n📦 " + display;
            appendUserBubble(bubble);
            chatStore.addMessage(sessionKey, "user", bubble, null, null, System.currentTimeMillis());
            final String filePath = pendingFilePath;
            clearPendingFile();
            if (careAI == null) careAI = CareAI.getInstance(this);
            thinking = true;
            renderThinkingDot();
            careAI.sendMessageWithFile(text, display, filePath);
            scrollToBottom();
            return;
        }

        appendUserBubble(text);
        chatStore.addMessage(sessionKey, "user", text, null, null, System.currentTimeMillis());
        scrollToBottom();

        if (isCare) {
            if (careAI == null) careAI = CareAI.getInstance(this);
            thinking = true;
            renderThinkingDot();
            careAI.sendMessage(text);
        } else {
            if (ChatStore.TYPE_MODEL.equals(type) && modelName != null && !modelName.isEmpty()) {
                PetService svc = PetService.getInstance();
                if (svc != null) svc.switchToModelByName(modelName);
            }
            AICore ai = getAiCore();
            if (ai == null) {
                appendAiBubble("桌宠尚未启动，无法回复。请到「设置」Tab 启动桌宠。");
                chatStore.addMessage(sessionKey, "assistant", "桌宠尚未启动，无法回复。请到「设置」Tab 启动桌宠。", null, null, System.currentTimeMillis());
                scrollToBottom();
                return;
            }
            thinking = true;
            renderThinkingDot();
            ai.onUserSays(text);
        }
    }

    /** 轮询 MemoryStore：把 AICore 新产出的 assistant 回复同步进本会话 */
    private void pollMemoryIncrements() {
        if (listContainer == null) return;
        try {
            List<MemoryStore.Message> fresh = memory.getMessagesSince(lastMemoryTs);
            boolean added = false;
            for (MemoryStore.Message m : fresh) {
                if (!"assistant".equals(m.role)) continue;
                if (m.content == null || m.content.isEmpty()) continue;
                // 跳过自动化记录（情绪/行为描述）类短消息
                if (m.content.length() < 8 && !m.content.contains("（")) continue;
                chatStore.addMessage(sessionKey, "assistant", m.content, null, null, System.currentTimeMillis());
                hideThinkingDot();
                appendAiBubble(m.content);
                added = true;
                thinking = false;
            }
            if (added) scrollToBottom();
            for (MemoryStore.Message m : fresh) {
                if (m.timestamp > lastMemoryTs) lastMemoryTs = m.timestamp;
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== 护理大脑（care）流式 ====================

    private void setupCareListener() {
        if (careAI == null) careAI = CareAI.getInstance(this);
        careAI.setListener(new CareAI.CareListener() {
            @Override
            public void onDelta(String text) {
                runOnUiThread(() -> {
                    if (curAssistantBubble == null) {
                        hideThinkingDot();
                        curAssistantText = "";
                        curAssistantBubble = newTextViewBubble();
                        listContainer.addView(curAssistantBubble);
                    }
                    curAssistantText += text;
                    curAssistantBubble.setText(curAssistantText);
                    scrollToBottom();
                });
            }

            @Override
            public void onToolCall(String toolName, JSONObject args, String toolCallId) {
                runOnUiThread(() -> appendToolBubble(toolName,
                        args != null ? args.toString() : null));
            }

            @Override
            public void onToolResult(String toolName, boolean ok, String result) {
                runOnUiThread(() -> markLastToolResult(toolName, ok, result));
            }

            @Override
            public void onDone(String fullText) {
                runOnUiThread(() -> {
                    if (curAssistantBubble != null && !curAssistantText.isEmpty()) {
                        chatStore.addMessage(sessionKey, "assistant", curAssistantText,
                                null, null, System.currentTimeMillis());
                        curAssistantBubble = null;
                        curAssistantText = "";
                    } else if (fullText != null && !fullText.isEmpty()) {
                        chatStore.addMessage(sessionKey, "assistant", fullText,
                                null, null, System.currentTimeMillis());
                        hideThinkingDot();
                        appendAiBubble(fullText);
                    }
                    hideThinkingDot();
                    thinking = false;
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    hideThinkingDot();
                    appendAiBubble("出错了：" + error);
                    thinking = false;
                });
            }
        });
    }

    // ==================== 附件（护理大脑上传 zip） ====================

    private void pickAttach() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed"});
            startActivityForResult(i, REQ_ATTACH);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ATTACH && resultCode == RESULT_OK && data != null) {
            final Uri uri = data.getData();
            if (uri == null) return;
            // 先复制到缓存并暂存，等用户点发送时与文字一起提交
            new Thread(() -> {
                try {
                    String name = getFileName(uri);
                    File f = copyToCache(uri, name == null ? "model.zip" : name);
                    runOnUiThread(() -> showPendingFile(
                            name == null || name.isEmpty() ? "model.zip" : name,
                            f.getAbsolutePath()));
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                            "读取文件失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            }).start();
        }
    }

    /** 显示待发送附件条（暂存文件，等待与消息一起发送） */
    private void showPendingFile(String name, String path) {
        pendingFileName = name;
        pendingFilePath = path;
        tvAttachName.setText("📦 " + name + "（将随下一条消息一起发送）");
        attachBar.setVisibility(View.VISIBLE);
    }

    private void clearPendingFile() {
        pendingFileName = null;
        pendingFilePath = null;
        attachBar.setVisibility(View.GONE);
    }

    private String getFileName(Uri uri) {
        String name = null;
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        return name;
    }

    private File copyToCache(Uri uri, String name) throws java.io.IOException {
        File dir = new File(getCacheDir(), "uploads");
        if (!dir.exists()) dir.mkdirs();
        String safe = (name == null || name.isEmpty()) ? "model.zip" : name;
        File out = new File(dir, safe);
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        }
        return out;
    }

    // ==================== 气泡渲染 ====================

    /** 相邻消息间隔超过 5 分钟时插入居中的时间标签（iMessage 风格） */
    private void appendTimeDividerIfNeeded() {
        long now = System.currentTimeMillis();
        if (lastTsLabel != 0 && now - lastTsLabel < 5 * 60 * 1000L) return;
        lastTsLabel = now;
        TextView t = new TextView(this);
        t.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(now)));
        t.setTextSize(10f);
        t.setTextColor(getColorCompat(R.color.text_hint));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, dp(6));
        listContainer.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView newTextViewBubble() {
        TextView b = new TextView(this);
        b.setTextSize(15f);
        b.setTextColor(getColorCompat(R.color.text_primary));
        b.setLineSpacing(3f, 1f);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setMaxWidth(dp(260));
        b.setElevation(dp(2));
        b.setBackgroundResource(R.drawable.bg_bubble_ai);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return b;
    }

    private void appendUserBubble(String text) {
        appendTimeDividerIfNeeded();
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
        bubble.setElevation(dp(2));
        bubble.setBackgroundResource(R.drawable.bg_bubble_user);
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
        appendTimeDividerIfNeeded();
        TextView b = newTextViewBubble();
        b.setText(text);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listContainer.addView(b);
    }

    /** 工具过程卡片（完全可视化）：工具名 + 完整参数；结果回填后完整展示 */
    private void appendToolBubble(String toolName, String argsText) {
        hideThinkingDot();
        final TextView b = new TextView(this);
        b.setTextSize(12f);
        b.setTextColor(getColorCompat(R.color.text_primary));
        b.setLineSpacing(2f, 1f);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setElevation(dp(1));
        b.setBackgroundResource(R.drawable.bg_tool);
        String pretty = prettyJson(argsText);
        b.setText("🔧 正在调用工具：" + (toolName == null ? "…" : toolName)
                + "\n\n⚙ 参数：" + (pretty.isEmpty() ? "（无）" : pretty)
                + "\n\n⏳ 状态：执行中…");
        b.setTag(Boolean.TRUE);
        b.setOnClickListener(v -> toggleToolCard(b));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(24);
        listContainer.addView(b, lp);
        curToolBubble = b;
        curToolName = toolName == null ? "" : toolName;
        curToolFull = "";
        scrollToBottom();
    }

    /** 工具执行结果回填：完整结果直接展示，状态徽标标识成败 */
    private void markLastToolResult(String toolName, Boolean ok, String result) {
        if (curToolBubble != null) {
            String full = result == null ? "" : result.trim();
            curToolFull = full;
            String name = (toolName == null || toolName.isEmpty()) ? curToolName : toolName;
            if (!name.isEmpty()) curToolName = name;
            String status = ok == null ? "已完成" : (ok ? "✅ 成功" : "❌ 失败");
            StringBuilder text = new StringBuilder("🔧 ")
                    .append(curToolName.isEmpty() ? "工具" : curToolName)
                    .append("\n\n").append(status);
            if (full.isEmpty()) {
                text.append("\n\n（无返回内容）");
            } else {
                text.append("\n\n📋 结果：\n").append(full);
            }
            int statusColor = ok == null ? getColorCompat(R.color.text_secondary)
                    : (ok ? 0xFF2E7D32 : 0xFFC62828);
            SpannableString ss = new SpannableString(text.toString());
            int start = text.indexOf(status);
            ss.setSpan(new ForegroundColorSpan(statusColor), start, start + status.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            curToolBubble.setText(ss);
            curToolBubble.setTag(Boolean.TRUE);
        }
        curToolBubble = null;
    }

    /** 工具卡片点击：在完整内容与摘要之间切换（默认完全展开） */
    private void toggleToolCard(TextView b) {
        if (curToolFull == null || curToolFull.isEmpty()) return;
        boolean showingFull = Boolean.TRUE.equals(b.getTag());
        String name = curToolName.isEmpty() ? "工具" : curToolName;
        if (showingFull) {
            String brief = curToolFull.length() > 150
                    ? curToolFull.substring(0, 150) + "…" : curToolFull;
            b.setText("🔧 " + name + "（已折叠，点击展开完整结果）\n" + brief);
            b.setTag(Boolean.FALSE);
        } else {
            b.setText("🔧 " + name + "（点击收起）\n" + curToolFull);
            b.setTag(Boolean.TRUE);
        }
    }

    /** 把工具参数 JSON 美化排版后完整展示 */
    private String prettyJson(String s) {
        if (s == null || s.isEmpty()) return "";
        String t = s.trim();
        try {
            if (t.startsWith("{")) return new JSONObject(t).toString(2);
            if (t.startsWith("[")) return new org.json.JSONArray(t).toString(2);
        } catch (Exception ignored) {
        }
        return s;
    }

    private void renderThinkingDot() {
        hideThinkingDot();
        TextView b = newTextViewBubble();
        b.setText("……");
        b.setTag("thinking");
        listContainer.addView(b);
        scrollToBottom();
    }

    private void hideThinkingDot() {
        if (listContainer == null) return;
        for (int i = listContainer.getChildCount() - 1; i >= 0; i--) {
            View v = listContainer.getChildAt(i);
            if ("thinking".equals(v.getTag())) listContainer.removeViewAt(i);
        }
    }

    private String parseToolName(String toolCallsJson) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(toolCallsJson);
            if (arr.length() > 0) {
                JSONObject call = arr.optJSONObject(0);
                if (call != null && call.optJSONObject("function") != null) {
                    String n = call.optJSONObject("function").optString("name", "");
                    if (!n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {
        }
        return "tool";
    }

    private String parseToolArgs(String toolCallsJson) {
        try {
            org.json.JSONArray arr = new org.json.JSONArray(toolCallsJson);
            if (arr.length() > 0) {
                JSONObject call = arr.optJSONObject(0);
                if (call != null && call.optJSONObject("function") != null) {
                    return call.optJSONObject("function").optString("arguments", "");
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ==================== 其他 ====================

    private void confirmClear() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("清空对话")
                .setMessage("确定清空本会话的全部消息吗？")
                .setPositiveButton("清空", (d, w) -> {
                    chatStore.clearSession(sessionKey);
                    listContainer.removeAllViews();
                    curAssistantBubble = null;
                    curToolBubble = null;
                    curToolName = "";
                    curToolFull = "";
                    if (isCare && careAI != null) careAI.clearHistory();
                    thinking = false;
                    if (isCare) {
                        appendAiBubble("已清空。可以发消息或上传模型 zip 开始新的对话。");
                    } else {
                        appendAiBubble("已清空，开始新的对话吧。");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private AICore getAiCore() {
        PetService svc = PetService.getInstance();
        return svc != null ? svc.getAiCore() : null;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
    }

    private void scrollToBottom() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
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
    protected void onResume() {
        super.onResume();
        if (isCare) setupCareListener();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(poller);
    }
}
