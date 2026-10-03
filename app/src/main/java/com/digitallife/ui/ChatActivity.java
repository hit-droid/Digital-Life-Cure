package com.digitallife.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.FrameLayout;
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
import com.digitallife.harness.ToolApprovalPolicy;
import com.digitallife.service.PetService;
import com.digitallife.ui.chat.ChatTextOps;
import com.digitallife.ui.chat.FailoverPolicy;
import com.digitallife.ui.chat.HistoryBudget;
import com.digitallife.ui.chat.ScrollAnchor;
import com.digitallife.ui.chat.SuggestionEngine;
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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

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
    /** v1.141.0（#43）：判定「已离开底部」的底部间隙阈值（dp） */
    private static final int SCROLL_BOTTOM_THRESHOLD_DP = 140;
    /** v1.141.0（#43）：回到底部按钮的未读累积 / 清零逻辑 */
    private final ScrollAnchor scrollAnchor = new ScrollAnchor();
    /** 记录上一次已计入的行数，用行数差把「一条消息」和「流式多次刷新」区分开 */
    private int lastRowCount = 0;

    /**
     * v1.142.0（#57）：按添加顺序登记的 assistant 气泡。
     *
     * <p>「删除 / 重新生成」只能作用于最后一条 assistant 消息——数据库侧目前只有
     * {@code ChatStore.deleteLastAssistantMessage}。此前不校验所点的是哪一条，
     * 界面移除所点的、数据库删掉最后的，两边对不上。这里记下顺序好做判定。</p>
     */
    private final ArrayList<TextView> aiBubbles = new ArrayList<>();
    private TextView btnScrollBottom;
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
    /** v1.120.0：长期记忆（对话内容作为记忆源，供检索与自动提取；护理大脑不参与） */
    private com.digitallife.util.MemoryStore chatMemory;

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
        // App 启动时重排定时任务闹钟（进程被杀/重启后 AlarmManager 注册会丢）
        com.digitallife.brain.TaskScheduler.rescheduleAll(this);
        // 首次启动复制内置 SKILL.md 技能到 files/skills/
        com.digitallife.skill.SkillManager.ensureDefaults(this);
        sessionKey = getIntent().getStringExtra(EXTRA_SESSION);
        title = getIntent().getStringExtra(EXTRA_TITLE);
        type = getIntent().getStringExtra(EXTRA_TYPE);
        modelName = getIntent().getStringExtra(EXTRA_MODEL);
        if (sessionKey == null) sessionKey = ChatStore.SESSION_CARE;
        isCare = ChatStore.TYPE_CARE.equals(type);
        if (!isCare) chatMemory = new com.digitallife.util.MemoryStore(this);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        mdRenderer = new MarkdownRenderer(
                getColorCompat(R.color.code_bg),
                getColorCompat(R.color.code_text),
                getColorCompat(R.color.operit_text_secondary),
                getColorCompat(R.color.operit_text_primary),
                getColorCompat(R.color.operit_accent),
                getColorCompat(R.color.operit_divider));
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
        btnMore.setHapticFeedbackEnabled(true);   // 自动生成：haptic
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
            boolean away = ScrollAnchor.shouldShow(bottomGap, dp(SCROLL_BOTTOM_THRESHOLD_DP));
            // v1.141.0（#43）：回到（或被带回）底部即清零未读
            if (userScrolledAway && !away) scrollAnchor.clear();
            userScrolledAway = away;
            updateScrollBottomButton();
        };
        scroll.getViewTreeObserver().addOnScrollChangedListener(scrollWatcher);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(dp(12), dp(8), dp(12), dp(8));
        scroll.addView(listContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // v1.141.0（#43）：列表外套一层 FrameLayout，把「回到底部」浮动按钮压在右下角
        FrameLayout listFrame = new FrameLayout(this);
        listFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        btnScrollBottom = buildScrollBottomButton();
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fabLp.gravity = Gravity.BOTTOM | Gravity.END;
        fabLp.setMargins(0, 0, dp(14), dp(14));
        listFrame.addView(btnScrollBottom, fabLp);
        root.addView(listFrame, new LinearLayout.LayoutParams(
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

        // v1.142.0（#55）：附件按钮两种模式都建。此前只在护理模式下创建，可
        // hint 对普通会话写着「＋ 可附带文件」，pickAttach / onActivityResult /
        // attachBar / buildFileContext 整条链路也都不限模式（普通会话发 .txt/.md/
        // .json/.java 等文本文件本就能读进上下文）——唯独按钮没建，等于死代码。
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
        btnVoice.setHapticFeedbackEnabled(true);   // 自动生成：haptic
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
        btnSendView.setHapticFeedbackEnabled(true);   // 自动生成：haptic
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
                appendUserBubble(m.content, m.timestamp);
            } else if ("tool".equals(m.role)) {
                markLastToolResult(null, null, m.content);
            } else if (m.toolCalls != null && !m.toolCalls.isEmpty()) {
                appendToolBubble(ChatTextOps.parseToolName(m.toolCalls),
                        ChatTextOps.parseToolArgs(m.toolCalls));
            } else if (m.content != null && !m.content.isEmpty()) {
                appendAiBubble(m.content, m.timestamp);
            }
        }
        // v1.142.0（#55）：历史渲染完两件事——
        // 1) 直接落到最新一条。此前打开有历史的会话停在顶部，看到的是最旧的消息，
        //    得自己滑到底才能接上上下文（scrollToBottom 只在发消息时调）。
        // 2) 把已渲染行数同步给未读计数。lastRowCount 原先只在追加新行时更新，
        //    历史恢复后仍是 0，于是上翻后到来的第一条新消息会把整屏历史全算成未读
        //    （浮标一上来就是「101 条新消息」）。
        lastRowCount = listContainer.getChildCount();
        jumpToLatestOnce();
    }

    /**
     * v1.142.0（#55）：一次性把列表滚到最底部。
     *
     * <p>用 {@code OnPreDrawListener} 而不是 {@code post}：首帧绘制前布局量已完成，
     * 此时 {@code getBottom()} 才准；用 {@code post} 有可能赶在 measure 之前跑，滚不到位。
     * 长列表直接 {@code scrollTo} 不跑平滑动画——打开会话就该直接看到最新一条。</p>
     */
    private void jumpToLatestOnce() {
        if (scroll == null) return;
        scroll.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        android.view.ViewTreeObserver obs = scroll.getViewTreeObserver();
                        if (obs.isAlive()) obs.removeOnPreDrawListener(this);
                        View child = scroll.getChildAt(0);
                        if (child != null) scroll.scrollTo(0, child.getBottom());
                        return true;
                    }
                });
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
            rememberUser(text.isEmpty() ? display : text);
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
            // ByteArrayOutputStream#toString(Charset) 需 API 33，改用 String 构造器兼容 minSdk 21
            String content = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
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
            rememberUser(text);
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

    /**
     * v1.120.0：把对话内容并入长期记忆，使对话成为记忆源
     * （可被相关召回注入 prompt，也可被自动提取沉淀为 facts）。护理大脑不参与。
     */
    private void rememberUser(String text) {
        if (isCare || chatMemory == null || text == null || text.trim().isEmpty()) return;
        chatMemory.addUserMessage(text);
    }

    /** 见 {@link #rememberUser}；assistant 侧需先剥掉折叠/中断角标再入库 */
    private void rememberAssistant(String text) {
        if (isCare || chatMemory == null) return;
        String clean = ChatTextOps.stripCollapseHint(text);
        if (clean == null || clean.trim().isEmpty()) return;
        chatMemory.addAssistantMessage(clean);
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
                if (harness != null) harness.cancel();
                llm.cancel();
                // v1.111.0：连同正在运行的子智能体一起停（v1.117.0 可能并行多个）
                synchronized (subAgentLlms) {
                    for (LLMClient c : subAgentLlms) {
                        if (c != null) c.cancel();
                    }
                    subAgentLlms.clear();
                }
            }
        } catch (Exception ignored) {
        }
        if (curAssistantBubble != null && !curAssistantText.isEmpty()) {
            // 中断回复：末尾附加「⏹ 已中断」角标，并随消息一起持久化
            String finalText = curAssistantText + ChatTextOps.INTERRUPT_MARK;
            chatStore.addMessage(sessionKey, "assistant", finalText,
                    null, null, System.currentTimeMillis());
            rememberAssistant(curAssistantText);
            markInterrupted(curAssistantBubble);
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
        String fingerprint = SuggestionEngine.fingerprint(hist);
        if (fingerprint.equals(cachedSuggestionKey) && cachedSuggestions != null) {
            renderSuggestions(cachedSuggestions);
            return;
        }

        if (hist.isEmpty()) {
            // 冷启动：用 settings 里的 petName 生成首次建议
            String[] cold = SuggestionEngine.coldStart(new Settings(this).getPetName());
            cachedSuggestionKey = fingerprint;
            cachedSuggestions = cold;
            renderSuggestions(cold);
            return;
        }

        String context = SuggestionEngine.buildPrompt(new Settings(this).getPetName(), hist);

        List<LLMClient.ChatMessage> msgs = new ArrayList<>();
        msgs.add(new LLMClient.ChatMessage("user", context));
        JSONObject extra = new JSONObject();
        try {
            extra.put("system", "你只输出 JSON 数组，不要任何解释。");
        } catch (Exception ignored) {}

        llm.chatOnce(msgs, extra, (text, err) -> {
            if (err != null || text == null) {
                handler.post(() -> hideSuggestions());   // v1.33.0
                return;
            }
            String[] suggestions = SuggestionEngine.parse(text);
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
        llm = newChatLlmClient();
    }

    /** 按当前对话配置新建一个模型客户端实例（主对话与子智能体各用各的实例） */
    private LLMClient newChatLlmClient() {
        ApiManager am = new ApiManager(this);
        ApiProfile p = am.getCurrent(ApiManager.SCOPE_CHAT);
        if (p != null && p.baseUrl != null && !p.baseUrl.isEmpty()
                && !p.effectiveKeys().isEmpty()) {
            LLMClient c = new LLMClient(p.baseUrl, am.nextKey(ApiManager.SCOPE_CHAT, p.id), p.model);
            // v1.26.0：注入完整密钥池，401/429 时 LLMClient 自动轮换
            c.setApiKeys(p.effectiveKeys());
            return c;
        }
        Settings s = new Settings(this);
        return new LLMClient(s.getApiBase(), s.getApiKey(), s.getModel());
    }

    // ==================== v1.30.0：对话上下文预算裁剪 ====================

    /** 跨配置故障转移：本次发送已尝试过的 profile id（防止循环切换） */
    private final java.util.Set<String> failoverTriedIds = new java.util.HashSet<>();
    /** 故障转移自动重发期间不清空已尝试集合 */
    private boolean inFailoverResend = false;

    // ==================== 对话大脑工具调用（v1.110.0） ====================

    /** 对话大脑工具宿主：仅注册 BuiltinTools/MCP/插件工具，不含桌宠表现工具 */
    private com.digitallife.brain.Tools chatTools;
    /** 本轮历史投影（交给 DeepSeek Harness 的 session log） */
    private final java.util.List<LLMClient.ChatMessage> chatLiveMsgs = new java.util.ArrayList<>();
    /** DeepSeek Harness：一切皆插件的对话循环 */
    private com.digitallife.harness.DeepSeekHarness harness;
    /** v1.141.0（#40）：危险工具审批策略；会话级放行集需在 Activity 生命周期内保持 */
    private final com.digitallife.harness.ToolApprovalPolicy toolApprovalPolicy =
            new com.digitallife.harness.ToolApprovalPolicy();
    /** v1.141.0（#40）：等待用户审批的超时秒数，超时按拒绝处理，避免工具线程卡死 */
    private static final int APPROVAL_TIMEOUT_SEC = 120;
    /** v1.111.0：正在运行的子智能体客户端（用户点停止时一并取消）。
     *  v1.117.0 支持并行子智能体后可能同时存在多个，故用列表跟踪。 */
    private final java.util.List<LLMClient> subAgentLlms =
            java.util.Collections.synchronizedList(new java.util.ArrayList<LLMClient>());
    /** 子智能体协作过程的可视化（严格成对的「建气泡→回填」） */
    private final com.digitallife.harness.subagent.SubagentRunner.ProgressListener subAgentProgress =
            (agent, phase, detail) -> runOnUiThread(() -> {
                if ("tool".equals(phase)) {
                    appendToolBubble(agent + " · " + detail.split(" ")[0],
                            detail.contains(" ") ? detail.substring(detail.indexOf(' ') + 1) : "");
                } else if ("result".equals(phase)) {
                    markLastToolResult(null, Boolean.TRUE, detail);
                }
            });
    /** v1.117.0 并行协作：每个子智能体一张独立卡片，按「agent + 任务」索引，
     *  并发下（含同一 agent 接多个任务）互不串扰 */
    private final java.util.Map<String, TextView> teamCards = new java.util.HashMap<>();
    private final java.util.Map<String, String> teamTasks = new java.util.HashMap<>();
    private final com.digitallife.harness.subagent.SubagentRunner.ProgressListener teamProgress =
            new com.digitallife.harness.subagent.SubagentRunner.ProgressListener() {
                @Override
                public void onStep(String agent, String phase, String detail) {
                    // 并行批次的工具级事件不单独出气泡，统一收敛到团队卡片
                }

                @Override
                public void onTeamStep(String agent, String task, String phase, String detail) {
                    runOnUiThread(() -> {
                        String key = teamKey(agent, task);
                        if ("start".equals(phase)) {
                            appendTeamCard(key, agent, task);
                        } else if ("team-ok".equals(phase)) {
                            finishTeamCard(key, true, detail);
                        } else if ("team-fail".equals(phase)) {
                            finishTeamCard(key, false, detail);
                        }
                    });
                }
            };

    /** 团队卡片索引：同一 agent 接多个任务时靠任务原文区分 */
    private static String teamKey(String agent, String task) {
        String a = (agent == null || agent.isEmpty()) ? "子智能体" : agent;
        return a + "\u0000" + (task == null ? "" : task);
    }

    private void sendChatMessage(String text, String attachContext) {
        // v1.42.0：记录原文，失败时可一键重试
        lastUserText = text;
        lastAttachContext = attachContext;
        // 新的发送意图：清空故障转移记录（自动重发期间保留）
        if (!inFailoverResend) failoverTriedIds.clear();
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
        List<ChatStore.StoredMsg> hist = HistoryBudget.trim(
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

        // 本轮历史投影交给 DeepSeek Harness 的 session log
        chatLiveMsgs.clear();
        chatLiveMsgs.addAll(msgs);
        startChatLoop();
    }

    /** 确保对话大脑工具宿主就绪：BuiltinTools + delegate_task（子智能体 seam） */
    private void ensureChatTools() {
        if (chatTools != null) return;
        chatTools = new com.digitallife.brain.Tools(true);
        com.digitallife.tools.BuiltinTools.install(chatTools, getApplicationContext());
        com.digitallife.harness.subagent.SubagentRunner.installDelegateTool(
                chatTools, this::newSubAgentLlm, subAgentProgress);
        // v1.117.0：并行委派（一次多个互不依赖的子任务）
        com.digitallife.harness.subagent.SubagentRunner.installParallelDelegateTool(
                chatTools, this::newSubAgentLlm, teamProgress);
    }

    /** 子智能体专用模型客户端：与主对话同配置，但独立实例（互不干扰 tools/线程池） */
    private LLMClient newSubAgentLlm() {
        LLMClient c = newChatLlmClient();
        subAgentLlms.add(c);
        return c;
    }

    /** 发起一次 DeepSeek Harness 轮次：session log 投影历史，agent-loop 驱动 step */
    private void startChatLoop() {
        ensureChatLlm();
        if (llm == null || llm.getBaseUrl() == null || llm.getBaseUrl().isEmpty()) {
            runOnUiThread(() -> {
                hideThinkingDot();
                thinking = false;
                updateSendButton();
            });
            return;
        }
        // 新一轮：上一轮的子智能体客户端已结束，清空跟踪列表
        subAgentLlms.clear();
        ensureChatTools();
        if (harness == null) {
            harness = com.digitallife.harness.DeepSeekHarness.boot(getApplicationContext());
        }
        installToolApproval(harness);
        // v1.120.0：把当轮用户问题交给记忆插件，做「查询相关召回」
        harness.context().provide(com.digitallife.harness.plugin.MemoryPlugin.KEY_QUERY,
                lastUserText == null ? "" : lastUserText);
        harness.startTurn(llm, chatTools, chatPromptOverlay(), chatLiveMsgs,
                new com.digitallife.harness.AgentHandle.Listener() {
                    @Override
                    public void onDelta(String t) {
                        runOnUiThread(() -> {
                            if (curAssistantBubble == null) {
                                hideThinkingDot();
                                curAssistantText = "";
                                curAssistantBubble = newTextViewBubble();
                                // v1.130.0：流式回复气泡同样带时间戳
                                listContainer.addView(wrapBubble(curAssistantBubble,
                                        System.currentTimeMillis(), false));
                                trackAssistant(curAssistantBubble);
                            }
                            curAssistantText += t;
                            curAssistantBubble.setText(mdRenderer != null
                                    ? mdRenderer.render(curAssistantText) : curAssistantText);
                            scrollToBottom();
                        });
                    }

                    @Override
                    public void onToolCall(String name, JSONObject args, String toolCallId) {
                        if (name == null || name.isEmpty()) return;
                        JSONObject a = args != null ? args : new JSONObject();
                        runOnUiThread(() -> appendToolBubble(name, a.toString()));
                    }

                    @Override
                    public void onToolResult(String name, boolean ok, String result) {
                        runOnUiThread(() -> markLastToolResult(name, ok,
                                result != null ? result : ""));
                    }

                    @Override
                    public void onDone(String fullText) {
                        runOnUiThread(() -> {
                            if (consumeAbort()) return;
                            if ((fullText == null || fullText.isEmpty())
                                    && harness != null && harness.endedOnTool()) {
                                appendAiBubble("（工具调用次数较多，已自动收尾，有需要可以再问我）");
                                hideThinkingDot();
                                thinking = false;
                                updateSendButton();
                                return;
                            }
                            maybeAutoTitle();
                            if (curAssistantBubble != null && !curAssistantText.isEmpty()) {
                                chatStore.addMessage(sessionKey, "assistant", curAssistantText,
                                        null, null, System.currentTimeMillis());
                                rememberAssistant(curAssistantText);
                                applyCollapse(curAssistantBubble);
                                curAssistantBubble = null;
                                curAssistantText = "";
                            } else if (fullText != null && !fullText.isEmpty()) {
                                chatStore.addMessage(sessionKey, "assistant", fullText,
                                        null, null, System.currentTimeMillis());
                                rememberAssistant(fullText);
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
                            if (tryModelFailover(error)) return;
                            hideThinkingDot();
                            appendAiBubble("出错了：" + error);
                            thinking = false;
                            updateSendButton();
                        });
                    }

                    @Override
                    public void onTurn(String phase) {
                    }
                });
    }

    /**
     * 参考 OpenMinis 的模型组设计：当前对话配置失败时，自动切换到同 scope 的
     * 下一个可用配置并重发本条消息。每个配置每次发送最多尝试一次，全部失败才报错。
     */
    private boolean tryModelFailover(String error) {
        if (isCare) return false; // 护理大脑由 CareAI 自行管理密钥池
        if (lastUserText == null || lastUserText.isEmpty()) return false;
        if (!FailoverPolicy.isFailoverable(error)) return false;
        ApiManager am = new ApiManager(this);
        ApiProfile cur = am.getCurrent(ApiManager.SCOPE_CHAT);
        if (cur != null) failoverTriedIds.add(cur.id);
        ApiProfile next = null;
        for (ApiProfile p : am.list(ApiManager.SCOPE_CHAT)) {
            if (failoverTriedIds.contains(p.id)) continue;
            if (p.baseUrl == null || p.baseUrl.isEmpty() || p.effectiveKeys().isEmpty()) continue;
            next = p;
            break;
        }
        if (next == null) return false;
        failoverTriedIds.add(next.id);
        am.setCurrent(ApiManager.SCOPE_CHAT, next.id);
        String fromName = cur != null && cur.name != null && !cur.name.isEmpty()
                ? cur.name : "当前模型";
        String toName = next.name != null && !next.name.isEmpty()
                ? next.name : (next.model != null ? next.model : "备用模型");
        // 仅作界面提示，不写入聊天存储，避免污染后续上下文
        appendAiBubble("「" + fromName + "」暂时不可用，已自动切换到备用模型「" + toName
                + "」，正在重新回复…");
        inFailoverResend = true;
        try {
            sendChatMessage(lastUserText, lastAttachContext);
        } finally {
            inFailoverResend = false;
        }
        return true;
    }

    /**
     * 普通对话的人设由 PersonaPlugin 织进 prompt；模型小房间才覆盖 persona 段。
     * 时间由 ClockPlugin 每步刷新，不在这里写。
     */
    private String chatPromptOverlay() {
        if (!ChatStore.TYPE_MODEL.equals(type) || modelName == null || modelName.isEmpty()) {
            return null;
        }
        return "你是「" + modelName + "」，一个住在用户手机里的 AI 二次元少女，是用户亲密的朋友。\n"
                + "你活泼可爱，有一点小傲娇，关心用户但绝不啰嗦。\n"
                + "说话简短自然，一般不超过 80 字，用口语化的二次元语气，亲近但不腻。\n"
                + "直接以纯文本回复，不要输出 JSON 或任何标记。";
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
                        // v1.130.0：流式回复气泡同样带时间戳
                        listContainer.addView(wrapBubble(curAssistantBubble,
                                System.currentTimeMillis(), false));
                        trackAssistant(curAssistantBubble);
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

    /** 相邻消息间隔超过 5 分钟时插入居中的时间标签（iMessage 风格）
     *  @param ts 该条消息的真实时间戳（毫秒）；<=0 时回退为当前时间 */
    private void appendTimeDividerIfNeeded(long ts) {
        long msgTs = ts > 0 ? ts : System.currentTimeMillis();
        long now = System.currentTimeMillis();
        if (lastTsLabel != 0 && Math.abs(msgTs - lastTsLabel) < 5 * 60 * 1000L) return;
        lastTsLabel = msgTs;
        TextView t = new TextView(this);
        // v1.38.0：智能时间分割线（今天 / 昨天 / 更早）
        // 修复：此前一律按「当前时间」渲染，恢复历史会话时时间条显示的是打开时间而非发送时间
        t.setText(ChatTextOps.formatDividerTime(msgTs, now, Locale.getDefault()));
        t.setTextSize(10f);
        t.setTextColor(getColorCompat(R.color.operit_text_hint));
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, dp(6));
        listContainer.addView(t, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < listContainer.getChildCount(); i++) {
            android.view.View child = listContainer.getChildAt(i);
            // v1.130.0：气泡可能包在「气泡+时间戳」容器里，取里面的气泡来匹配
            TextView bubble = bubbleOf(child);
            if (bubble == null) continue;
            String txt = bubble.getText() == null ? "" : bubble.getText().toString();
            if (txt.toLowerCase(java.util.Locale.ROOT).contains(lower)) searchHits.add(i);
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
        // v1.130.0：高亮要落在气泡上，不是外层容器
        TextView bubble = bubbleOf(child);
        if (bubble != null) {
            highlightText(bubble, searchQuery);
        }
    }

    /** 命中关键词染成品牌色，搜索词变更时重绘即可复原（setText 会重建 span） */
    private void highlightText(TextView tv, String query) {
        if (tv == null || query == null || query.isEmpty()) return;
        String text = tv.getText() == null ? "" : tv.getText().toString();
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        String q = query.toLowerCase(java.util.Locale.ROOT);
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
                removeBubble(b);
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

    /**
     * v1.127.0：把消息以引用块形式放进输入框（5.4 第 2 条「引用回复」）。
     * 引用块的纯文本拼装在 {@link ChatTextOps#buildQuote} 里，这里只负责塞进输入框。
     */
    private void quoteIntoInput(String text) {
        String quote = ChatTextOps.buildQuote(text);
        if (quote.isEmpty()) return;
        String cur = etInput.getText() == null ? "" : etInput.getText().toString();
        String merged = cur.isEmpty() ? quote
                : (cur.endsWith("\n") ? cur + quote : cur + "\n" + quote);
        etInput.setText(merged);
        etInput.setSelection(merged.length());
        etInput.requestFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.showSoftInput(etInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        }
    }

    // ==================== 气泡时间戳（v1.130.0，5.4 第 1 条） ====================

    /**
     * v1.130.0：气泡下方的时间戳 {@code HH:mm}。
     * 左右对齐交给外层容器的 gravity，这里只管字号/颜色。
     */
    private TextView newBubbleTime(long ts) {
        TextView t = new TextView(this);
        t.setText(ChatTextOps.formatBubbleTime(ts));
        t.setTextSize(11f);
        t.setTextColor(getColorCompat(R.color.operit_text_secondary));
        t.setAlpha(0.7f);
        t.setPadding(dp(10), dp(2), dp(10), 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /**
     * v1.130.0：把气泡包进「气泡 + 时间戳」的纵向容器。
     * <p>包一层会让气泡不再是 {@code listContainer} 的直接子视图，因此所有
     * 遍历/移除子视图的地方都必须走 {@link #bubbleOf} / {@link #removeBubble}，
     * 否则搜索找不到气泡、删除会留下孤儿时间条。</p>
     *
     * @param mine true=自己的消息（靠右），false=对方（靠左）
     */
    private LinearLayout wrapBubble(TextView bubble, long ts, boolean mine) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(mine ? Gravity.END : Gravity.START);
        wrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(4);
        wrap.addView(bubble, blp);
        wrap.addView(newBubbleTime(ts));
        return wrap;
    }

    /**
     * v1.130.0：移除气泡。气泡被包在容器里时连容器一起移除，
     * 否则只移除气泡会把时间戳留在列表里变成孤儿视图。
     */
    private void removeBubble(View v) {
        if (listContainer == null || v == null) return;
        // v1.142.0（#57）：登记必须在两个 return 分支之前清掉，否则被包在容器里的
        // 气泡（历史 / 流式回复都是）移除后仍留在 aiBubbles 里，会被当成「最后一条」
        aiBubbles.remove(v);
        android.view.ViewParent p = v.getParent();
        if (p instanceof View && p != listContainer && p.getParent() == listContainer) {
            listContainer.removeView((View) p);
            return;
        }
        listContainer.removeView(v);
    }

    /**
     * v1.142.0（#57）：登记一条 assistant 气泡。
     *
     * <p>历史恢复、非流式回复、两处流式回复（普通 / 护理）都要登记，漏掉任意一处，
     * 那条回复就会被误判成「不是最后一条」而删不掉——所以收口成一个方法。</p>
     */
    private void trackAssistant(TextView bubble) {
        if (bubble == null) return;
        aiBubbles.remove(bubble);   // 防重复登记
        aiBubbles.add(bubble);
    }

    /**
     * v1.142.0（#57）：所点气泡是不是当前最后一条 assistant 气泡。
     *
     * <p>只认最后一条：数据库侧删的是最后一条 assistant 消息，对中间某条下手会让
     * 界面和数据库各改一条，重开会话后界面上删掉的那条还在、库里最后一条却没了。</p>
     */
    private boolean isLastAssistantBubble(TextView bubble) {
        if (bubble == null || aiBubbles.isEmpty()) return false;
        return aiBubbles.get(aiBubbles.size() - 1) == bubble;
    }

    /**
     * v1.130.0：从 {@code listContainer} 的直接子视图里取出气泡。
     * 加了时间戳后气泡是容器（{@link #wrapBubble}）的第 0 个子视图；
     * 没加时间戳的（工具气泡、错误气泡、时间分隔线）本身就是 TextView。
     */
    private TextView bubbleOf(View child) {
        if (child instanceof TextView) return (TextView) child;
        if (child instanceof ViewGroup) {
            View v0 = ((ViewGroup) child).getChildAt(0);
            if (v0 instanceof TextView) return (TextView) v0;
        }
        return null;
    }

    private void appendUserBubble(String text) {
        appendUserBubble(text, System.currentTimeMillis());
    }

    /** @param ts 消息真实时间戳（毫秒），用于渲染时间条；<=0 时按当前时间处理 */
    private void appendUserBubble(String text, long ts) {
        appendTimeDividerIfNeeded(ts);
        LinearLayout row = new LinearLayout(this);
        // v1.130.0：纵向——气泡在上、时间戳在下；gravity=END 让两者一起靠右
        row.setOrientation(LinearLayout.VERTICAL);
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
        // v1.130.0：气泡自己的时间戳（5.4 第 1 条）
        row.addView(newBubbleTime(ts));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(2);
        listContainer.addView(row, rlp);
        bubble.setOnLongClickListener(v -> {
            String txt = bubble.getText() == null ? "" : bubble.getText().toString();
            new android.app.AlertDialog.Builder(this)
                    .setTitle("消息操作")
                    // v1.76.0：补上「朗读」，与 AI 气泡菜单保持一致
                    // v1.127.0：新增「引用回复」（索引 2），其后项索引顺延
                    .setItems(new String[]{"朗读", "复制", "引用回复", "重新发送", "分享"}, (d, w) -> {
                        if (w == 0) {
                            speakText(txt);
                        } else if (w == 1) {
                            copyToClipboard(txt);
                        } else if (w == 2) {
                            quoteIntoInput(txt);
                        } else if (w == 3) {
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
        appendAiBubble(text, System.currentTimeMillis());
    }

    /** @param ts 消息真实时间戳（毫秒），用于渲染时间条；<=0 时按当前时间处理 */
    private void appendAiBubble(String text, long ts) {
        appendTimeDividerIfNeeded(ts);
        TextView b = newTextViewBubble();
        b.setText(mdRenderer != null ? mdRenderer.render(text) : text);
        // v1.130.0：气泡 + 时间戳一起挂载（布局参数由 wrapBubble 统一设置）
        listContainer.addView(wrapBubble(b, ts, false));
        trackAssistant(b);
        // v1.28.0：长消息折叠（历史消息/非流式回复同样生效）
        applyCollapse(b);
        b.setOnLongClickListener(v -> {
            String txt = b.getText() == null ? "" : b.getText().toString();
            // v1.28.0：去掉折叠提示尾巴，避免复制/朗读带出「▸ 展开全文」
            txt = ChatTextOps.stripCollapseHint(txt);
            final String clean = txt;
            new android.app.AlertDialog.Builder(this)
                    .setTitle("消息操作")
                    // v1.29.0：新增「重新生成」「删除」
                    // v1.127.0：新增「引用回复」（索引 2），其后项索引顺延
                    .setItems(new String[]{"朗读", "复制", "引用回复", "分享", "重新生成", "删除"},
                            (d, w) -> {
                        if (w == 0) {
                            speakText(clean);
                        } else if (w == 1) {
                            copyToClipboard(clean);
                        } else if (w == 2) {
                            quoteIntoInput(clean);
                        } else if (w == 3) {
                            shareText(clean);
                        } else if (w == 4) {
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

    /** 中断时给气泡追加「⏹ 已中断」角标（次要色小字）；同步 setText，保证随后的折叠 post 读到含角标的行数 */
    private void markInterrupted(TextView bubble) {
        if (bubble == null) return;
        CharSequence cur = bubble.getText();
        if (cur == null || cur.length() == 0) return;
        if (cur.toString().endsWith(ChatTextOps.INTERRUPT_MARK)) return;
        // 用 SpannableStringBuilder 保留原有 span（代码块复制链接、Markdown 样式等）
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(cur);
        int start = ssb.length();
        ssb.append(ChatTextOps.INTERRUPT_MARK);
        ssb.setSpan(new android.text.style.ForegroundColorSpan(
                        getColorCompat(R.color.text_secondary)),
                start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new android.text.style.RelativeSizeSpan(0.85f),
                start, ssb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        bubble.setText(ssb);
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
        // v1.142.0（#57）：只对最后一条生效。此前不校验，点中间某条会删掉库里的
        // 最后一条、移除界面上所点的那条，两条消息一起错位。
        if (!isLastAssistantBubble(bubble)) {
            Toast.makeText(this, "只能重新生成最后一条回复", Toast.LENGTH_SHORT).show();
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
        removeBubble(bubble);
        // 重新发送（走正常发送流程，会重新 append user bubble + 请求）
        sendChatMessage(lastUser, null);
        Toast.makeText(this, "已重新生成", Toast.LENGTH_SHORT).show();
    }

    /** v1.29.0：删除单条气泡（同时从数据库移除） */
    private void deleteBubble(TextView bubble) {
        // v1.142.0（#57）：数据库侧只有「删最后一条 assistant」的接口，中间某条
        // 删了界面却删不掉库——重开会话被删的那条又回来了，原本最后一条反没了。
        // 不是最后一条就明确提示，宁可不支持，也不制造界面与数据库不一致。
        if (!isLastAssistantBubble(bubble)) {
            Toast.makeText(this, "目前只能删除最后一条回复", Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除这条消息？")
                .setMessage("删除后无法恢复。")
                .setPositiveButton("删除", (d, w) -> {
                    ChatStore.StoredMsg removed = chatStore.deleteLastAssistantMessage(sessionKey);
                    removeBubble(bubble);
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
        String pretty = ChatTextOps.prettyJson(argsText);
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

    // ==================== v1.141.0（#40）：危险工具执行前确认 ====================

    /** 审批结果回调（避免用 API 24+ 的 java.util.function） */
    private interface OutcomeSink {
        void accept(ToolApprovalPolicy.Outcome outcome);
    }

    /**
     * v1.141.0（#40）：把危险工具审批接到 harness。
     *
     * <p>回调运行在工具循环的**后台线程**上：这里 post 到主线程弹确认卡片，
     * 同时阻塞后台线程等结果（{@link #APPROVAL_TIMEOUT_SEC} 秒超时按拒绝兜底）。</p>
     */
    private void installToolApproval(com.digitallife.harness.DeepSeekHarness h) {
        if (h == null || h.tools() == null) return;
        h.tools().setApproval(toolApprovalPolicy, (toolName, args, summary) -> {
            final ArrayBlockingQueue<ToolApprovalPolicy.Outcome> queue =
                    new ArrayBlockingQueue<>(1);
            runOnUiThread(() -> showToolApprovalCard(summary,
                    o -> queue.offer(o == null ? ToolApprovalPolicy.Outcome.DENY : o)));
            try {
                ToolApprovalPolicy.Outcome outcome = queue.poll(APPROVAL_TIMEOUT_SEC, TimeUnit.SECONDS);
                return outcome == null ? ToolApprovalPolicy.Outcome.DENY : outcome;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolApprovalPolicy.Outcome.DENY;
            }
        });
    }

    /** 确认卡片：显示脱敏后的工具与参数摘要，给出「仅这次 / 本会话始终 / 拒绝」三个选择 */
    private void showToolApprovalCard(String summary, OutcomeSink sink) {
        if (isFinishing() || isDestroyed() || listContainer == null) {
            sink.accept(ToolApprovalPolicy.Outcome.DENY);
            return;
        }
        hideThinkingDot();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        card.setBackgroundResource(R.drawable.bg_tool);

        TextView tv = new TextView(this);
        tv.setTextSize(13f);
        tv.setTextColor(getColorCompat(R.color.operit_text_primary));
        tv.setLineSpacing(2f, 1f);
        tv.setText("⚠️ 这个操作有风险，需要你确认后才会执行\n\n"
                + (summary == null ? "" : summary));
        card.addView(tv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        card.addView(row, rowLp);

        final boolean[] answered = {false};
        int brand = getColorCompat(R.color.brand);
        int muted = getColorCompat(R.color.operit_text_secondary);
        row.addView(makeApprovalButton("仅这次允许", ToolApprovalPolicy.Outcome.ALLOW_ONCE,
                answered, card, sink, brand));
        row.addView(makeApprovalButton("本会话始终允许", ToolApprovalPolicy.Outcome.ALLOW_SESSION,
                answered, card, sink, brand));
        row.addView(makeApprovalButton("拒绝", ToolApprovalPolicy.Outcome.DENY,
                answered, card, sink, muted));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(24);
        listContainer.addView(card, lp);
        scrollToBottom();
    }

    private TextView makeApprovalButton(String text, ToolApprovalPolicy.Outcome outcome,
                                        boolean[] answered, View card, OutcomeSink sink, int color) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(12f);
        b.setTextColor(color);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            if (answered[0]) return;   // 只认第一次点击，避免重复 resolve
            answered[0] = true;
            UiKit.flash(v);
            card.setVisibility(View.GONE);
            sink.accept(outcome);
        });
        return b;
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

    /**
     * v1.117.0 并行协作卡片：某子智能体开始工作时创建，按「agent + 任务」索引。
     * 同一 agent 并发接多个任务时各有各的卡片，不会互相覆盖。
     */
    private void appendTeamCard(String key, String agent, String task) {
        hideThinkingDot();
        String name = (agent == null || agent.isEmpty()) ? "子智能体" : agent;
        TextView b = new TextView(this);
        b.setTextSize(13f);
        b.setTextColor(getColorCompat(R.color.operit_text_primary));
        b.setLineSpacing(2f, 1f);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setElevation(dp(1));
        b.setBackgroundResource(R.drawable.bg_tool);
        b.setText(teamCardText(name, task, "协作中 🔄", null, true));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(24);
        listContainer.addView(b, lp);
        teamCards.put(key, b);
        teamTasks.put(key, task == null ? "" : task);
        scrollToBottom();
    }

    /** v1.117.0 并行协作卡片：某子智能体结束时回填结论或失败原因 */
    private void finishTeamCard(String key, boolean ok, String detail) {
        TextView b = teamCards.get(key);
        if (b == null) return;
        int sep = key.indexOf('\u0000');
        String name = sep > 0 ? key.substring(0, sep) : "子智能体";
        String task = teamTasks.get(key);
        b.setText(teamCardText(name, task, ok ? "✅ 完成" : "❌ 失败", detail, ok));
        teamCards.remove(key);
        teamTasks.remove(key);
        scrollToBottom();
    }

    private CharSequence teamCardText(String agent, String task, String status,
                                      String detail, boolean ok) {
        StringBuilder sb = new StringBuilder("🤝 协作子智能体：").append(agent)
                .append("\n\n📋 任务：").append(task == null || task.isEmpty() ? "（无）" : task)
                .append("\n\n状态：").append(status);
        if (detail != null && !detail.isEmpty()) {
            sb.append("\n\n").append(ok ? "📋 结论：" : "原因：").append(detail);
        }
        return sb.toString();
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

    // ==================== 其他 ====================

    private void confirmClear() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("清空对话")
                .setMessage("确定清空本会话的全部消息吗？")
                .setPositiveButton("清空", (d, w) -> {
                    chatStore.clearSession(sessionKey);
                    listContainer.removeAllViews();
                    // v1.142.0（#57）：登记一并清空，否则清空后残留的气泡会被当成「最后一条」
                    aiBubbles.clear();
                    // 行数归零，不然清空后 rows 一直小于旧值，未读计数再也不累加
                    lastRowCount = 0;
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
        noteAppendedRows();
        if (userScrolledAway) return;
        scroll.post(() -> {
            View child = scroll.getChildAt(0);
            if (child == null) return;
            scroll.smoothScrollTo(0, child.getBottom());
        });
    }

    /**
     * v1.141.0（#43）：按「新增了几行」累积未读。
     * <p>流式回复每个 delta 都会调一次 {@link #scrollToBottom()}，但气泡行只在首个
     * delta 加一次；用行数差计数，才能保证「一条消息算一条」，不被流式刷新刷爆。</p>
     */
    private void noteAppendedRows() {
        if (listContainer == null) return;
        int rows = listContainer.getChildCount();
        if (rows <= lastRowCount) return;
        int added = rows - lastRowCount;
        lastRowCount = rows;
        if (userScrolledAway) {
            for (int i = 0; i < added; i++) scrollAnchor.onMessageAppended(false);
            updateScrollBottomButton();
        }
    }

    /** v1.141.0（#43）：右下角「回到底部 / N 条新消息」浮动按钮 */
    private TextView buildScrollBottomButton() {
        TextView b = new TextView(this);
        b.setText(ScrollAnchor.label(0));
        b.setTextSize(12f);
        b.setTextColor(Color.WHITE);
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColorCompat(R.color.operit_accent));
        bg.setCornerRadius(dp(18));
        b.setBackground(bg);
        b.setVisibility(View.GONE);
        b.setOnClickListener(v -> {
            UiKit.flash(v);
            scrollAnchor.clear();
            userScrolledAway = false;
            updateScrollBottomButton();
            View child = scroll.getChildAt(0);
            if (child != null) scroll.smoothScrollTo(0, child.getBottom());
        });
        return b;
    }

    /** v1.141.0（#43）：按当前是否离开底部与未读数刷新按钮显隐 / 文案 */
    private void updateScrollBottomButton() {
        if (btnScrollBottom == null) return;
        if (!userScrolledAway) {
            btnScrollBottom.setVisibility(View.GONE);
            return;
        }
        btnScrollBottom.setText(ScrollAnchor.label(scrollAnchor.unreadCount()));
        btnScrollBottom.setVisibility(View.VISIBLE);
    }

    private ImageButton iconButton(int res) {
        ImageButton b = new ImageButton(this);
        b.setHapticFeedbackEnabled(true);   // 自动生成：haptic
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
                sb.append("**工具**：").append(ChatTextOps.parseToolName(m.toolCalls)).append("\n\n");
                sb.append("**参数**：\n```json\n").append(ChatTextOps.parseToolArgs(m.toolCalls)).append("\n```\n\n");
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
                sb.append("[").append(time).append("] AI 调用工具：").append(ChatTextOps.parseToolName(m.toolCalls)).append("\n");
                sb.append("参数：").append(ChatTextOps.parseToolArgs(m.toolCalls)).append("\n\n");
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
        if (harness != null) harness.cancel();
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
