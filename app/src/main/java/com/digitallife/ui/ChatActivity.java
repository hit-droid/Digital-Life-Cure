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
import android.widget.HorizontalScrollView;
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
import org.json.JSONArray;

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
    /** v1.27.0：输入建议栏（基于历史对话的 3 条短建议） */
    private LinearLayout suggestionBar;
    /** v1.33.0：固定 chips 栏引用，与动态建议互斥显示避免挤占输入区 */
    private android.widget.HorizontalScrollView chipScrollRef;
    private long lastSuggestionAt = 0;
    /** v1.34.0：建议缓存——历史指纹未变则复用，省 API 调用与延迟 */
    private String cachedSuggestionKey = null;
    private String[] cachedSuggestions = null;
    private static final long SUGGESTION_COOLDOWN_MS = 30_000L;
    private static final long SUGGESTION_DEBOUNCE_MS = 1_000L;
    private final Runnable suggestionDebounce = new Runnable() {
        @Override public void run() { requestSuggestions(); }
    };
    /** 气泡最大宽度 = 屏幕宽度 82%，对齐 Operit 比例 */
    private int maxBubbleWidth;
    private CareAI careAI;
    private CareAI.CareListener careListener;
    private LLMClient llm;
    private MarkdownRenderer mdRenderer;

    // 待发送附件（选文件后不立即发，与消息一起提交）
    private LinearLayout attachBar;
    private TextView tvAttachName;
    private String pendingFileName;
    private String pendingFilePath;
    /** v1.42.0：记录最后一条用户消息原文，供失败后一键重试 */
    private String lastUserText = null;
    private String lastAttachContext = null;

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

    // ==================== v1.31.0：语音输入 ====================
    private android.speech.SpeechRecognizer speechRecognizer;
    private boolean listening = false;
    private static final int REQ_AUDIO_PERMISSION = 4001;
    /** v1.47.0：输入框最多显示的行数，超出后内部滚动 */
    private static final int INPUT_MAX_LINES = 5;
    private ImageButton btnVoiceRef;
    /** v1.32.0：顶栏标题引用，供自动标题更新 */
    private TextView tvTitleRef;

    private boolean thinking = false;
    private TextView thinkingBubble;
    private Runnable thinkingUpdater;
    private int thinkingStage = 0;
    private static final String[] THINKING_STAGES = {
            "正在发送…", "等待 AI 响应…", "AI 思考中…", "模型推理中…"
    };
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
                getColorCompat(R.color.code_text),
                getColorCompat(R.color.operit_text_secondary),
                getColorCompat(R.color.operit_text_primary),
                getColorCompat(R.color.operit_accent));
        restoreHistory();
    }

    private void buildUi() {
        maxBubbleWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.operit_bg));

        // ===== 顶栏 =====
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        // v1.26.0：护理大脑用专属深绿顶栏，区分普通对话
        topBar.setBackgroundResource(isCare
                ? R.drawable.bg_top_bar_care
                : R.drawable.bg_top_bar);
        topBar.setElevation(dp(4));
        topBar.setPadding(dp(4), statusBarHeight() + dp(8), dp(4), dp(12));

        ImageButton btnBack = iconButton(R.drawable.ic_back);
        btnBack.setContentDescription("返回");
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, btnLp(40, 40));

        TextView tvTitle = new TextView(this);
        tvTitleRef = tvTitle;   // v1.32.0：供自动标题更新
        tvTitle.setText(title == null || title.isEmpty() ? "对话" : title);
        tvTitle.setTextSize(18f);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tvTitle.setSingleLine(true);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // v1.26.0：护理大脑模式徽章（pill 描边样式）
        if (isCare) {
            TextView careBadge = new TextView(this);
            careBadge.setText("● 医疗");
            careBadge.setTextSize(11f);
            careBadge.setTextColor(0xFF6EE7B7);  // 薄荷绿
            careBadge.setBackgroundResource(R.drawable.bg_pill_care);
            careBadge.setPadding(dp(10), dp(3), dp(10), dp(3));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.setMargins(dp(4), 0, dp(6), 0);
            topBar.addView(careBadge, blp);
        }

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

        // v1.44.0：溢出菜单（搜索/导出/清空 收进「⋯」，给标题腾出横向空间，
        // 同时把「清空」这类破坏性操作藏进菜单，避免误触）
        Button btnMore = new Button(this);
        btnMore.setText("⋯");
        btnMore.setContentDescription("更多操作");
        btnMore.setTextSize(18f);
        btnMore.setTextColor(Color.WHITE);
        btnMore.setAllCaps(false);
        btnMore.setBackgroundResource(R.drawable.bg_btn_glass);
        btnMore.setPadding(dp(10), dp(2), dp(10), dp(2));
        btnMore.setMinWidth(dp(40));
        UiKit.pressScale(btnMore);
        LinearLayout.LayoutParams mlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        mlp2.setMargins(dp(4), 0, dp(4), 0);
        btnMore.setOnClickListener(v -> showOverflowMenu(btnMore));
        topBar.addView(btnMore, mlp2);

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

        // 输入区与对话区的细分隔线
        View inputDivider = new View(this);
        inputDivider.setBackgroundColor(getColorCompat(R.color.operit_divider));
        root.addView(inputDivider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // ===== 快捷操作 chips（输入栏上方） =====
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScrollRef = chipScroll;   // v1.33.0：供与动态建议互斥显示
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setBackgroundColor(getColorCompat(R.color.operit_bg));
        chipScroll.setPadding(dp(10), dp(6), dp(10), dp(6));
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);
        // v1.26.0：护理大脑用专属 chips（健康/医疗主题）
        String[] chipLabels;
        String[] chipInserts;
        if (isCare) {
            chipLabels = new String[]{"✦ 症状", "✦ 饮食", "✦ 用药", "✦ 运动", "✦ 复盘"};
            chipInserts = new String[]{
                    "我最近有以下症状，请帮我分析：",
                    "请帮我设计一份适合我的饮食方案：",
                    "请帮我看看这个用药方案：",
                    "请帮我制定一个运动计划：",
                    "请帮我复盘最近的健康数据："
            };
        } else {
            chipLabels = new String[]{"✦ 语音", "✦ 拍照", "✦ 翻译", "✦ 总结", "✦ 联网"};
            chipInserts = new String[]{
                    "请用语音回复我：",
                    "请看图回答：",
                    "请帮我翻译成中文：",
                    "请帮我总结要点：",
                    "请联网搜索最新信息："
            };
        }
        for (int i = 0; i < chipLabels.length; i++) {
            final String insert = chipInserts[i];
            TextView chip = new TextView(this);
            chip.setText(chipLabels[i]);
            chip.setTextSize(12f);
            chip.setTextColor(getColorCompat(R.color.operit_text_secondary));
            chip.setBackgroundResource(R.drawable.bg_chip_outline);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipLp.rightMargin = dp(8);
            chip.setLayoutParams(chipLp);
            chip.setOnClickListener(v -> {
                UiKit.flash(v);
                etInput.setText(insert);
                etInput.setSelection(insert.length());
            });
            chipRow.addView(chip);
        }
        chipScroll.addView(chipRow);
        root.addView(chipScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // v1.27.0：输入建议栏（默认隐藏；空内容+焦点时 LLM 生成 3 条短建议）
        suggestionBar = new LinearLayout(this);
        suggestionBar.setOrientation(LinearLayout.HORIZONTAL);
        suggestionBar.setGravity(Gravity.CENTER_VERTICAL);
        suggestionBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        suggestionBar.setVisibility(View.GONE);
        root.addView(suggestionBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ===== 底部输入栏（玻璃感容器） =====
        LinearLayout inputBar = new LinearLayout(this);
        inputBar.setOrientation(LinearLayout.HORIZONTAL);
        inputBar.setGravity(Gravity.CENTER_VERTICAL);
        inputBar.setBackgroundResource(R.drawable.bg_input_bar);
        inputBar.setPadding(dp(8), dp(8), dp(8), dp(8));

        // 待发送附件条（选文件后先暂存，与文字一起发送）
        attachBar = new LinearLayout(this);
        attachBar.setOrientation(LinearLayout.HORIZONTAL);
        attachBar.setGravity(Gravity.CENTER_VERTICAL);
        attachBar.setBackgroundColor(getColorCompat(R.color.operit_surface));
        attachBar.setPadding(dp(14), dp(6), dp(14), dp(6));
        attachBar.setVisibility(View.GONE);
        tvAttachName = new TextView(this);
        tvAttachName.setTextSize(13f);
        tvAttachName.setTextColor(getColorCompat(R.color.operit_text_primary));
        tvAttachName.setSingleLine(true);
        tvAttachName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        attachBar.addView(tvAttachName, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tvRemove = new TextView(this);
        tvRemove.setText("✕");
        tvRemove.setTextSize(15f);
        tvRemove.setTextColor(getColorCompat(R.color.operit_text_hint));
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
            btnAttach.setContentDescription("附加文件");   // v1.37.0 无障碍
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
        // v1.47.0：改为多行输入。此前 singleLine + IME_ACTION_SEND 会让回车直接发送，
        // 用户无法换行排版，长消息只能挤成一行。现在回车＝换行，发送走按钮。
        etInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NONE);
        etInput.setSingleLine(false);
        etInput.setMaxLines(INPUT_MAX_LINES);
        etInput.setHorizontallyScrolling(false);
        etInput.setVerticalScrollBarEnabled(false);
        // 随内容自动增高，达到上限后内部滚动，避免顶飞上方对话
        etInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) {
                int lines = etInput.getLineCount();
                etInput.setMaxLines(java.lang.Math.min(lines, INPUT_MAX_LINES));
            }
        });
        etInput.setBackgroundResource(R.drawable.bg_input);
        etInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        etInput.setOnFocusChangeListener((v, has) -> v.setBackgroundResource(
                has ? R.drawable.bg_input_focused : R.drawable.bg_input));
        // v1.27.0：输入监听 → 触发输入建议
        etInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                String t = s == null ? "" : s.toString().trim();
                if (t.isEmpty() && etInput.hasFocus()) {
                    handler.removeCallbacks(suggestionDebounce);
                    handler.postDelayed(suggestionDebounce, SUGGESTION_DEBOUNCE_MS);
                } else {
                    handler.removeCallbacks(suggestionDebounce);
                    hideSuggestions();   // v1.33.0
                }
            }
        });
        etInput.setOnFocusChangeListener((v, has) -> {
            v.setBackgroundResource(has ? R.drawable.bg_input_focused : R.drawable.bg_input);
            if (has && (etInput.getText() == null || etInput.getText().toString().trim().isEmpty())) {
                handler.removeCallbacks(suggestionDebounce);
                handler.postDelayed(suggestionDebounce, SUGGESTION_DEBOUNCE_MS);
            } else if (!has) {
                handler.removeCallbacks(suggestionDebounce);
                hideSuggestions();   // v1.33.0
            }
        });
        inputBar.addView(etInput, new LinearLayout.LayoutParams(0, dp(48), 1f));

        // v1.31.0：语音按钮接入真实 SpeechRecognizer
        ImageButton btnVoice = new ImageButton(this);
        btnVoiceRef = btnVoice;
        btnVoice.setImageResource(R.drawable.ic_mic);
        btnVoice.setColorFilter(getColorCompat(R.color.operit_text_secondary));
        btnVoice.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnVoice.setScaleType(ImageView.ScaleType.CENTER);
        btnVoice.setPadding(dp(10), dp(10), dp(10), dp(10));
        UiKit.pressScale(btnVoice);
        btnVoice.setContentDescription("语音输入");
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        vlp.leftMargin = dp(6);
        btnVoice.setOnClickListener(v -> toggleVoiceInput());
        inputBar.addView(btnVoice, vlp);

        ImageButton btnSendView = new ImageButton(this);
        btnSend = btnSendView;
        btnSend.setImageResource(R.drawable.ic_send);
        btnSend.setBackgroundResource(R.drawable.bg_send);
        btnSend.setScaleType(ImageView.ScaleType.CENTER);
        btnSend.setPadding(dp(10), dp(10), dp(10), dp(10));
        btnSend.setElevation(dp(2));
        btnSend.setContentDescription("发送");   // v1.37.0 无障碍
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
                // v1.45.0：普通会话空态追加引导卡片（快捷入口），
                // 解决新用户打开后只有一句话、不知道能问什么的困惑
                appendAiBubble("你好，开始我们的对话吧。");
                appendEmptyGuide();
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
        File f = new File(path);
        if (!f.exists() || f.length() > 200 * 1024) {
            return "\n\n[附件 " + name + "]（文件过大或不存在，内容未读取）";
        }
        // 用 try-with-resources 保证流一定关闭；按块读避免一次性分配大数组
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) bos.write(buf, 0, n);
            String content = bos.toString(java.nio.charset.StandardCharsets.UTF_8);
            if (content.length() > 20000) {
                content = content.substring(0, 20000) + "\n…（内容已截断）";
            }
            return "\n\n[附件 " + name + " 内容如下]\n" + content;
        } catch (Exception e) {
            return "\n\n[附件 " + name + "]（读取失败：" + com.digitallife.ui.UiKit.safeMsg(e) + "）";
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
            // v1.28.0：完成后对长消息应用折叠
            applyCollapse(curAssistantBubble);
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

    // ==================== v1.27.0：输入建议（基于历史对话的 3 条短建议） ====================

    /**
     * 基于近 10 条对话 + 相关事实，让 LLM 生成 3 条 ≤12 字的输入建议。
     * 失败/超时/无 API 静默隐藏 suggestionBar。
     */
    private void requestSuggestions() {
        if (suggestionBar == null) return;
        if (etInput == null || etInput.getText() == null
                || !etInput.getText().toString().trim().isEmpty()) {
            hideSuggestions();   // v1.33.0
            return;
        }
        if (!etInput.hasFocus()) {
            hideSuggestions();   // v1.33.0
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastSuggestionAt < SUGGESTION_COOLDOWN_MS) return;
        lastSuggestionAt = now;

        ensureChatLlm();
        if (llm == null || llm.getBaseUrl() == null || llm.getBaseUrl().isEmpty()) return;

        List<ChatStore.StoredMsg> hist = chatStore.getMessages(sessionKey, 10);

        // v1.34.0：历史指纹（条数 + 末条时间戳），未变则复用缓存建议
        String fingerprint = hist.size() + "_"
                + (hist.isEmpty() ? 0 : hist.get(hist.size() - 1).timestamp);
        if (fingerprint.equals(cachedSuggestionKey) && cachedSuggestions != null) {
            renderSuggestions(cachedSuggestions);
            return;
        }

        if (hist.isEmpty()) {
            // 冷启动：用 settings 里的 petName 生成首次建议
            String petName = new Settings(this).getPetName();
            String[] cold = new String[]{
                    "和" + petName + "聊聊天",
                    "问问" + petName + "今天心情",
                    "让" + petName + "讲个笑话"
            };
            cachedSuggestionKey = fingerprint;
            cachedSuggestions = cold;
            renderSuggestions(cold);
            return;
        }

        StringBuilder context = new StringBuilder();
        context.append("你是「").append(new Settings(this).getPetName()).append("」。\n");
        context.append("基于以下最近的对话，为用户生成 3 条他可能想问的简短问题（每条 ≤12 字）。\n");
        context.append("要求：贴合上下文、自然、口语化。\n");
        context.append("输出 JSON 数组：[\"...\",\"...\",\"...\"]。只输出 JSON。\n\n");
        for (ChatStore.StoredMsg m : hist) {
            String role = m.role == null ? "user" : m.role;
            String content = m.content == null ? "" : m.content;
            if (content.length() > 80) content = content.substring(0, 80) + "…";
            context.append(role).append(": ").append(content).append("\n");
        }

        List<LLMClient.ChatMessage> msgs = new ArrayList<>();
        msgs.add(new LLMClient.ChatMessage("user", context.toString()));
        JSONObject extra = new JSONObject();
        try {
            extra.put("system", "你只输出 JSON 数组，不要任何解释。");
        } catch (Exception ignored) {}

        llm.chatOnce(msgs, extra, (text, err) -> {
            if (err != null || text == null) {
                handler.post(() -> hideSuggestions());   // v1.33.0
                return;
            }
            String[] suggestions = parseSuggestions(text);
            handler.post(() -> {
                if (suggestions == null) {
                    hideSuggestions();   // v1.33.0
                } else {
                    // v1.34.0：写入缓存，下次历史未变时直接复用
                    cachedSuggestionKey = fingerprint;
                    cachedSuggestions = suggestions;
                    renderSuggestions(suggestions);
                }
            });
        });
    }

    private String[] parseSuggestions(String text) {
        if (text == null) return null;
        String t = text.trim();
        // 去除 markdown 代码块包裹
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            int lastFence = t.lastIndexOf("```");
            if (lastFence > 0) t = t.substring(0, lastFence);
            t = t.trim();
        }
        try {
            JSONArray arr = new JSONArray(t);
            if (arr.length() == 0) return null;
            String[] out = new String[Math.min(3, arr.length())];
            for (int i = 0; i < out.length; i++) {
                String s = arr.optString(i, "").trim();
                if (s.isEmpty()) return null;
                if (s.length() > 20) s = s.substring(0, 20);
                out[i] = s;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void renderSuggestions(String[] suggestions) {
        if (suggestionBar == null) return;
        suggestionBar.removeAllViews();
        for (int i = 0; i < suggestions.length; i++) {
            final String text = suggestions[i];
            TextView chip = new TextView(this);
            chip.setText("✦ " + text);
            chip.setTextSize(12f);
            chip.setTextColor(getColorCompat(R.color.operit_text_secondary));
            chip.setBackgroundResource(R.drawable.bg_chip_outline);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                UiKit.flash(v);
                etInput.setText(text);
                etInput.setSelection(text.length());
                hideSuggestions();   // v1.33.0：统一收起并恢复固定 chips
            });
            suggestionBar.addView(chip);
        }
        suggestionBar.setVisibility(View.VISIBLE);
        // v1.33.0：有动态建议时隐藏固定 chips，避免两栏同时挤占输入区
        if (chipScrollRef != null) chipScrollRef.setVisibility(View.GONE);
    }

    /**
     * v1.33.0：收起动态建议栏并恢复固定 chips 栏。
     * 所有隐藏 suggestionBar 的地方统一走这里，保证互斥状态一致。
     */
    private void hideSuggestions() {
        if (suggestionBar != null) suggestionBar.setVisibility(View.GONE);
        if (chipScrollRef != null) chipScrollRef.setVisibility(View.VISIBLE);
    }

    // ==================== 对话大脑独立对话（不依赖桌宠） ====================

    private void ensureChatLlm() {
        ApiManager am = new ApiManager(this);
        ApiProfile p = am.getCurrent(ApiManager.SCOPE_CHAT);
        if (p != null && p.baseUrl != null && !p.baseUrl.isEmpty()
                && !p.effectiveKeys().isEmpty()) {
            llm = new LLMClient(p.baseUrl, am.nextKey(ApiManager.SCOPE_CHAT, p.id), p.model);
            // v1.26.0：注入完整密钥池，401/429 时 LLMClient 自动轮换
            llm.setApiKeys(p.effectiveKeys());
        } else {
            Settings s = new Settings(this);
            llm = new LLMClient(s.getApiBase(), s.getApiKey(), s.getModel());
        }
    }

    // ==================== v1.30.0：对话上下文预算裁剪 ====================

    /** 上下文字符预算（约 6k 字符 ≈ 2~3k token），超出则丢弃较早消息 */
    private static final int CTX_BUDGET_CHARS = 6000;
    /** 无论多长，至少保留最近这么多条原文 */
    private static final int CTX_KEEP_RECENT = 6;

    /**
     * v1.30.0：按字符预算裁剪历史。
     * 策略：从最新往回累加，超出预算就停；至少保留 CTX_KEEP_RECENT 条。
     * 若发生丢弃，在最早保留的一条前插入一条说明，让模型知道上下文被截断。
     */
    private List<ChatStore.StoredMsg> trimHistoryForBudget(List<ChatStore.StoredMsg> full) {
        if (full == null || full.isEmpty()) return full;
        int total = 0;
        for (ChatStore.StoredMsg m : full) {
            total += m.content == null ? 0 : m.content.length();
        }
        if (total <= CTX_BUDGET_CHARS) return full; // 预算内，原样返回

        // 从最新往回选
        ArrayList<ChatStore.StoredMsg> kept = new ArrayList<>();
        int used = 0;
        for (int i = full.size() - 1; i >= 0; i--) {
            ChatStore.StoredMsg m = full.get(i);
            int len = m.content == null ? 0 : m.content.length();
            if (kept.size() >= CTX_KEEP_RECENT && used + len > CTX_BUDGET_CHARS) break;
            kept.add(0, m);
            used += len;
        }
        int dropped = full.size() - kept.size();
        if (dropped > 0) {
            // 插入一条截断说明（role=system 由调用方按 user 兼容处理）
            ChatStore.StoredMsg note = new ChatStore.StoredMsg("system",
                    "（为控制长度，已省略更早的 " + dropped + " 条对话）",
                    null, null, System.currentTimeMillis());
            kept.add(0, note);
        }
        return kept;
    }

    private void sendChatMessage(String text, String attachContext) {
        // v1.42.0：记录原文，失败时可一键重试
        lastUserText = text;
        lastAttachContext = attachContext;
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
        // v1.30.0：上下文预算裁剪，避免长对话撑爆 token
        List<ChatStore.StoredMsg> hist = trimHistoryForBudget(
                chatStore.getMessages(sessionKey, 40));
        boolean found = false;
        for (ChatStore.StoredMsg m : hist) {
            if (m.content == null || m.content.isEmpty()) continue;
            // v1.30.0：system 说明原样传（不归类成 assistant）
            String role = "user".equals(m.role) ? "user"
                    : ("system".equals(m.role) ? "system" : "assistant");
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
                    // v1.32.0：首轮完成后自动命名会话
                    maybeAutoTitle();
                    if (curAssistantBubble != null && !curAssistantText.isEmpty()) {
                        chatStore.addMessage(sessionKey, "assistant", curAssistantText,
                                null, null, System.currentTimeMillis());
                        // v1.28.0：完成后对长消息应用折叠
                        applyCollapse(curAssistantBubble);
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
                    // v1.32.0：首轮完成后自动命名会话
                    maybeAutoTitle();
                    if (curAssistantBubble != null && !curAssistantText.isEmpty()) {
                        chatStore.addMessage(sessionKey, "assistant", curAssistantText,
                                null, null, System.currentTimeMillis());
                        // v1.28.0：完成后对长消息应用折叠
                        applyCollapse(curAssistantBubble);
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
                            "读取文件失败：" + com.digitallife.ui.UiKit.safeMsg(e), Toast.LENGTH_SHORT).show());
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
        // v1.38.0：智能时间分割线（今天 / 昨天 / 更早）
        t.setText(formatDividerTime(now));
        t.setTextSize(10f);
        t.setTextColor(getColorCompat(R.color.operit_text_hint));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, dp(6));
        listContainer.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * v1.38.0：时间分割线文案。
     * 今天 → HH:mm；昨天 → 昨天 HH:mm；今年更早 → M月d日 HH:mm；跨年 → yyyy/M/d HH:mm
     */
    private String formatDividerTime(long ts) {
        java.util.Calendar target = java.util.Calendar.getInstance();
        target.setTimeInMillis(ts);
        java.util.Calendar now = java.util.Calendar.getInstance();

        boolean sameYear = target.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR);
        int dayDiff = sameYear
                ? target.get(java.util.Calendar.DAY_OF_YEAR) - now.get(java.util.Calendar.DAY_OF_YEAR)
                : 999;

        String hm = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ts));
        if (dayDiff == 0) return hm;
        if (dayDiff == -1) return "昨天 " + hm;
        if (sameYear) {
            return new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(new Date(ts));
        }
        return new SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(new Date(ts));
    }

    private TextView newTextViewBubble() {
        TextView b = new TextView(this);
        b.setTextSize(15f);
        b.setTextColor(getColorCompat(R.color.operit_text_primary));
        b.setLineSpacing(3f, 1f);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        b.setMaxWidth(maxBubbleWidth);
        b.setElevation(dp(2));
        // v1.26.0：护理大脑用专属绿色气泡背景
        b.setBackgroundResource(isCare
                ? R.drawable.bg_bubble_care
                : R.drawable.bg_bubble_ai);
        b.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        b.setLinksClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        return b;
    }

    // ==================== v1.28.0：长消息展开/收起 ====================

    /** 折叠阈值：AI 回复超过此行数时默认收起，点击展开 */
    private static final int COLLAPSE_MAX_LINES = 10;
    /** v1.35.0：工具结果超过此长度默认折叠；折叠时摘要长度 */
    private static final int TOOL_COLLAPSE_CHARS = 300;
    private static final int TOOL_BRIEF_CHARS = 150;

    /**
     * v1.28.0：对已完成的气泡应用长消息折叠。
     * 超过 COLLAPSE_MAX_LINES 行则截断并加「展开」提示，点击气泡切换展开/收起。
     * 仅对已完成气泡调用（流式过程中不限制，保证用户能看到流式全文）。
     */
    private void applyCollapse(TextView bubble) {
        if (bubble == null) return;
        // 用 post 确保布局完成后再读行数
        bubble.post(() -> {
            try {
                int lineCount = bubble.getLineCount();
                if (lineCount <= COLLAPSE_MAX_LINES) {
                    // 短消息：保持原样，点击无副作用
                    return;
                }
                // 保存完整文本
                CharSequence full = bubble.getText();
                // 收起态
                bubble.setMaxLines(COLLAPSE_MAX_LINES);
                bubble.setEllipsize(android.text.TextUtils.TruncateAt.END);
                appendCollapseHint(bubble, full, false);
                bubble.setOnClickListener(v -> {
                    UiKit.flash(v);
                    if (bubble.getMaxLines() == COLLAPSE_MAX_LINES) {
                        // 展开
                        bubble.setMaxLines(Integer.MAX_VALUE);
                        bubble.setEllipsize(null);
                        appendCollapseHint(bubble, full, true);
                    } else {
                        // 收起
                        bubble.setMaxLines(COLLAPSE_MAX_LINES);
                        bubble.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        appendCollapseHint(bubble, full, false);
                    }
                    scrollToBottom();
                });
            } catch (Exception ignored) {
            }
        });
    }

    /** 在气泡文本末尾追加「展开 / 收起」提示（用次要色 + 小字） */
    private void appendCollapseHint(TextView bubble, CharSequence fullText, boolean expanded) {
        String hint = expanded ? "\n\n▾ 收起" : "\n\n▸ 展开全文";
        SpannableString ss = new SpannableString(fullText.toString() + hint);
        ss.setSpan(new android.text.style.ForegroundColorSpan(
                        getColorCompat(R.color.operit_accent)),
                fullText.length(), ss.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new android.text.style.RelativeSizeSpan(0.9f),
                fullText.length(), ss.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        bubble.setText(ss);
    }

    // ==================== v1.36.0：会话内搜索 ====================

    private String searchQuery = null;
    private java.util.List<Integer> searchHits = new java.util.ArrayList<>();
    private int searchHitIndex = 0;

    /** 弹搜索框，输入关键词后定位到第一处命中 */
    private void showSearchDialog() {
        final EditText input = new EditText(this);
        input.setHint("输入关键词");
        input.setTextSize(14f);
        input.setSingleLine(true);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (searchQuery != null) input.setText(searchQuery);

        new android.app.AlertDialog.Builder(this)
                .setTitle("搜索本会话")
                .setView(input)
                .setPositiveButton("搜索", (d, w) -> {
                    String q = input.getText() == null ? "" : input.getText().toString().trim();
                    if (q.isEmpty()) {
                        Toast.makeText(this, "请输入关键词", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    doSearch(q);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 在已渲染的气泡里查找关键词并记录命中位置 */
    private void doSearch(String query) {
        searchQuery = query;
        searchHits.clear();
        searchHitIndex = 0;
        String lower = query.toLowerCase();
        for (int i = 0; i < listContainer.getChildCount(); i++) {
            android.view.View child = listContainer.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            String txt = ((TextView) child).getText() == null ? ""
                    : ((TextView) child).getText().toString();
            if (txt.toLowerCase().contains(lower)) searchHits.add(i);
        }
        if (searchHits.isEmpty()) {
            Toast.makeText(this, "没有找到「" + query + "」", Toast.LENGTH_SHORT).show();
            return;
        }
        gotoHit(0);
        Toast.makeText(this, "找到 " + searchHits.size() + " 处，点击「下一处」继续",
                Toast.LENGTH_SHORT).show();
        showNextHitControl();
    }

    /** 滚动到第 index 处命中并高亮闪一下 */
    private void gotoHit(int index) {
        if (searchHits.isEmpty()) return;
        if (index < 0) index = 0;
        if (index >= searchHits.size()) index = 0;   // 循环
        searchHitIndex = index;
        int childIndex = searchHits.get(index);
        android.view.View child = listContainer.getChildAt(childIndex);
        if (child == null) return;
        child.requestFocus();
        // 滚到该气泡位置
        handler.post(() -> scroll.smoothScrollTo(0, child.getTop()));
        UiKit.flash(child);
        if (child instanceof TextView) {
            highlightText((TextView) child, searchQuery);
        }
    }

    /** 命中关键词染成品牌色，搜索词变更时重绘即可复原（setText 会重建 span） */
    private void highlightText(TextView tv, String query) {
        if (tv == null || query == null || query.isEmpty()) return;
        String text = tv.getText() == null ? "" : tv.getText().toString();
        String lower = text.toLowerCase();
        String q = query.toLowerCase();
        SpannableString ss = new SpannableString(text);
        int from = 0;
        while (true) {
            int idx = lower.indexOf(q, from);
            if (idx < 0) break;
            ss.setSpan(new android.text.style.BackgroundColorSpan(0x446C5CE7),
                    idx, idx + q.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = idx + q.length();
        }
        tv.setText(ss);
    }

    /** 显示一个可重复使用的「下一处/关闭」浮动条 */
    private void showNextHitControl() {
        Toast.makeText(this, "再次点击顶栏「搜索」可跳转下一处", Toast.LENGTH_LONG).show();
    }

    /**
     * v1.42.0：错误气泡（可点击一键重试）。
     * 展示错误原因，若记录了上一条用户消息则提供「点击重试」。
     */
    private void appendErrorBubble(String error) {
        TextView b = newTextViewBubble();
        String reason = (error == null || error.isEmpty()) ? "未知错误" : error;
        boolean canRetry = lastUserText != null && !lastUserText.trim().isEmpty();
        String tip = canRetry ? "\n\n▸ 点击这里重试" : "";
        SpannableString ss = new SpannableString("⚠ 出错了：" + reason + tip);
        // 错误原因用警示红
        ss.setSpan(new ForegroundColorSpan(0xFFE57373),
                "⚠ 出错了：".length(), "⚠ 出错了：".length() + reason.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (canRetry) {
            int s = ss.length() - tip.length() + 3;   // 跳过 "\n\n▸ "
            ss.setSpan(new ForegroundColorSpan(getColorCompat(R.color.operit_accent)),
                    s, ss.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setOnClickListener(v -> {
                UiKit.flash(v);
                String retry = lastUserText;
                String ctx = lastAttachContext;
                listContainer.removeView(b);
                if (retry != null && !retry.trim().isEmpty()) {
                    sendChatMessage(retry, ctx);
                }
            });
        }
        b.setText(ss);
        listContainer.addView(b, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        scrollToBottom();
    }

    /**
     * v1.44.0：顶栏溢出菜单。
     * 把低频的「搜索 / 导出 / 清空」收进菜单，顶栏只保留高频的「模型」切换，
     * 给会话标题留出横向空间；清空属破坏性操作，藏进菜单可减少误触。
     */
    private void showOverflowMenu(android.view.View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "搜索消息");
        pm.getMenu().add(0, 2, 0, "导出对话");
        pm.getMenu().add(0, 3, 0, "清空对话");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    // 已有搜索词时再点即跳下一处，否则弹框输入
                    if (searchQuery != null && !searchHits.isEmpty()) {
                        gotoHit(searchHitIndex + 1);
                        Toast.makeText(this, "第 " + (searchHitIndex + 1) + "/"
                                + searchHits.size() + " 处", Toast.LENGTH_SHORT).show();
                    } else {
                        showSearchDialog();
                    }
                    return true;
                case 2:
                    exportChat();
                    return true;
                case 3:
                    confirmClear();
                    return true;
                default:
                    return false;
            }
        });
        pm.show();
    }

    /**
     * v1.45.0：新会话引导卡片，追加在欢迎气泡之后。
     * 给出三枚快捷入口，点击后填入输入框（与建议 chip 行为一致，不直接发送，
     * 方便用户先编辑再决定）。
     */
    private void appendEmptyGuide() {
        if (listContainer == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(6), dp(10), dp(6), dp(4));

        TextView tip = new TextView(this);
        tip.setText("试试这样问我：");
        tip.setTextSize(12f);
        tip.setTextColor(getColorCompat(R.color.operit_text_secondary));
        tip.setPadding(0, 0, 0, dp(8));
        box.addView(tip);

        String[] starters = new String[]{"帮我写一段自我介绍", "推荐几本好书", "今天有什么安排"};
        for (final String s : starters) {
            TextView chip = new TextView(this);
            chip.setText("✦ " + s);
            chip.setTextSize(13f);
            chip.setTextColor(getColorCompat(R.color.operit_text_secondary));
            chip.setBackgroundResource(R.drawable.bg_chip_outline);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                UiKit.flash(v);
                if (etInput != null) {
                    etInput.setText(s);
                    etInput.setSelection(s.length());
                    etInput.requestFocus();
                }
            });
            box.addView(chip);
        }
        listContainer.addView(box, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
        bubble.setMaxWidth(maxBubbleWidth);
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
        // v1.28.0：长消息折叠（历史消息/非流式回复同样生效）
        applyCollapse(b);
        b.setOnLongClickListener(v -> {
            String txt = b.getText() == null ? "" : b.getText().toString();
            // v1.28.0：去掉折叠提示尾巴，避免复制/朗读带出「▸ 展开全文」
            txt = stripCollapseHint(txt);
            final String clean = txt;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("消息操作")
                    // v1.29.0：新增「重新生成」「删除」
                    .setItems(new String[]{"朗读", "复制", "分享", "重新生成", "删除"},
                            (d, w) -> {
                        if (w == 0) {
                            speakText(clean);
                        } else if (w == 1) {
                            copyToClipboard(clean);
                        } else if (w == 2) {
                            shareText(clean);
                        } else if (w == 3) {
                            regenerateLast(b);
                        } else {
                            deleteBubble(b);
                        }
                    })
                    .show();
            return true;
        });
    }

    /**
     * v1.31.0：切换语音输入。未授权则先申请；正在听则停止。
     */
    private void toggleVoiceInput() {
        if (listening) {
            stopVoiceInput();
            return;
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "当前设备不支持语音识别", Toast.LENGTH_SHORT).show();
            return;
        }
        // Android 6+ 需运行时申请录音权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M
                && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},
                    REQ_AUDIO_PERMISSION);
            return;
        }
        startVoiceInput();
    }

    /** v1.31.0：启动语音识别，结果实时填入输入框 */
    private void startVoiceInput() {
        if (listening) return;
        try {
            if (speechRecognizer == null) {
                speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
                speechRecognizer.setRecognitionListener(new VoiceRecognitionListener());
            }
            Intent intent = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE,
                    java.util.Locale.getDefault());
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            speechRecognizer.startListening(intent);
            listening = true;
            // 录音中：麦克风染成品牌色作为状态提示
            if (btnVoiceRef != null) {
                btnVoiceRef.setColorFilter(getColorCompat(R.color.brand));
                UiKit.flash(btnVoiceRef);
            }
            Toast.makeText(this, "请说话…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            listening = false;
            Toast.makeText(this, "无法启动语音识别：" + com.digitallife.ui.UiKit.safeMsg(e),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** v1.31.0：停止语音识别 */
    private void stopVoiceInput() {
        listening = false;
        if (btnVoiceRef != null) {
            btnVoiceRef.setColorFilter(getColorCompat(R.color.operit_text_secondary));
        }
        try {
            if (speechRecognizer != null) speechRecognizer.stopListening();
        } catch (Exception ignored) {
        }
    }

    /** v1.31.0：权限申请结果 */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_AUDIO_PERMISSION) {
            if (grantResults != null && grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startVoiceInput();
            } else {
                Toast.makeText(this, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** v1.31.0：语音识别回调——结果追加到输入框 */
    private class VoiceRecognitionListener
            implements android.speech.RecognitionListener {
        @Override public void onReadyForSpeech(android.os.Bundle params) {}
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() { stopVoiceInput(); }
        @Override public void onEvent(int eventType, android.os.Bundle params) {}

        @Override
        public void onPartialResults(android.os.Bundle partialResults) {
            String text = pickBest(partialResults);
            if (text != null && etInput != null) {
                etInput.setText(text);
                etInput.setSelection(text.length());
            }
        }

        @Override
        public void onResults(android.os.Bundle results) {
            listening = false;
            if (btnVoiceRef != null) {
                btnVoiceRef.setColorFilter(getColorCompat(R.color.operit_text_secondary));
            }
            String text = pickBest(results);
            if (text != null && !text.trim().isEmpty() && etInput != null) {
                String merged = etInput.getText() == null
                        ? text : etInput.getText().toString();
                // 最终结果覆盖掉 partial 内容，避免重复拼接
                if (merged.trim().isEmpty() || merged.equals(text)) {
                    merged = text;
                } else {
                    merged = text;
                }
                etInput.setText(merged);
                etInput.setSelection(merged.length());
                updateSendButton();
            }
        }

        @Override
        public void onError(int error) {
            listening = false;
            if (btnVoiceRef != null) {
                btnVoiceRef.setColorFilter(getColorCompat(R.color.operit_text_secondary));
            }
            String msg;
            switch (error) {
                case android.speech.SpeechRecognizer.ERROR_AUDIO:
                    msg = "录音失败"; break;
                case android.speech.SpeechRecognizer.ERROR_CLIENT:
                    msg = "客户端错误"; break;
                case android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    msg = "缺少麦克风权限"; break;
                case android.speech.SpeechRecognizer.ERROR_NETWORK:
                case android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    msg = "网络错误，语音识别需要联网"; break;
                case android.speech.SpeechRecognizer.ERROR_NO_MATCH:
                    msg = "没听清，请再说一次"; break;
                case android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    msg = "识别服务忙，请稍后"; break;
                case android.speech.SpeechRecognizer.ERROR_SERVER:
                    msg = "识别服务出错"; break;
                case android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    msg = "没听到声音"; break;
                default:
                    msg = "识别失败（" + error + "）"; break;
            }
            Toast.makeText(ChatActivity.this, msg, Toast.LENGTH_SHORT).show();
        }

        /** 从识别结果里取置信度最高的一条 */
        private String pickBest(android.os.Bundle bundle) {
            if (bundle == null) return null;
            java.util.ArrayList<String> list = bundle.getStringArrayList(
                    android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
            if (list == null || list.isEmpty()) return null;
            return list.get(0);
        }
    }

    // ==================== v1.32.0：会话自动标题 ====================

    /** 默认标题（与 ConversationTabView 新建时一致），命中才自动命名 */
    private static final String DEFAULT_TITLE_CHAT = "新对话";
    private static final String DEFAULT_TITLE_CARE = "护理会话";
    private boolean titleAutoTried = false;

    /**
     * v1.32.0：首轮对话完成后，若标题仍是默认值则用 LLM 生成简短标题。
     * 只尝试一次；失败/无 API 静默保留原标题。
     */
    private void maybeAutoTitle() {
        if (titleAutoTried) return;
        if (title != null && !title.isEmpty()
                && !DEFAULT_TITLE_CHAT.equals(title)
                && !DEFAULT_TITLE_CARE.equals(title)) {
            titleAutoTried = true; // 用户已自定义，不再动
            return;
        }
        List<ChatStore.StoredMsg> msgs = chatStore.getMessages(sessionKey, 10);
        // 统计 assistant 条数，仅首轮（第一条回复后）触发
        int assistantCount = 0;
        for (ChatStore.StoredMsg m : msgs) {
            if (m != null && "assistant".equals(m.role)) assistantCount++;
        }
        if (assistantCount < 1) return;
        titleAutoTried = true;

        List<LLMClient.ChatMessage> req = new ArrayList<>();
        StringBuilder ctx = new StringBuilder();
        ctx.append("根据下面这段对话，生成一个简短的会话标题。\n");
        ctx.append("要求：不超过 12 个字，中文，概括主题，不要引号和标点。\n");
        ctx.append("只输出标题文字，不要任何其他内容。\n\n");
        for (ChatStore.StoredMsg m : msgs) {
            String content = m.content == null ? "" : m.content;
            if (content.length() > 100) content = content.substring(0, 100) + "…";
            ctx.append("user".equals(m.role) ? "用户：" : "AI：").append(content).append("\n");
        }
        req.add(new LLMClient.ChatMessage("user", ctx.toString()));

        JSONObject extra = new JSONObject();
        try {
            extra.put("system", "你只输出一个简短中文标题，不超过 12 字，不要解释。");
        } catch (Exception ignored) {
        }

        // 用当前会话的 LLM（不干扰主对话）
        try {
            ensureChatLlm();
        } catch (Exception ignored) {
        }
        if (llm == null || llm.getBaseUrl() == null || llm.getBaseUrl().isEmpty()) return;

        llm.chatOnce(req, extra, (text, err) -> {
            if (err != null || text == null) return;
            String newTitle = text.trim();
            // 清理：去引号、去首尾标点、限长
            newTitle = newTitle.replace("\"", "").replace("'", "")
                    .replace("“", "").replace("”", "").trim();
            if (newTitle.isEmpty()) return;
            if (newTitle.length() > 20) newTitle = newTitle.substring(0, 20);
            final String finalTitle = newTitle;
            runOnUiThread(() -> {
                try {
                    chatStore.renameSession(sessionKey, finalTitle);
                    title = finalTitle;
                    if (tvTitleRef != null) tvTitleRef.setText(finalTitle);
                } catch (Exception ignored) {
                }
            });
        });
    }

    /** v1.28.0：移除折叠提示后缀（▸ 展开全文 / ▾ 收起） */
    private String stripCollapseHint(String text) {
        if (text == null) return "";
        String t = text;
        int i = t.lastIndexOf("\n\n▸ 展开全文");
        if (i >= 0) t = t.substring(0, i);
        i = t.lastIndexOf("\n\n▾ 收起");
        if (i >= 0) t = t.substring(0, i);
        return t;
    }

    /**
     * v1.29.0：重新生成——删掉最后一条 assistant 消息并重发上一条用户提问。
     * 仅在该气泡确实是最后一条 assistant 消息时可用。
     */
    private void regenerateLast(TextView bubble) {
        if (thinking) {
            Toast.makeText(this, "她还在回复中，稍等一下哦…", Toast.LENGTH_SHORT).show();
            return;
        }
        ChatStore.StoredMsg removed = chatStore.deleteLastAssistantMessage(sessionKey);
        if (removed == null) {
            Toast.makeText(this, "只能重新生成最后一条回复", Toast.LENGTH_SHORT).show();
            return;
        }
        // 找到上一条用户消息作为重发内容
        List<ChatStore.StoredMsg> hist = chatStore.getMessages(sessionKey, 40);
        String lastUser = null;
        for (int i = hist.size() - 1; i >= 0; i--) {
            ChatStore.StoredMsg m = hist.get(i);
            if (m != null && "user".equals(m.role) && m.content != null
                    && !m.content.trim().isEmpty()) {
                lastUser = m.content.trim();
                break;
            }
        }
        if (lastUser == null) {
            Toast.makeText(this, "找不到要重新生成的提问", Toast.LENGTH_SHORT).show();
            return;
        }
        // 移除界面上的旧气泡
        listContainer.removeView(bubble);
        // 重新发送（走正常发送流程，会重新 append user bubble + 请求）
        sendChatMessage(lastUser, null);
        Toast.makeText(this, "已重新生成", Toast.LENGTH_SHORT).show();
    }

    /** v1.29.0：删除单条气泡（同时从数据库移除） */
    private void deleteBubble(TextView bubble) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除这条消息？")
                .setMessage("删除后无法恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    ChatStore.StoredMsg removed = chatStore.deleteLastAssistantMessage(sessionKey);
                    listContainer.removeView(bubble);
                    Toast.makeText(this, removed != null ? "已删除" : "已从界面移除",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
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
        b.setTextColor(getColorCompat(R.color.operit_text_primary));
        b.setLineSpacing(2f, 1f);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setElevation(dp(1));
        b.setBackgroundResource(R.drawable.bg_tool);
        String pretty = prettyJson(argsText);
        String head = "🔧 正在调用工具：" + (toolName == null ? "…" : toolName)
                + "\n\n⚙ 参数：" + (pretty.isEmpty() ? "（无）" : pretty);
        String statusLine = "\n\n状态：执行中 🔄";
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
            // v1.35.0：长结果默认折叠，避免长工具输出占满屏
            final boolean longResult = full.length() > TOOL_COLLAPSE_CHARS;
            if (full.isEmpty()) {
                text.append("\n\n（无返回内容）");
            } else if (longResult) {
                String brief = full.substring(0, TOOL_BRIEF_CHARS) + "…";
                text.append("\n\n（结果较长，已折叠，点击展开）\n\n📋 结果：\n").append(brief);
            } else {
                text.append("\n\n📋 结果：\n").append(full);
            }
            int statusColor = ok == null ? getColorCompat(R.color.operit_text_secondary)
                    : (ok ? 0xFF2E7D32 : 0xFFC62828);
            SpannableString ss = new SpannableString(text.toString());
            int start = text.indexOf(status);
            ss.setSpan(new ForegroundColorSpan(statusColor), start, start + status.length(),
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            curToolBubble.setText(ss);
            // v1.35.0：长结果初始标记为「未展开」，短结果保持展开态
            curToolBubble.setTag(!longResult);
        }
        curToolBubble = null;
    }

    /** 工具卡片点击：在完整内容与摘要之间切换（默认完全展开） */
    private void toggleToolCard(TextView b) {
        if (curToolFull == null || curToolFull.isEmpty()) return;
        boolean showingFull = Boolean.TRUE.equals(b.getTag());
        String name = curToolName.isEmpty() ? "工具" : curToolName;
        if (showingFull) {
            String brief = curToolFull.length() > TOOL_BRIEF_CHARS
                    ? curToolFull.substring(0, TOOL_BRIEF_CHARS) + "…" : curToolFull;
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
        thinkingStage = 0;
        TextView b = newTextViewBubble();
        b.setText(THINKING_STAGES[0]);
        b.setTag("thinking");
        listContainer.addView(b);
        thinkingBubble = b;
        thinkingUpdater = new Runnable() {
            @Override
            public void run() {
                if (thinkingBubble == null || thinkingStage >= THINKING_STAGES.length - 1) return;
                thinkingStage++;
                if (thinkingBubble != null) {
                    thinkingBubble.setText(THINKING_STAGES[thinkingStage]);
                }
            }
        };
        handler.postDelayed(thinkingUpdater, 2200);
        scrollToBottom();
    }

    private void hideThinkingDot() {
        if (thinkingUpdater != null) {
            handler.removeCallbacks(thinkingUpdater);
            thinkingUpdater = null;
        }
        thinkingBubble = null;
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
                    // v1.34.0：清空会话后失效建议缓存，避免复用旧建议
                    cachedSuggestionKey = null;
                    cachedSuggestions = null;
                    hideSuggestions();
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
        scroll.post(() -> {
            View child = scroll.getChildAt(0);
            if (child == null) return;
            scroll.smoothScrollTo(0, child.getBottom());
        });
    }

    private ImageButton iconButton(int res) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        // v1.37.0：无障碍描述（当前工厂仅用于返回键）
        if (res == R.drawable.ic_back) b.setContentDescription("返回");
        return b;
    }

    /** 导出当前会话：弹格式选择，再走系统分享面板分发 */
    private void exportChat() {
        if (chatStore == null) chatStore = new ChatStore(this);
        List<ChatStore.StoredMsg> msgs = chatStore.getMessages(sessionKey, 1000);
        if (msgs.isEmpty()) {
            Toast.makeText(this, "本会话暂无消息可导出", Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("导出对话")
                .setMessage("选择导出格式\n\n" + msgs.size() + " 条消息")
                .setPositiveButton("Markdown", (d, w) -> doExport("md"))
                .setNegativeButton("纯文本", (d, w) -> doExport("txt"))
                .setNeutralButton("取消", null)
                .show();
    }

    private void doExport(String format) {
        try {
            String content = "md".equals(format) ? buildMarkdownExport() : buildTextExport();
            String subject = (title == null || title.isEmpty() ? "对话" : title) + " 导出";
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, subject);
            send.putExtra(Intent.EXTRA_TEXT, content);
            startActivity(Intent.createChooser(send, "分享导出的对话"));
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + UiKit.safeMsg(e), Toast.LENGTH_SHORT).show();
        }
    }

    private String buildMarkdownExport() {
        List<ChatStore.StoredMsg> msgs = chatStore.getMessages(sessionKey, 1000);
        StringBuilder sb = new StringBuilder();
        String exportTitle = (title == null || title.isEmpty() ? "对话" : title);
        sb.append("# ").append(exportTitle).append(" - 会话导出\n\n");
        sb.append("> 导出时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date())).append("\n");
        sb.append("> 会话类型：").append(isCare ? "护理大脑" : "对话大脑").append("\n");
        sb.append("> 消息数量：").append(msgs.size()).append("\n\n");
        sb.append("---\n\n");
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
        for (ChatStore.StoredMsg m : msgs) {
            String time = timeFmt.format(new Date(m.timestamp));
            if ("user".equals(m.role)) {
                sb.append("## 用户 · ").append(time).append("\n\n");
                sb.append(m.content == null ? "" : m.content).append("\n\n");
            } else if ("tool".equals(m.role)) {
                sb.append("## 工具结果 · ").append(time).append("\n\n");
                sb.append("```\n").append(m.content == null ? "" : m.content).append("\n```\n\n");
            } else if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                sb.append("## AI 调用工具 · ").append(time).append("\n\n");
                sb.append("**工具**：").append(parseToolName(m.toolCalls)).append("\n\n");
                sb.append("**参数**：\n```json\n").append(parseToolArgs(m.toolCalls)).append("\n```\n\n");
            } else {
                sb.append("## AI · ").append(time).append("\n\n");
                sb.append(m.content == null ? "" : m.content).append("\n\n");
            }
            sb.append("---\n\n");
        }
        sb.append("\n*由「数字生命」导出*\n");
        return sb.toString();
    }

    private String buildTextExport() {
        List<ChatStore.StoredMsg> msgs = chatStore.getMessages(sessionKey, 1000);
        StringBuilder sb = new StringBuilder();
        sb.append("[【").append(title == null || title.isEmpty() ? "对话" : title).append("】]\n");
        sb.append("导出时间：").append(new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(new Date())).append("\n");
        sb.append("会话类型：").append(isCare ? "护理大脑" : "对话大脑").append("\n");
        sb.append("消息数：").append(msgs.size()).append("\n\n");
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);
        for (ChatStore.StoredMsg m : msgs) {
            String time = timeFmt.format(new Date(m.timestamp));
            if ("user".equals(m.role)) {
                sb.append("[").append(time).append("] 用户：\n").append(m.content == null ? "" : m.content).append("\n\n");
            } else if ("tool".equals(m.role)) {
                sb.append("[").append(time).append("] 工具结果：\n").append(m.content == null ? "" : m.content).append("\n\n");
            } else if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                sb.append("[").append(time).append("] AI 调用工具：").append(parseToolName(m.toolCalls)).append("\n");
                sb.append("参数：").append(parseToolArgs(m.toolCalls)).append("\n\n");
            } else {
                sb.append("[").append(time).append("] AI：\n").append(m.content == null ? "" : m.content).append("\n\n");
            }
        }
        return sb.toString();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (careAI != null && careListener != null) careAI.removeListener(careListener);
        if (llm != null) llm.cancel();
        // v1.31.0：释放语音识别资源，避免泄漏
        if (speechRecognizer != null) {
            try {
                speechRecognizer.stopListening();
                speechRecognizer.cancel();
                speechRecognizer.destroy();
            } catch (Exception ignored) {
            }
            speechRecognizer = null;
            listening = false;
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        if (thinkingUpdater != null) {
            handler.removeCallbacks(thinkingUpdater);
            thinkingUpdater = null;
        }
        if (scrollWatcher != null) {
            try {
                scroll.getViewTreeObserver().removeOnScrollChangedListener(scrollWatcher);
            } catch (Exception ignored) {
            }
        }
    }
}
