package com.maiden.pet.overlay;

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

import com.maiden.pet.render.Pose;
import com.maiden.pet.render.Live2DGLView;

/**
 * 悬浮窗 Live2D 视图 + 桌面聊天输入框。
 */
public class PetOverlayView extends FrameLayout {

    private static final String TAG = "PetOverlayView";
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onTap();
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
    private final EditText chatInput;
    private final GestureDetector gestureDetector;
    private static final java.util.Random rnd = new java.util.Random();

    private Listener listener;
    private boolean dragging = false;
    private float lastTouchX, lastTouchY;
    private boolean live2DReady = false;

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
        bubbleView.setTextColor(Color.rgb(60, 50, 80));
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

        // 桌面聊天输入框
        chatInput = new EditText(context);
        chatInput.setHint("跟小汐说句话...");
        chatInput.setTextSize(13f);
        chatInput.setTextColor(Color.rgb(40, 30, 60));
        chatInput.setHintTextColor(Color.argb(120, 100, 80, 140));
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
                // 长按不再打开设置，改为不做任何事
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
    }

    private void handleModelTap(float x, float y) {
        if (!live2DReady) return;
        // 点击身体：从 TapBody 组随机播放真实动作，不再固定第一个
        live2DView.startMotion("TapBody", rnd.nextInt(6), 3);
    }

    public Pose getPose() {
        return pose;
    }

    public void showBubble(String text, float seconds) {
        mainHandler.post(() -> {
            if (text == null || text.isEmpty()) {
                bubbleContainer.setVisibility(View.GONE);
                return;
            }
            bubbleView.setText(text);
            bubbleContainer.setVisibility(View.VISIBLE);
            bubbleContainer.removeCallbacks(null);
            bubbleContainer.postDelayed(() -> bubbleContainer.setVisibility(View.GONE), (long) (seconds * 1000));
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
        bg.setCornerRadius(dp(14));
        bg.setColor(Color.WHITE);
        bg.setStroke(dp(1), Color.argb(120, 180, 170, 200));
        return bg;
    }

    private android.graphics.drawable.Drawable getTailBackground() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setColor(Color.WHITE);
        return bg;
    }

    private android.graphics.drawable.Drawable getInputBackground() {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(12f);
        bg.setColor(Color.argb(230, 255, 255, 255));
        bg.setStroke(1, Color.argb(100, 160, 140, 200));
        return bg;
    }

    public void clearBubble() {
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
        // 点击气泡区域：直接关闭气泡，不触发人偶交互
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && isTouchInBubble(event.getRawX(), event.getRawY())) {
            clearBubble();
            return true;
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
        return true;
    }
}