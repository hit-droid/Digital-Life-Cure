package com.digitallife.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.digitallife.R;
import com.digitallife.render.Pose;
import com.digitallife.render.Live2DGLView;
import com.digitallife.ui.pet.PetStatusText;
import com.digitallife.ui.pet.PetTouchReaction;

/**
 * 悬浮窗 Live2D 视图 + 桌面聊天输入框。
 */
public class PetOverlayView extends FrameLayout {

    private static final String TAG = "PetOverlayView";
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onModelReady();
        void onTap();
        /** v1.137.0（Issue #37）：人偶分区单击（Head/Body），供差异化气泡与情绪增量；与 onTap() 互不影响 */
        void onTapZone(String zone);
        void onDoubleTap();
        void onLongPress();
        void onDrag(float dx, float dy);
        void onDragged(float dx, float dy);
        void onChatMessage(String text);
        void onRequestChatFocus();
    }

    private final Pose pose = new Pose();
    private final Live2DGLView live2DView;

    public Live2DGLView getLive2DView() { return live2DView; }
    private final LinearLayout bubbleContainer;
    private final TextView bubbleView;
    private final View bubbleTail;
    /** v1.133.0：桌宠状态胶囊（亲密度 / 精力 / 主导情绪） */
    private final TextView statusView;
    private final EditText chatInput;
    /** 状态胶囊的数据来源；未接线时为 null，胶囊不显示 */
    private StatusProvider statusProvider;
    private final GestureDetector gestureDetector;
    private static final java.util.Random rnd = new java.util.Random();

    private Listener listener;
    private boolean dragging = false;
    private float lastTouchX, lastTouchY;
    private boolean live2DReady = false;

    /** 可交互人偶区域（模型带）：水平居中 60%、垂直 20%~100%；区域外触摸透传给下层应用 */
    private final android.graphics.Rect modelRect = new android.graphics.Rect();

    public PetOverlayView(Context context, Listener l) {
        super(context);
        this.listener = l;
        setWillNotDraw(false);

        // Live2DGLView（OpenGL ES 渲染，使用 Cubism Native SDK）
        live2DView = new Live2DGLView(context);
        live2DView.setListener(new Live2DGLView.Listener() {
            @Override
            public void onReady() {
                live2DReady = true;
                Log.d(TAG, "Live2D GL ready");
                // 转发给外层（PetService）：应用默认模型 + 更新 L1 能力感知
                if (listener != null) listener.onModelReady();
            }

            @Override
            public void onTap(float x, float y) {
                handleModelTap(x, y);
            }
        });
        addView(live2DView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 聊天气泡：白底不透明 + 圆角 + 阴影 + 指向人偶的小尾巴
        bubbleContainer = new LinearLayout(context);
        bubbleContainer.setOrientation(LinearLayout.VERTICAL);
        bubbleView = new TextView(context);
        bubbleView.setTextColor(colorRes(R.color.operit_text_primary));
        bubbleView.setTextSize(14f);
        bubbleView.setLineSpacing(2f, 1f);
        bubbleView.setPadding(dp(14), dp(10), dp(14), dp(10));
        bubbleView.setMaxWidth(dp(210));
        bubbleView.setShadowLayer(dp(2), 0, dp(1), Color.argb(90, 0, 0, 0));
        bubbleView.setBackground(getBubbleBackground());
        bubbleContainer.addView(bubbleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bubbleTail = new View(context);
        bubbleTail.setBackground(getTailBackground());
        bubbleTail.setRotation(45f);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(12), dp(12));
        tp.gravity = Gravity.CENTER_HORIZONTAL;
        tp.topMargin = -dp(5);
        bubbleContainer.addView(bubbleTail, tp);
        bubbleContainer.setVisibility(View.GONE);
        FrameLayout.LayoutParams bcp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bcp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        bcp.topMargin = dp(6);
        addView(bubbleContainer, bcp);

        // v1.133.0：状态胶囊（Issue #27 第 1 条）。
        // 在 chatInput 之前 addView：弹出输入框时它自然盖住胶囊，
        // 输入时也不需要看状态，省掉一套互斥逻辑。
        statusView = new TextView(context);
        statusView.setTextSize(10f);
        statusView.setTextColor(colorRes(R.color.operit_text_secondary));
        statusView.setBackground(getStatusBackground());
        statusView.setPadding(dp(8), dp(3), dp(8), dp(3));
        statusView.setAlpha(0.9f);
        statusView.setVisibility(View.GONE);
        statusView.setGravity(Gravity.CENTER);
        statusView.setMinHeight(dp(24));
        // v1.136.0（Issue #27 第 1 条「可点开详情」）：胶囊本身可点。
        // 在此之前它不是 clickable，点它会被下面的手势层当成「点了人偶」→ 打开输入框，
        // 想看一眼完整状态反而弹出键盘，纯属添乱。
        statusView.setClickable(true);
        statusView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showStatusDetail();
            }
        });
        FrameLayout.LayoutParams stp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        stp.bottomMargin = dp(2);
        addView(statusView, stp);

        // 桌面聊天输入框
        chatInput = new EditText(context);
        chatInput.setHint("跟她说句话...");
        chatInput.setTextSize(13f);
        chatInput.setTextColor(colorRes(R.color.operit_text_primary));
        chatInput.setHintTextColor(colorRes(R.color.operit_text_hint));
        chatInput.setSingleLine(true);
        chatInput.setBackground(getInputBackground());
        chatInput.setPadding(10, 6, 10, 6);
        chatInput.setVisibility(View.GONE);
        chatInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        chatInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                String text = chatInput.getText().toString().trim();
                if (!text.isEmpty() && listener != null) {
                    listener.onChatMessage(text);
                }
                chatInput.setText("");
                chatInput.setVisibility(View.GONE);
                return true;
            }
            return false;
        });
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        ip.bottomMargin = 4;
        ip.leftMargin = 4;
        ip.rightMargin = 4;
        addView(chatInput, ip);

        // 手势识别
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (listener != null) listener.onTap();
                handleModelTap(e.getX(), e.getY());
                showChatInput();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (listener != null) listener.onDoubleTap();
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                // v1.135.0（Issue #27 第 2 条）：长按拉起快捷菜单。
                // 之前这里是空实现，listener.onLongPress() 从来没被调用过，
                // PetService 那一侧等于死代码；补上这句，菜单才弹得出来。
                if (listener != null) listener.onLongPress();
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (!dragging) dragging = true;
                return true;
            }
        });
        gestureDetector.setIsLongpressEnabled(true);
        startStatusTicker();
    }

    // ==================== v1.133.0：状态胶囊（Issue #27 第 1 条） ====================

    /** 状态刷新间隔；情绪是慢变量（AICore 里按 tick 衰减），5 秒足够 */
    private static final long STATUS_REFRESH_MS = 5000L;

    /**
     * 状态胶囊的数据来源。
     * <p>刻意不让 {@code ui/} 反向依赖 {@code brain/}：这里只收三个标量，
     * 由 {@code PetService} 接线时把 {@code AICore#getEmotion()} 的值喂进来。
     * 本期不碰 {@code PetService}（该文件已被 trae 的 #26 认领），
     * 所以接线放到第二步，胶囊在接线前保持隐藏。</p>
     */
    public interface StatusProvider {
        float intimacy();

        float energy();

        String dominant();

        /**
         * v1.136.0：角色名（{@code Settings.pet_name}），详情卡片标题用；不实现则回落「我的状态」。
         * <p>用 default 方法是为了不破坏已有的匿名实现——只有 {@code PetService} 一处接线。</p>
         */
        default String name() {
            return "";
        }

        /**
         * v1.136.0：某一维情绪的强度（0~1），详情卡片逐条画进度条用。
         *
         * @param dim 取值为 {@link PetStatusText#DIMS}
         */
        default float emotion(String dim) {
            return 0f;
        }
    }

    /** 接线入口：{@code PetService} 调一次即可点亮胶囊；传 null 则隐藏 */
    public void setStatusProvider(StatusProvider p) {
        this.statusProvider = p;
        refreshStatus();
    }

    /**
     * ticker 提为字段：{@link #mainHandler} 是 static 的，匿名 Runnable 又隐式持有
     * 本 View，view 销毁后不摘掉就会一直空转并拖着整个 PetOverlayView 不释放。
     */
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            mainHandler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    private void startStatusTicker() {
        mainHandler.postDelayed(statusTick, STATUS_REFRESH_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mainHandler.removeCallbacks(statusTick);
    }

    private void refreshStatus() {
        StatusProvider p = statusProvider;
        if (p == null) {
            statusView.setVisibility(View.GONE);
            return;
        }
        try {
            // 悬浮窗里任何取数异常都不该把整个桌宠带崩，取不到就这一轮不显示
            String text = PetStatusText.capsule(p.intimacy(), p.energy(), p.dominant());
            if (text.isEmpty()) {
                statusView.setVisibility(View.GONE);
                return;
            }
            if (!text.equals(statusView.getText().toString())) statusView.setText(text);
            statusView.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "refreshStatus failed", t);
            statusView.setVisibility(View.GONE);
        }
    }

    private android.graphics.drawable.Drawable getStatusBackground() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(9));
        bg.setColor(colorRes(R.color.surface_glass));
        bg.setStroke(1, colorRes(R.color.brand_stroke));
        return bg;
    }

    // ==================== v1.135.0：长按快捷菜单（Issue #27 第 2 条） ====================

    /** 菜单项点击回调：回传 {@link PetQuickMenu} 里的索引 */
    public interface MenuCallback {
        void onItem(int index);
    }

    /** 菜单遮罩层；非 null 即为菜单打开中 */
    private FrameLayout menuLayer;

    public boolean isQuickMenuShowing() {
        return menuLayer != null && menuLayer.getVisibility() == View.VISIBLE;
    }

    /**
     * 在悬浮窗正中弹出快捷菜单。
     *
     * <p>遮罩层最后 addView，z 序最高且 clickable，点空白处关闭；菜单期间
     * {@link #onInterceptTouchEvent} 与 {@link #onTouchEvent} 都让位，手势不会打架。</p>
     */
    public void showQuickMenu(String[] items, final MenuCallback cb) {
        if (items == null || items.length == 0) return;
        runOnUi(new Runnable() {
            @Override
            public void run() {
                hideQuickMenu();
                if (chatInput.getVisibility() == View.VISIBLE) hideChatInput();

                final FrameLayout layer = new FrameLayout(getContext());
                layer.setBackgroundColor(Color.parseColor("#33000000"));
                layer.setClickable(true);
                layer.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        hideQuickMenu();
                    }
                });

                LinearLayout card = new LinearLayout(getContext());
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(getMenuBackground());
                FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cp.gravity = Gravity.CENTER;
                card.setLayoutParams(cp);

                for (int i = 0; i < items.length; i++) {
                    final int index = i;
                    TextView row = new TextView(getContext());
                    row.setText(items[i]);
                    row.setTextSize(13f);
                    row.setTextColor(colorRes(R.color.operit_text_primary));
                    row.setGravity(Gravity.CENTER);
                    row.setPadding(dp(16), dp(10), dp(16), dp(10));
                    row.setMinWidth(dp(104));
                    row.setClickable(true);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            hideQuickMenu();
                            if (cb != null) cb.onItem(index);
                        }
                    });
                    card.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    if (i < items.length - 1) {
                        card.addView(menuDivider(), new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 1));
                    }
                }
                layer.addView(card);
                addView(layer, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                menuLayer = layer;
            }
        });
    }

    /** 关闭菜单；已关闭时是空操作，可以放心重复调 */
    public void hideQuickMenu() {
        runOnUi(new Runnable() {
            @Override
            public void run() {
                if (menuLayer == null) return;
                removeView(menuLayer);
                menuLayer = null;
            }
        });
    }

    /** 主线程直跑、其它线程丢回主线程：菜单是 View 操作，跨线程改会崩 */
    private void runOnUi(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }

    private View menuDivider() {
        View v = new View(getContext());
        v.setBackgroundColor(colorRes(R.color.brand_stroke));
        return v;
    }

    private android.graphics.drawable.Drawable getMenuBackground() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(12));
        bg.setColor(colorRes(R.color.card_bg));
        bg.setStroke(dp(1), colorRes(R.color.brand_stroke));
        return bg;
    }

    // ==================== v1.136.0：状态详情卡片（Issue #27 第 1 条「可点开详情」） ====================

    /** 详情进度条的最大宽度（px）；{@code PetStatusText.barWidth} 按它换算填充宽度 */
    private static final int DETAIL_BAR_MAX_DP = 128;

    /** 详情遮罩层；非 null 即为详情打开中 */
    private FrameLayout detailLayer;

    public boolean isStatusDetailShowing() {
        return detailLayer != null && detailLayer.getVisibility() == View.VISIBLE;
    }

    /**
     * 点状态胶囊 → 详情卡片：亲密度 / 精力 + 六维情绪逐条进度条。
     *
     * <p>数据在打开瞬间快照一次。胶囊本身就是 5 秒刷一次，详情属于「点开看一眼」的
     * 轻交互，为一个浮层再挂个 ticker 不划算。</p>
     */
    public void showStatusDetail() {
        final StatusProvider p = statusProvider;
        if (p == null) return;
        runOnUi(new Runnable() {
            @Override
            public void run() {
                hideQuickMenu();
                hideStatusDetail();
                if (chatInput.getVisibility() == View.VISIBLE) hideChatInput();

                try {
                    float intimacy = safeValue(p.intimacy());
                    float energy = safeValue(p.energy());

                    final FrameLayout layer = new FrameLayout(getContext());
                    layer.setBackgroundColor(Color.parseColor("#33000000"));
                    layer.setClickable(true);
                    layer.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            hideStatusDetail();
                        }
                    });

                    LinearLayout card = new LinearLayout(getContext());
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setBackground(getMenuBackground());
                    card.setPadding(dp(16), dp(14), dp(16), dp(14));
                    card.setClickable(true); // 吃掉落点卡片内部的点击，避免顺带关掉
                    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    cp.gravity = Gravity.CENTER;
                    card.setLayoutParams(cp);

                    card.addView(detailText(PetStatusText.detailTitle(p.name()), 14f,
                            colorRes(R.color.operit_text_primary)));
                    card.addView(detailText(PetStatusText.detailSubtitle(intimacy), 11f,
                            colorRes(R.color.operit_text_secondary)));

                    card.addView(detailGap(dp(10)));
                    card.addView(detailBarRow("亲密度", intimacy, colorRes(R.color.brand)));
                    card.addView(detailBarRow("精力", energy, colorRes(R.color.success)));
                    card.addView(detailGap(dp(8)));
                    card.addView(detailHairline());
                    card.addView(detailGap(dp(8)));

                    for (String dim : PetStatusText.DIMS) {
                        card.addView(detailBarRow(PetStatusText.dominantLabel(dim),
                                safeValue(p.emotion(dim)), colorRes(R.color.brand_operit_light)));
                    }

                    card.addView(detailGap(dp(10)));
                    card.addView(detailText("点空白处收起", 10f,
                            colorRes(R.color.operit_text_hint)));

                    layer.addView(card);
                    addView(layer, new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    detailLayer = layer;
                } catch (Throwable t) {
                    // 悬浮窗里任何构建异常都不该把整个桌宠带崩，取不到就当作没点
                    Log.w(TAG, "showStatusDetail failed", t);
                    hideStatusDetail();
                }
            }
        });
    }

    /** 关闭详情卡片；已关闭时是空操作，可以放心重复调 */
    public void hideStatusDetail() {
        runOnUi(new Runnable() {
            @Override
            public void run() {
                if (detailLayer == null) return;
                removeView(detailLayer);
                detailLayer = null;
            }
        });
    }

    /** 一行：{@code 标签 | 进度条 | 数值}，三段等宽对齐，几行叠起来不会参差 */
    private View detailBarRow(String label, float value, int fillColor) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView name = detailText(label, 12f, colorRes(R.color.operit_text_secondary));
        name.setWidth(dp(44));
        row.addView(name);

        int maxPx = dp(DETAIL_BAR_MAX_DP);
        int h = dp(6);
        FrameLayout bar = new FrameLayout(getContext());
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        track.setCornerRadius(h / 2f);
        track.setColor(colorRes(R.color.brand_soft));
        bar.setBackground(track);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(maxPx, h);
        barLp.leftMargin = dp(4);
        barLp.rightMargin = dp(8);
        row.addView(bar, barLp);

        View fill = new View(getContext());
        android.graphics.drawable.GradientDrawable fg = new android.graphics.drawable.GradientDrawable();
        fg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        fg.setCornerRadius(h / 2f);
        fg.setColor(fillColor);
        fill.setBackground(fg);
        // barWidth 已保证落在 [0, maxPx]，这里不再夹取（回归在 PetStatusTextTest）
        bar.addView(fill, new FrameLayout.LayoutParams(PetStatusText.barWidth(value, maxPx), h));

        TextView val = detailText(PetStatusText.rowValue(value), 12f,
                colorRes(R.color.operit_text_primary));
        val.setGravity(Gravity.END);
        val.setWidth(dp(38));
        row.addView(val);

        return row;
    }

    private TextView detailText(String text, float sizeSp, int color) {
        TextView tv = new TextView(getContext());
        tv.setText(text);
        tv.setTextSize(sizeSp);
        tv.setTextColor(color);
        return tv;
    }

    private View detailGap(int px) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px));
        return v;
    }

    private View detailHairline() {
        View v = new View(getContext());
        v.setBackgroundColor(colorRes(R.color.brand_stroke));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    /** 数据源里的 NaN / 越界不让它传进布局计算；非法值按 0 处理 */
    private static float safeValue(float v) {
        if (Float.isNaN(v)) return 0f;
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // 气泡宽度限制在悬浮窗内，避免超出窗口被裁掉
        if (w > 0 && bubbleView != null) {
            bubbleView.setMaxWidth((int) (w * 0.9f));
        }
        // 模型经 viewMatrix 下移后，人偶头顶约在悬浮窗顶部往下 1/4 高度处；
        // 气泡框下移贴近头顶，避免离头顶太远悬空。
        if (h > 0 && bubbleContainer != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) bubbleContainer.getLayoutParams();
            lp.topMargin = (int) (h * 0.26f);
            bubbleContainer.setLayoutParams(lp);
        }
        // 人偶区域（模型带）：水平居中 60%（20%~80%），垂直 20%~100%；与 Live2DGLView 共享同一 Rect
        if (w > 0 && h > 0) {
            modelRect.set((int) (w * 0.2f), (int) (h * 0.2f), (int) (w * 0.8f), h);
            live2DView.setTouchRegion(modelRect);
        }
    }

    private void handleModelTap(float x, float y) {
        if (!live2DReady) return;
        // v1.137.0（Issue #37）：触摸分区。分区来源是 Java 近似（原生 HitArea 在悬浮窗里收不到触摸，
        // 见 PetTouchReaction 注释），按人偶区域纵向位置切「上 38% 头 / 其余身子」。
        final String zone = PetTouchReaction.zoneFor(x, y,
                modelRect.left, modelRect.top, modelRect.right, modelRect.bottom);
        final PetTouchReaction.Reaction reaction = PetTouchReaction.reactionFor(zone);
        // 视觉：摸头害羞、戳身惊讶；未知分区不改表情，保留原有「随机 TapBody」手感
        if (reaction.expression != null) {
            setExpression(reaction.expression);
        }
        if (reaction.motionGroup != null) {
            int index = reaction.motionIndex >= 0 ? reaction.motionIndex : rnd.nextInt(6);
            live2DView.startMotion(reaction.motionGroup, index, 3);
        }
        // 气泡与情绪增量交给 PetService（那里才有 aiCore）；onTap() 语义不变
        if (listener != null) listener.onTapZone(zone);
    }

    public Pose getPose() {
        return pose;
    }

    public void showBubble(String text, float seconds) {
        final String display;
        if (text == null || text.isEmpty()) {
            display = "";
        } else if (text.length() > 300) {
            display = text.substring(0, 300) + "…";
        } else {
            display = text;
        }
        final float duration = (seconds > 0 && seconds <= 30f) ? seconds : 30f;
        mainHandler.post(() -> {
            if (display.isEmpty()) {
                bubbleContainer.setVisibility(View.GONE);
                return;
            }
            bubbleView.setText(display);
            bubbleContainer.setVisibility(View.VISIBLE);
            bubbleContainer.removeCallbacks(null);
            bubbleContainer.postDelayed(() -> bubbleContainer.setVisibility(View.GONE), (long) (duration * 1000));
        });
    }

    private boolean isTouchInBubble(float screenX, float screenY) {
        if (bubbleContainer.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        bubbleContainer.getLocationOnScreen(loc);
        return screenX >= loc[0] && screenX <= loc[0] + bubbleContainer.getWidth()
                && screenY >= loc[1] && screenY <= loc[1] + bubbleContainer.getHeight();
    }

    private android.graphics.drawable.Drawable getBubbleBackground() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(18));
        bg.setColor(colorRes(R.color.card_bg));
        bg.setStroke(dp(1), colorRes(R.color.brand_stroke));
        return bg;
    }

    private android.graphics.drawable.Drawable getTailBackground() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setColor(colorRes(R.color.card_bg));
        return bg;
    }

    private android.graphics.drawable.Drawable getInputBackground() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(16));
        bg.setColor(colorRes(R.color.surface_glass));
        bg.setStroke(dp(1), colorRes(R.color.brand_stroke));
        return bg;
    }

    private int colorRes(int res) {
        return getResources().getColor(res);
    }

    public void clearBubble() {
        mainHandler.post(() -> bubbleContainer.setVisibility(View.GONE));
    }

    /** 显示「思考中」状态气泡（不自动隐藏，直到 hideThinking） */
    public void showThinking() {
        mainHandler.post(() -> {
            bubbleView.setText("正在思考…");
            bubbleContainer.setVisibility(View.VISIBLE);
            bubbleContainer.removeCallbacks(null);
        });
    }

    /** 隐藏「思考中」状态气泡 */
    public void hideThinking() {
        mainHandler.post(() -> bubbleContainer.setVisibility(View.GONE));
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    public void showChatInput() {
        mainHandler.post(() -> {
            if (listener != null) listener.onRequestChatFocus();
            chatInput.setVisibility(View.VISIBLE);
            chatInput.requestFocus();
            chatInput.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(chatInput, InputMethodManager.SHOW_IMPLICIT);
            }, 150);
        });
    }

    public void hideChatInput() {
        mainHandler.post(() -> {
            InputMethodManager imm = (InputMethodManager) getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(chatInput.getWindowToken(), 0);
            chatInput.setVisibility(View.GONE);
            chatInput.setText("");
        });
    }

    public String getLastJsError() {
        return live2DReady ? null : "Live2D not loaded";
    }

    public boolean isWebViewReady() {
        return live2DReady;
    }

    public void setSpeaking(boolean s) {
        pose.speaking = s;
        if (live2DReady) {
            live2DView.setMouth(s ? 0.3f : 0f);
        }
    }

    public void setExpression(String expr) {
        pose.setEmotion(expr);
        if (live2DReady) {
            // 如果已经是 Live2D 表情名（如 F01），直接使用；否则映射
            String mapped = expr;
            if (expr != null && !expr.startsWith("F")) {
                mapped = mapExpression(expr);
            }
            live2DView.setExpression(mapped);
        }
    }

    public void setMotion(String action) {
        pose.setAction(action);
        if (live2DReady) {
            int[] m = resolveMotion(action);
            live2DView.startMotion(MOTION_GROUPS[m[0]], m[1], 3);
        }
    }

    /** 直接设置 Live2D 动作（AI 直接控制用） */
    public void setMotion(String group, int index, int priority) {
        if (live2DReady) {
            live2DView.startMotion(group, index, priority);
        }
    }

    public void setMouth(float open) {
        pose.mouth = open;
        if (live2DReady) {
            live2DView.setMouth(open);
        }
    }

    /** 直接设置 Live2D 参数值（AI 实时控制用） */
    public void setParameterValue(String paramId, float value) {
        if (live2DReady) {
            live2DView.setParameterValue(paramId, value);
        }
    }

    /** 是否有非待机动作在播放（AI 待机微动让位用） */
    public boolean isMotionPlaying() {
        if (!live2DReady) return false;
        return live2DView.isMotionPlaying();
    }

    /** 在 GL 线程释放 Native Cubism 资源，供服务停止前调用 */
    public void releaseNative() {
        live2DView.releaseNative();
    }

    private String mapExpression(String emotion) {
        if (emotion == null) return "F01";
        switch (emotion) {
            case "happy":
            case "excited":
                return "F02";
            case "sad":
                return "F03";
            case "angry":
                return "F04";
            case "surprised":
                return "F05";
            case "shy":
                return "F06";
            default:
                return "F01";
        }
    }

    /** 根据动作名解析到真实动作组与索引（供 AI 工具和界面动作使用） */
    public static int[] resolveMotion(String action) {
        if (action == null) return new int[]{0, 0}; // Idle[0]
        switch (action) {
            case "wave":
                return new int[]{1, 0};   // Wave[0] = m25（挥手，唯一语义）
            case "clap":
                return new int[]{2, 0};   // Happy[0] = m03 拍手（避免随机到思考/静止动作）
            case "bounce":
                return new int[]{3, 0};   // Excited[0] = m18（幅度适中）
            case "stretch":
                return new int[]{2, 1};   // Happy[1] = m04 伸展
            case "point":
                return new int[]{0, 4};   // TapBody[4] = m08 指向（自然手臂）
            case "tilt_head":
                return new int[]{6, 0};   // Shy[0] = m07
            case "sit":
                return new int[]{2, 2};   // Happy[2] = m19 思考（安静端坐）
            case "happy":
                return new int[]{2, 0};
            case "excited":
                return new int[]{3, 0};
            case "sad":
                return new int[]{4, 0};
            case "surprised":
                return new int[]{5, 0};
            case "shy":
                return new int[]{6, 0};
            case "sleepy":
                return new int[]{7, 0};
            default:
                return new int[]{0, rnd.nextInt(2)};  // Idle 随机
        }
    }

    public static final String[] MOTION_GROUPS = {
            "Idle", "Wave", "Happy", "Excited", "Sad", "Surprised", "Shy", "Sleepy"
    };

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // v1.135.0：菜单期间不参与手势（拖拽 / 长按），全交给菜单层，避免边点菜单边拖窗口
        if (isQuickMenuShowing()) return false;
        // v1.136.0：详情卡片开着时同理，遮罩层自己处理「点空白收起」
        if (isStatusDetailShowing()) return false;
        // 点击气泡区域：直接关闭气泡，不触发人偶交互
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isTouchInBubble(event.getRawX(), event.getRawY())) {
            clearBubble();
            return true;
        }
        // 点状态胶囊：透传给胶囊自身处理（点开详情），不走人偶手势
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && isTouchInStatus(event.getX(), event.getY())) {
            return false;
        }
        // 人偶区域外的事件（子 View 未消费回溯到此处）不处理，透传给下层窗口
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && !modelRect.contains((int) event.getX(), (int) event.getY())) {
            return false;
        }
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = event.getRawX();
                lastTouchY = event.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    float dx = event.getRawX() - lastTouchX;
                    float dy = event.getRawY() - lastTouchY;
                    lastTouchX = event.getRawX();
                    lastTouchY = event.getRawY();
                    if (listener != null) listener.onDragged(dx, dy);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (listener != null) listener.onDrag(0, 0);
                return true;
        }
        return true;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // v1.135.0：菜单开着时一律放行。菜单居中显示、正好落在人偶区域里，
        // 不放行就会被下面的拦截逻辑吃掉，菜单点不动。
        if (isQuickMenuShowing()) return false;
        // v1.136.0：详情卡片同上，遮罩层自己处理点击
        if (isStatusDetailShowing()) return false;
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // 聊天输入框可见时，其范围放行给输入框本身（可聚焦输入），不参与穿透判定
            if (chatInput.getVisibility() == View.VISIBLE
                    && ev.getX() >= chatInput.getLeft() && ev.getX() <= chatInput.getRight()
                    && ev.getY() >= chatInput.getTop() && ev.getY() <= chatInput.getBottom()) {
                return false;
            }
            // v1.136.0：状态胶囊压在「人偶区域」里，不放行的话点它会被下面的拦截吃掉，
            // 变成「点头像 = 打开输入框」。胶囊自己 clickable，这里让路。
            if (isTouchInStatus(ev.getX(), ev.getY())) {
                return false;
            }
            // 人偶区域（模型带）内拦截以支持拖动/点击；区域外放行，最终经事件回溯透传给下层应用
            if (!modelRect.contains((int) ev.getX(), (int) ev.getY())) {
                return false;
            }
        }
        return true;
    }

    /** 该坐标是否落在可见的状态胶囊上（胶囊隐藏 / 未接线时一律 false） */
    private boolean isTouchInStatus(float x, float y) {
        if (statusView == null || statusView.getVisibility() != View.VISIBLE) return false;
        return x >= statusView.getLeft() && x <= statusView.getRight()
                && y >= statusView.getTop() && y <= statusView.getBottom();
    }
}