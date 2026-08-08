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
import com.digitallife.brain.LLMClient;
import com.digitallife.care.CareAI;
import com.digitallife.service.PetService;
import com.digitallife.util.ApiManager;
import com.digitallife.util.ApiProfile;
import com.digitallife.util.ChatStore;
import com.digitallife.util.Settings;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
    private static final int REQ_ATTACH = 3001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ScrollView scroll;
    /** 用户向上翻阅历史时暂停自动滚底，回到底部后恢复 */
    private boolean userScrolledAway = false;
    private android.view.ViewTreeObserver.OnScrollChangedListener scrollWatcher;
    private LinearLayout listContainer;
    private EditText etInput;
    private ImageButton btnAttach;
    private ImageButton btnSend;
    private Button btnModel;
    private CareAI careAI;
    private CareAI.CareListener careListener;
    private LLMClient llm;
    private MarkdownRenderer mdRenderer;

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

    private TextView curAssistantBubble;   // care 流式回复气泡
    private TextView curToolBubble;        // care 工具过程卡片
    private String curAssistantText = "";
    private String curToolName = "";       // 工具卡片名称
    private String curToolFull = "";       // 工具卡片完整结果（点击展开/收起）
    private long lastTsLabel = 0;

    private boolean thinking = false;
    /** 用户主动点击“停止生成”后吞掉取消触发的 onDone/onError */
    private boolean aborting = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        chatStore = new ChatStore(this);
        sessionKey = getIntent().getStringExtra(EXTRA_SESSION);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        type = getIntent().getStringExtra(EXTRA_TYPE);
        modelName = getIntent().getStringExtra(EXTRA_MODEL);
        if (sessionKey == null) sessionKey = ChatStore.SESSION_CARE;
        isCare = ChatStore.TYPE_CARE.equals(type);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        mdRenderer = new MarkdownRenderer(
                getColorCompat(R.color.code_bg),
                getColorCompat(R.color.text_primary),
                getColorCompat(R.color.text_secondary),
                getColorCompat(R.color.text_primary),
                getColorCompat(R.color.brand));
        restoreHistory();
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
        topBar.setPadding(dp(4), statusBarHeight() + dp(8), dp(4), dp(12));

        ImageButton btnBack = iconButton(R.drawable.ic_back);
        btnBack.setContentDescription("返回");
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, btnLp(40, 40));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title == null || title.isEmpty() ? "对话" : title);
        tvTitle.setTextSize(18f);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnModel = new Button(this);
        btnModel.setTextSize(12f);
        btnModel.setTextColor(Color.WHITE);
        btnModel.setAllCaps(false);
        btnModel.setMaxWidth(dp(150));
        btnModel.setMaxLines(1);
        btnModel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        btnModel.setBackgroundResource(R.drawable.bg_btn_glass);
        btnModel.setPadding(dp(10), dp(4), dp(10), dp(4));
        UiKit.pressScale(btnModel);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        mlp.setMargins(dp(4), 0, dp(2), 0);
        btnModel.setOnClickListener(v -> switchModel());
        topBar.addView(btnModel, mlp);

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
        scrollWatcher = () -> {
            View content = scroll.getChildAt(0);
            if (content == null) return;
            int bottomGap = content.getBottom() - (scroll.getScrollY() + scroll.getHeight());
            userScrolledAway = bottomGap > dp(140);
        };
        scroll.getViewTreeObserver().addOnScrollChangedListener(scrollWatcher);
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
            btnAttach = new ImageButton(this);
            btnAttach.setImageResource(R.drawable.ic_attach);
            btnAttach.setColorFilter(getColorCompat(R.color.brand));
            btnAttach.setBackgroundResource(R.drawable.bg_btn_secondary);
            btnAttach.setScaleType(ImageView.ScaleType.CENTER);
            btnAttach.setPadding(dp(10), dp(10), dp(10), dp(10));
            UiKit.pressScale(btnAttach);
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(dp(44), dp(44));
            alp.rightMargin = dp(6);
            btnAttach.setOnClickListener(v -> pickAttach());
            inputBar.addView(btnAttach, alp);
        }

        etInput = new EditText(this);
        etInput.setHint(isCare ? "向护理大脑提问，点 ＋ 可附带模型 zip…"
                : "说点什么… ＋ 可附带文件");
        etInput.setTextSize(15f);
        etInput.setInputType(InputType.TYPE_CLASS_TEXT);
        etInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        etInput.setSingleLine(true);
        etInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                send();
                return true;
            }
            return false;
        });
        etInput.setBackgroundResource(R.drawable.bg_input);
        etInput.setPadding(dp(12), dp(6), dp(12), dp(6));
        etInput.setOnFocusChangeListener((v, has) -> v.setBackgroundResource(
                has ? R.drawable.bg_input_focused : R.drawable.bg_input));
        inputBar.addView(etInput, new LinearLayout.LayoutParams(0, dp(42), 1f));

        ImageButton btnSendView = new ImageButton(this);
        btnSend = btnSendView;
        btnSend.setImageResource(R.drawable.ic_send);
        btnSend.setBackgroundResource(R.drawable.bg_send);
        btnSend.setScaleType(ImageView.ScaleType.CENTER);
        btnSend.setPadding(dp(10), dp(10), dp(10), dp(10));
        btnSend.setElevation(dp(2));
        UiKit.pressScale(btnSend);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(44), dp(44));
        slp.leftMargin = dp(8);
        btnSend.setOnClickListener(v -> {
            if (thinking) {
                stopGenerating();
            } else {
                send();
            }
        });
        inputBar.addView(btnSend, slp);

        root.addView(inputBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        refreshModelChip();
    }

    // ==================== 历史恢复 ====================

    private void restoreHistory() {
        List<ChatStore.StoredMsg> msgs = chatStore.getMessages(sessionKey, 100);
        if (msgs.isEmpty() && isCare) {
            appendAiBubble("你好，我是护理大脑。\n\n可以给我发模型 zip 压缩包（点左下角 ＋），我会自动解压体检、分析完整性、修复缺失文件，还能创作和编辑动作。");
            appendCareQuickChips();
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
        for (ChatStore.StoredMsg m : msgs) {
            if ("user".equals(m.role)) {
                appendUserBubble(m.content);
            } else if ("tool".equals(m.role)) {
                markLastToolResult(null, null, m.content);
            } else if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                appendToolBubble(parseToolName(m.toolCalls), parseToolArgs(m.toolCalls));
            } else if (m.content != null && !m.content.isEmpty()) {
                appendAiBubble(m.content);
            }
        }
    }

    // ==================== 发送与分发 ====================

    /** 护理会话空态快捷能力入口：点击即发送对应指令 */
    private void appendCareQuickChips() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(8), dp(8), dp(8), 0);
        addQuickChip(row, "体检模型", "请帮我体检全部模型，分析完整性并给出报告");
        addQuickChip(row, "修复文件", "请帮我检查并修复模型缺失或损坏的文件");
        addQuickChip(row, "创作动作", "帮我创作一个全新的动作，展示效果");
        listContainer.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollToBottom();
    }

    private void addQuickChip(LinearLayout row, String label, String instruction) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(13f);
        chip.setTextColor(getColorCompat(R.color.brand));
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(7), dp(12), dp(7));
        chip.setBackgroundResource(R.drawable.bg_btn_secondary);
        UiKit.pressScale(chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        chip.setOnClickListener(v -> quickSend(instruction));
        row.addView(chip, lp);
    }

    private void quickSend(String text) {
        etInput.setText(text);
        send();
    }

    private void send() {
        String text = etInput.getText().toString().trim();
        boolean hasFile = pendingFilePath != null && !pendingFilePath.isEmpty();
        if (text.isEmpty() && !hasFile) return;
        if (thinking) {
            Toast.makeText(this, "她还在回复中，稍等一下哦…", Toast.LENGTH_SHORT).show();
            return;
        }
        aborting = false;
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
            updateSendButton();
            if (text.isEmpty()) {
                careAI.analyzeUploadedZip(display, filePath);
            } else {
                careAI.sendMessageWithFile(text, display, filePath);
            }
            scrollToBottom();
            return;
        }

        if (hasFile) {
            String display = pendingFileName == null || pendingFileName.isEmpty()
                    ? "附件" : pendingFileName;
            String bubble = text.isEmpty() ? "📄 " + display : text + "\n📄 " + display;
            appendUserBubble(bubble);
            chatStore.addMessage(sessionKey, "user", bubble, null, null, System.currentTimeMillis());
            scrollToBottom();
            final String filePath = pendingFilePath;
            final String userText = text;
            clearPendingFile();
            thinking = true;
            renderThinkingDot();
            updateSendButton();
            new Thread(() -> {
                final String ctx = buildFileContext(display, filePath);
                runOnUiThread(() -> sendChatMessage(userText, ctx));
            }).start();
            return;
        }

        sendRaw(text);
    }

    /** 读取附件作为模型上下文：文本类读取内容，其他类型提示未解析 */
    private String buildFileContext(String name, String path) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String[] textExts = {".txt", ".md", ".markdown", ".json", ".java", ".xml", ".py",
                ".js", ".ts", ".html", ".css", ".csv", ".log", ".yml", ".yaml", ".sql",
                ".sh", ".bat", ".properties", ".gradle", ".kt", ".c", ".h", ".cpp", ".ini",
                ".cfg", ".toml", ".env", ".jsx", ".tsx"};
        boolean isText = false;
        for (String e : textExts) {
            if (lower.endsWith(e)) {
                isText = true;
                break;
            }
        }
        if (!isText) {
            return "\n\n[附件 " + name + "]（该类型文件暂未解析内容）";
        }
        try {
            File f = new File(path);
            if (!f.exists() || f.length() > 200 * 1024) {
                return "\n\n[附件 " + name + "]（文件过大或不存在，内容未读取）";
            }
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int r = fis.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            fis.close();
            String content = new String(buf, java.nio.charset.StandardCharsets.UTF_8);
            if (content.length() > 20000) {
                content = content.substring(0, 20000) + "\n…（内容已截断）";
            }
            return "\n\n[附件 " + name + " 内容如下]\n" + content;
        } catch (Exception e) {
            return "\n\n[附件 " + name + "]（读取失败：" + e.getMessage() + "）";
        }
    }

    /** 发送纯文本消息（新输入或历史重发共用），带并发/停止状态管理 */
    private void sendRaw(String text) {
        if (text == null || text.isEmpty()) return;
        if (thinking) {
            Toast.makeText(this, "她还在回复中，稍等一下哦…", Toast.LENGTH_SHORT).show();
            return;
        }
        aborting = false;
        appendUserBubble(text);
        chatStore.addMessage(sessionKey, "user", text, null, null, System.currentTimeMillis());
        scrollToBottom();
        if (isCare) {
            if (careAI == null) careAI = CareAI.getInstance(this);
            thinking = true;
            renderThinkingDot();
            updateSendButton();
            careAI.sendMessage(text);
        } else {
            if (ChatStore.TYPE_MODEL.equals(type) && modelName != null && !modelName.isEmpty()) {
                PetService svc = PetService.getInstance();
                if (svc != null) svc.switchToModelByName(modelName);
            }
            thinking = true;
            renderThinkingDot();
            updateSendButton();
            sendChatMessage(text, null);
        }
    }

    // ==================== 停止生成 / 发送按钮状态 ====================

    private void updateSendButton() {
        if (btnSend == null) return;
        if (thinking) {
            btnSend.setImageResource(R.drawable.ic_stop);
        } else {
            btnSend.setImageResource(R.drawable.ic_send);
        }
    }

    /** 用户点击停止：取消当前流式请求，保留已生成内容，恢复可发送状态 */
    private void stopGenerating() {
        if (!thinking) return;
        aborting = true;
        try {
            if (isCare) {
                if (careAI != null) careAI.cancel();
            } else if (llm != null) {
                llm.cancel();
            }
        } catch (Exception ignored) {
        }
        if (curAssistantBubble != null && !curAssistantText.isEmpty()) {
            chatStore.addMessage(sessionKey, "assistant", curAssistantText,
                    null, null, System.currentTimeMillis());
        }
        curAssistantBubble = null;
        curAssistantText = "";
        thinking = false;
        aborting = false;
        hideThinkingDot();
        updateSendButton();
        scrollToBottom();
    }

    private boolean consumeAbort() {
        if (aborting) {
            aborting = false;
            thinking = false;
            hideThinkingDot();
            updateSendButton();
            return true;
        }
        return false;
    }

    // ==================== 顶部快捷切换模型 ====================

    private String modelScope() {
        return isCare ? com.digitallife.util.ApiManager.SCOPE_CARE
                : com.digitallife.util.ApiManager.SCOPE_CHAT;
    }

    private void refreshModelChip() {
        if (btnModel == null) return;
        com.digitallife.util.ApiManager am = new com.digitallife.util.ApiManager(this);
        com.digitallife.util.ApiProfile cur = am.getCurrent(modelScope());
        String label;
        if (cur != null && cur.model != null && !cur.model.isEmpty()) {
            label = cur.model;
        } else if (cur != null && cur.name != null && !cur.name.isEmpty()) {
            label = cur.name;
        } else {
            label = isCare ? "护理模型" : "选择模型";
        }
        btnModel.setText("模型 " + label + " ▾");
    }

    private void switchModel() {
        com.digitallife.util.ApiManager am = new com.digitallife.util.ApiManager(this);
        final String scope = modelScope();
        final java.util.List<com.digitallife.util.ApiProfile> list = am.list(scope);
        if (list.isEmpty()) {
            Toast.makeText(this, "还没有配置模型，请到「设置 → 模型配置」添加", Toast.LENGTH_LONG).show();
            return;
        }
        String curId = am.getCurrentId(scope);
        int curIdx = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(curId)) { curIdx = i; break; }
        }
        final String[] names = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            com.digitallife.util.ApiProfile p = list.get(i);
            names[i] = (p.name == null || p.name.isEmpty() ? "（未命名）" : p.name)
                    + " · " + (p.model == null || p.model.isEmpty() ? "?" : p.model);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(isCare ? "切换护理大脑模型" : "切换对话大脑模型")
                .setSingleChoiceItems(names, curIdx, (d, w) -> {
                    com.digitallife.util.ApiProfile sel = list.get(w);
                    am.setCurrent(scope, sel.id);
                    if (com.digitallife.util.ApiManager.SCOPE_CHAT.equals(scope)) {
                        am.syncCurrentToSettings(scope, new com.digitallife.util.Settings(this));
                    }
                    d.dismiss();
                    refreshModelChip();
                    applyModelNow();
                    Toast.makeText(this, "已切换到 " + sel.model, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 对话大脑独立对话（不依赖桌宠） ====================

    private void ensureChatLlm() {
        if (llm != null) return;
        ApiManager am = new ApiManager(this);
        ApiProfile p = am.getCurrent(ApiManager.SCOPE_CHAT);
        if (p != null && p.baseUrl != null && !p.baseUrl.isEmpty()
                && p.apiKey != null && !p.apiKey.isEmpty()) {
            llm = new LLMClient(p.baseUrl, p.apiKey, p.model);
        } else {
            Settings s = new Settings(this);
            llm = new LLMClient(s.getApiBase(), s.getApiKey(), s.getModel());
        }
    }

    private void sendChatMessage(String text, String attachContext) {
        ensureChatLlm();
        if (llm == null || llm.getBaseUrl() == null || llm.getBaseUrl().isEmpty()) {
            hideThinkingDot();
            thinking = false;
            updateSendButton();
            String msg = "还没有可用的模型配置，请到「设置 → 模型配置」添加对话大脑模型。";
            appendAiBubble(msg);
            chatStore.addMessage(sessionKey, "assistant", msg, null, null, System.currentTimeMillis());
            scrollToBottom();
            return;
        }
        List<LLMClient.ChatMessage> msgs = new ArrayList<>();
        List<ChatStore.StoredMsg> hist = chatStore.getMessages(sessionKey, 40);
        boolean found = false;
        for (ChatStore.StoredMsg m : hist) {
            if (m.content == null || m.content.isEmpty()) continue;
            String role = "user".equals(m.role) ? "user" : "assistant";
            if (!found && "user".equals(role) && text.equals(m.content)) {
                msgs.add(new LLMClient.ChatMessage(role,
                        attachContext != null && !attachContext.isEmpty()
                                ? m.content + "\n\n" + attachContext : m.content));
                found = true;
            } else {
                msgs.add(new LLMClient.ChatMessage(role, m.content));
            }
        }
        if (!found) {
            msgs.add(new LLMClient.ChatMessage("user",
                    attachContext != null && !attachContext.isEmpty()
                            ? text + "\n\n" + attachContext : text));
        }

        JSONObject extra = new JSONObject();
        try {
            extra.put("system", chatSystemPrompt());
        } catch (Exception ignored) {
        }

        llm.chatStream(msgs, extra, new LLMClient.StreamListener() {
            @Override
            public void onDelta(String t) {
                runOnUiThread(() -> {
                    if (curAssistantBubble == null) {
                        hideThinkingDot();
                        curAssistantText = "";
                        curAssistantBubble = newTextViewBubble();
                        listContainer.addView(curAssistantBubble);
                    }
                    curAssistantText += t;
                    curAssistantBubble.setText(mdRenderer != null
                            ? mdRenderer.render(curAssistantText) : curAssistantText);
                    scrollToBottom();
                });
            }

            @Override
            public void onToolCall(String name, JSONObject args, String toolCallId) {
            }

            @Override
            public void onDone(String fullText) {
                runOnUiThread(() -> {
                    if (consumeAbort()) return;
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
                    updateSendButton();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (consumeAbort()) return;
                    hideThinkingDot();
                    appendAiBubble("出错了：" + error);
                    thinking = false;
                    updateSendButton();
                });
            }
        });
    }

    private String chatSystemPrompt() {
        Settings s = new Settings(this);
        String persona = ChatStore.TYPE_MODEL.equals(type) && modelName != null
                ? modelName : s.getPetName();
        StringBuilder sb = new StringBuilder();
        sb.append("你是「").append(persona).append("」，一个住在用户手机里的 AI 二次元少女，是用户亲密的朋友。\n");
        sb.append("你活泼可爱，有一点小傲娇，关心用户但绝不啰嗦。\n");
        sb.append("说话简短自然，一般不超过 80 字，用口语化的二次元语气，亲近但不腻。\n");
        sb.append("当前时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date())).append("\n");
        sb.append("直接以纯文本回复，不要输出 JSON 或任何标记。");
        return sb.toString();
    }

    /** 切换模型后立即让本会话使用新配置 */
    private void applyModelNow() {
        if (isCare) {
            if (careAI == null) careAI = CareAI.getInstance(this);
            careAI.reconfigure();
            return;
        }
        llm = null;
        ensureChatLlm();
    }

    // ==================== 护理大脑（care）流式 ====================

    private void setupCareListener() {
        if (careAI == null) careAI = CareAI.getInstance(this);
        careListener = new CareAI.CareListener() {
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
                    curAssistantBubble.setText(mdRenderer != null
                            ? mdRenderer.render(curAssistantText) : curAssistantText);
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
                    if (consumeAbort()) return;
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
                    updateSendButton();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (consumeAbort()) return;
                    hideThinkingDot();
                    appendAiBubble("出错了：" + error);
                    thinking = false;
                    updateSendButton();
                });
            }
        };
        careAI.setListener(careListener);
    }

    // ==================== 附件（护理大脑上传 zip） ====================

    private void pickAttach() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            if (isCare) {
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed"});
            } else {
                i.setType("*/*");
            }
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
        b.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        b.setLinksClickable(true);
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
        bubble.setOnLongClickListener(v -> {
            String txt = bubble.getText() == null ? "" : bubble.getText().toString();
            new android.app.AlertDialog.Builder(this)
                    .setTitle("消息操作")
                    .setItems(new String[]{"复制", "重新发送", "分享"}, (d, w) -> {
                        if (w == 0) {
                            copyToClipboard(txt);
                        } else if (w == 1) {
                            sendRaw(txt);
                        } else {
                            shareText(txt);
                        }
                    })
                    .show();
            return true;
        });
    }

    private void appendAiBubble(String text) {
        appendTimeDividerIfNeeded();
        TextView b = newTextViewBubble();
        b.setText(mdRenderer != null ? mdRenderer.render(text) : text);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        listContainer.addView(b);
        b.setOnLongClickListener(v -> {
            String txt = b.getText() == null ? "" : b.getText().toString();
            new android.app.AlertDialog.Builder(this)
                    .setTitle("消息操作")
                    .setItems(new String[]{"朗读", "复制", "分享"}, (d, w) -> {
                        if (w == 0) {
                            speakText(txt);
                        } else if (w == 1) {
                            copyToClipboard(txt);
                        } else {
                            shareText(txt);
                        }
                    })
                    .show();
            return true;
        });
    }

    private android.speech.tts.TextToSpeech tts;

    private void speakText(String text) {
        if (text == null || text.isEmpty()) return;
        if (tts == null) {
            tts = new android.speech.tts.TextToSpeech(this, status -> {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    runOnUiThread(() -> speakNow(text));
                } else {
                    runOnUiThread(() -> Toast.makeText(ChatActivity.this,
                            "初始化语音引擎失败", Toast.LENGTH_SHORT).show());
                }
            });
        } else {
            speakNow(text);
        }
    }

    private void speakNow(String text) {
        if (tts == null) return;
        int r = tts.setLanguage(Locale.CHINA);
        if (r == android.speech.tts.TextToSpeech.LANG_MISSING_DATA
                || r == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
            Toast.makeText(this, "设备缺少中文语音数据", Toast.LENGTH_SHORT).show();
            return;
        }
        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                null, "speak" + System.currentTimeMillis());
    }

    private void shareText(String text) {
        if (text == null || text.isEmpty()) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(send, "分享消息"));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText("消息", text));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        }
    }

    /** 工具过程卡片（完全可视化）：工具名 + 完整参数；结果回填后完整展示 */
    private void appendToolBubble(String toolName, String argsText) {
        hideThinkingDot();
        final TextView b = new TextView(this);
        b.setTextSize(13f);
        b.setTextColor(getColorCompat(R.color.text_primary));
        b.setLineSpacing(2f, 1f);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setElevation(dp(1));
        b.setBackgroundResource(R.drawable.bg_tool);
        String pretty = prettyJson(argsText);
        String head = "🔧 正在调用工具：" + (toolName == null ? "…" : toolName)
                + "\n\n⚙ 参数：" + (pretty.isEmpty() ? "（无）" : pretty);
        String statusLine = "\n\n状态：执行中…";
        SpannableString ss = new SpannableString(head + statusLine);
        ss.setSpan(new ForegroundColorSpan(getColorCompat(R.color.brand)),
                head.length() + "\n\n状态：".length(), ss.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setText(ss);
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

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etInput.getWindowToken(), 0);
    }

    private void scrollToBottom() {
        if (userScrolledAway) return;
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

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isCare) {
            careAI = CareAI.getInstance(this);
            careAI.setSession(sessionKey);
            setupCareListener();
        } else {
            ensureChatLlm();
        }
    }

    @Override
    public void onBackPressed() {
        if (thinking) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("她还在回复中")
                    .setMessage("现在退出将中断回复，确定要退出吗？")
                    .setPositiveButton("退出", (d, w) -> finish())
                    .setNegativeButton("继续等", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

    protected void onDestroy() {
        super.onDestroy();
        if (careAI != null && careListener != null) careAI.removeListener(careListener);
        if (llm != null) llm.cancel();
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (scrollWatcher != null) {
            try {
                scroll.getViewTreeObserver().removeOnScrollChangedListener(scrollWatcher);
            } catch (Exception ignored) {
            }
        }
    }
}
