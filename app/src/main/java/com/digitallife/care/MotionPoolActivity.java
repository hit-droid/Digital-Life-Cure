package com.digitallife.care;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
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
import com.digitallife.model.ModelManager;
import com.digitallife.model.MotionCompat;
import com.digitallife.model.MotionLibrary;
import com.digitallife.service.PetService;
import com.digitallife.ui.UiKit;
import com.digitallife.util.Settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 动作池：把散落在各模型里的动作集中到一个库里看。
 *
 * <p>背景（v1.165.0）：动作本机其实有两套来源——内置模型 {@code assets/} 与导入模型
 * {@code files/models/}，每个模型的 {@code FileReferences.Motions} 声明了自己的动作组；
 * 但此前**只能回对话里让 Agent 念一遍**（{@code CareTools.list_motions}），没有任何界面能
 * 跨模型浏览。本页补上这个入口：只读浏览 + 点播当前模型动作 + 导出 / 复用 / 导入。</p>
 *
 * <p>「动作」口径含**未声明的孤立文件**：磁盘上存在但没被任何动作组引用的 {@code .motion3.json}
 * 引擎播不出，单独标「未注册文件（播不出）」，方便排查。表情（{@code .exp3.json}）同理：
 * 声明在 {@code FileReferences.Expressions}（{@code {Name, File}}）的引擎按 **Name** 播放，
 * 未被声明的同样归入「未注册表情」。</p>
 *
 * <p>写入边界：只能写到**导入模型**（{@code files/models/}）；内置模型在 assets、只读。
 * 复用受 Live2D 参数 ID 约束，跨不同骨骼的模型可能错位/无效果。</p>
 *
 * <p>详情 / 轻编辑（v1.170.0）：每条动作可点「详情」只读查看时长 / 循环 / 曲线数 / 参数清单；
 * 导入模型还可「淡入淡出」就地改写注册条目的 {@code FadeInTime/FadeOutTime}（引擎在
 * {@code LAppModel} 读取并应用，故会真正生效）。表情「详情」展示名称 / 文件 / 大小。</p>
 *
 * <p>搜索筛选 + 跨模型点播（v1.171.0）：顶部搜索框按模型名 / 组名 / 动作名 / 文件名 / 表情名
 * 实时过滤（命中模型的卡片只列命中项，索引仍按原始组内下标，保证点播不错位）；点播按钮在
 * **非当前默认模型**上显示为「切播」——一次确认后 `PetService.switchToModelByName` 切过去再播，
 * 二者都走 GL 线程同一 FIFO {@code queueEvent}，故顺序有保证（切换在前、播放在后）。</p>
 *
 * <p>连招 / 组合（v1.172.0）：把**同一个模型内**的若干动作 / 表情按顺序编成一段，一键连播。
 * 连招只记「组名 + 文件 / 表情名」，不记下标（用户改名 / 删动作后仍按当前扫描结果重新解析，
 * 解析不到的步骤跳过并提示）；停顿时长由 {@link ComboPlan#dwellMs} 依据动作时长推导（循环动作
 * 封顶、表情固定停顿）。播放经主线程 {@code Handler} 逐步调度，同一条连招用 generation 计数
 * 防串台；由于 {@code nativeStartMotion} / {@code nativeSetExpression} 同走 GL 线程 FIFO，
 * 逐步下发天然有序。连招只引用模型已有内容、不写模型 json，故内置模型也能编。</p>
 *
 * <p>连招打磨（v1.174.0）：编排弹窗里每个候选旁加「试」，可**单步试播**（非当前模型走「切播」
 * 确认），避免「编完整条才发现某一步不对」；已存连招加「复制」，另起不重名的副本名
 * （{@link ComboPlan#copyName}）原样拷贝步骤，便于派生变体。</p>
 */
public class MotionPoolActivity extends Activity {

    private static final int REQ_EXPORT = 5101;
    private static final int REQ_IMPORT = 5102;
    private static final int REQ_EXPR_IMPORT = 5103;

    private static final int COLOR_MISSING = Color.rgb(224, 96, 96);
    private static final int COLOR_CURRENT = Color.rgb(232, 152, 60);
    private static final int COLOR_BUILTIN = Color.rgb(130, 130, 150);
    private static final int COLOR_IMPORTED = Color.rgb(80, 150, 220);
    private static final int COLOR_PLAYABLE = Color.rgb(90, 160, 100);

    private Settings settings;
    private LinearLayout listContainer;
    private TextView tvSummary;
    private EditText searchBox;
    /** 最近一次扫描结果（供搜索过滤复用，避免每次输入都重扫磁盘） */
    private List<MotionLibrary.ModelActions> scanned = new ArrayList<>();
    /** 未过滤时的汇总文案 */
    private String totalSummary = "";

    /** 连招逐步播放的主线程调度器 */
    private Handler comboHandler;
    /** 连招代次：每次开播 +1，旧代次的延迟任务自我作废，避免多条连招串台 */
    private int comboGeneration = 0;

    /** 待写出的导出 */
    private String pendingExportModel;
    private boolean pendingExportImported;
    private String pendingExportFile;
    /** 待导入的目标模型 */
    private String pendingImportTarget;
    /** 待导入表情的目标模型 */
    private String pendingExprImportTarget;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = new Settings(this);
        comboHandler = new Handler(Looper.getMainLooper());
        buildUi();
        refresh();
    }

    @Override
    protected void onDestroy() {
        // 离开页面即作废未播完的连招，避免延迟任务打到已销毁的界面
        comboGeneration++;
        if (comboHandler != null) comboHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_operit_page);

        LinearLayout topBar = UiKit.pageTopBar(this, "动作池");
        Button btnRefresh = new Button(this);
        btnRefresh.setHapticFeedbackEnabled(true);
        btnRefresh.setContentDescription("刷新");
        btnRefresh.setText("刷新");
        btnRefresh.setTextSize(13f);
        btnRefresh.setTextColor(Color.WHITE);
        btnRefresh.setAllCaps(false);
        btnRefresh.setBackgroundResource(R.drawable.bg_btn_primary);
        btnRefresh.setPadding(dp(12), dp(4), dp(12), dp(4));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        rlp.setMargins(dp(4), 0, dp(4), 0);
        btnRefresh.setOnClickListener(v -> refresh());
        topBar.addView(btnRefresh, rlp);
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(12), dp(14), dp(16));

        // 搜索框：实时过滤模型/组/动作/表情（v1.171.0）
        searchBox = new EditText(this);
        searchBox.setHint("搜索模型 / 动作组 / 动作 / 表情");
        searchBox.setHintTextColor(getColorCompat(R.color.operit_text_hint));
        searchBox.setTextColor(getColorCompat(R.color.operit_text_primary));
        searchBox.setTextSize(14f);
        searchBox.setSingleLine(true);
        searchBox.setInputType(InputType.TYPE_CLASS_TEXT);
        searchBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        searchBox.setBackgroundResource(R.drawable.bg_card);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                renderList();
            }
        });
        body.addView(searchBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        tvSummary = new TextView(this);
        tvSummary.setTextSize(12f);
        tvSummary.setTextColor(getColorCompat(R.color.operit_text_secondary));
        tvSummary.setLineSpacing(dp(2), 1f);
        tvSummary.setIncludeFontPadding(false);
        body.addView(tvSummary);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lcp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lcp.topMargin = dp(10);
        body.addView(listContainer, lcp);

        scroll.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    private void refresh() {
        scanned = MotionLibrary.scanAll(this, settings.getDefaultModelDir());

        int groups = 0;
        int files = 0;
        int orphans = 0;
        int exprs = 0;
        int exprOrphans = 0;
        for (MotionLibrary.ModelActions m : scanned) {
            groups += m.groupCount();
            files += m.motionFileCount;
            orphans += m.orphans.size();
            exprs += m.exprCount();
            exprOrphans += m.exprOrphans.size();
        }
        totalSummary = "共 " + scanned.size() + " 个模型 · " + groups + " 个动作组 · "
                + files + " 个动作文件（" + orphans + " 未注册）· "
                + exprs + " 个表情（" + exprOrphans + " 未注册）";
        renderList();
    }

    /**
     * 按当前搜索词渲染列表（v1.171.0）。过滤只影响**展示**——点播仍用组内原始下标，
     * 故「看到的项」与「点播的项」不会错位。
     */
    private void renderList() {
        if (listContainer == null) return;
        listContainer.removeAllViews();
        String nq = MotionFilter.normalize(searchBox == null ? null : searchBox.getText().toString());

        if (scanned.isEmpty()) {
            tvSummary.setText(totalSummary);
            listContainer.addView(hint("还没有任何模型，也没有动作文件。\n"
                    + "可在 Agent 对话里发送模型 zip 安装，或先导入一个模型。"));
            return;
        }

        int shown = 0;
        for (MotionLibrary.ModelActions m : scanned) {
            View card = buildModelCard(m, nq);
            if (card == null) continue;
            listContainer.addView(card);
            shown++;
        }

        if (nq.isEmpty()) {
            tvSummary.setText(totalSummary);
        } else {
            tvSummary.setText("搜索「" + nq + "」· 命中 " + shown + " 个模型 / 共 "
                    + scanned.size() + " 个");
            if (shown == 0) {
                listContainer.addView(hint("没有匹配「" + nq + "」的模型 / 动作 / 表情。"));
            }
        }
    }

    // ==================== 模型卡片 ====================

    /**
     * 构建一个模型卡片；{@code nq} 为已归一化的搜索词（空＝不过滤）。
     * 过滤时只列命中项；整卡无命中则返回 {@code null}（由 {@link #renderList()} 跳过）。
     * 动作点播索引始终用**组内原始下标**，过滤不重编号。
     */
    private View buildModelCard(MotionLibrary.ModelActions m, String nq) {
        boolean filtered = nq != null && !nq.isEmpty();
        boolean modelHit = !filtered || MotionFilter.matches(m.dirName, nq);

        // 先筛出要展示的组 → 组内原始下标（LinkedHashMap 保序）
        java.util.LinkedHashMap<String, List<Integer>> shownGroups = new java.util.LinkedHashMap<>();
        for (MotionLibrary.Group g : m.groups) {
            boolean groupHit = modelHit || MotionFilter.matches(g.name, nq);
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < g.motions.size(); i++) {
                MotionLibrary.Motion mo = g.motions.get(i);
                if (groupHit || MotionFilter.anyMatches(nq, mo.displayName(), mo.file, mo.group)) {
                    idx.add(i);
                }
            }
            if (!idx.isEmpty()) shownGroups.put(g.name, idx);
        }
        List<MotionLibrary.Motion> shownOrphans = new ArrayList<>();
        for (MotionLibrary.Motion o : m.orphans) {
            if (modelHit || MotionFilter.anyMatches(nq, o.displayName(), o.file, o.group)) {
                shownOrphans.add(o);
            }
        }
        List<Integer> shownExprIdx = new ArrayList<>();
        for (int i = 0; i < m.expressions.size(); i++) {
            MotionLibrary.Expr e = m.expressions.get(i);
            if (modelHit || MotionFilter.anyMatches(nq, e.displayName(), e.file, e.name)) {
                shownExprIdx.add(i);
            }
        }
        List<MotionLibrary.Expr> shownExprOrphans = new ArrayList<>();
        for (MotionLibrary.Expr e : m.exprOrphans) {
            if (modelHit || MotionFilter.anyMatches(nq, e.displayName(), e.file, e.name)) {
                shownExprOrphans.add(e);
            }
        }

        boolean physicallyEmpty = m.groups.isEmpty() && m.orphans.isEmpty()
                && m.expressions.isEmpty() && m.exprOrphans.isEmpty();
        boolean anyShown = !shownGroups.isEmpty() || !shownOrphans.isEmpty()
                || !shownExprIdx.isEmpty() || !shownExprOrphans.isEmpty();
        if (filtered && !modelHit && !anyShown) return null;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);

        // 标题行
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(m.dirName);
        name.setTextSize(15f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(getColorCompat(R.color.operit_text_primary));
        head.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (m.isDefault) head.addView(tag("当前", COLOR_CURRENT));
        head.addView(tag(m.imported ? "已导入" : "内置", m.imported ? COLOR_IMPORTED : COLOR_BUILTIN));
        head.addView(tag(m.playable ? "可播放" : "零动作",
                m.playable ? COLOR_PLAYABLE : COLOR_MISSING));
        card.addView(head);

        boolean writable = ModelManager.isWritableModel(this, m.dirName);

        if (physicallyEmpty) {
            card.addView(hint("该模型没有动作/表情文件（也没有可播放的动作）。\n"
                    + "可在 Agent 对话里说「给 " + m.dirName + " 补动作」。"));
        }

        // 可写模型才给「导入动作 / 导入表情」入口；搜索过滤时只在模型名命中时展示，避免干扰
        if (writable && (!filtered || modelHit)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, 0);
            TextView imp = chip(row, "导入动作到此模型…", R.color.brand_operit_light);
            imp.setOnClickListener(v -> pickImportTo(m.dirName));
            TextView impExpr = chip(row, "导入表情到此模型…", R.color.brand_operit_light);
            impExpr.setOnClickListener(v -> pickExprImportTo(m.dirName));
            card.addView(row);
        }

        // 连招（v1.172.0）：同模型内有序连播；搜索过滤时只在模型名命中时展示，避免干扰
        if (!filtered || modelHit) {
            card.addView(buildComboSection(m));
        }

        // 动作组（按原顺序遍历，命中与否用预筛结果判定；下标沿用 g.motions 的原始 i）
        for (MotionLibrary.Group g : m.groups) {
            List<Integer> idx = shownGroups.get(g.name);
            if (idx == null) continue;
            card.addView(groupHeader(m.dirName, g.name, idx.size(), writable));
            for (int i : idx) {
                card.addView(motionRow(m, g.motions.get(i), g.name, i, writable));
            }
        }

        // 未注册文件
        if (!shownOrphans.isEmpty()) {
            card.addView(orphanHeader(writable
                    ? "未注册文件（播不出）· 点「注册」可挂进动作组"
                    : "未注册文件（播不出）"));
            for (MotionLibrary.Motion o : shownOrphans) {
                card.addView(motionRow(m, o, null, -1, writable));
            }
        }

        // 表情：声明区（引擎按 Name 播放）
        if (!shownExprIdx.isEmpty() || !shownExprOrphans.isEmpty()) {
            card.addView(sectionHeader("◆ 表情  ·  " + shownExprIdx.size() + " 个已注册"));
            for (int i : shownExprIdx) {
                card.addView(exprRow(m, m.expressions.get(i), i, writable));
            }
            if (!shownExprOrphans.isEmpty()) {
                card.addView(orphanHeader(writable
                        ? "未注册表情（播不出）· 点「注册」可挂进表情名"
                        : "未注册表情（播不出）"));
                for (MotionLibrary.Expr e : shownExprOrphans) {
                    card.addView(exprRow(m, e, -1, writable));
                }
            }
        }
        return card;
    }

    /** 动作组标题行：组名 + 动作数 +（可写模型）「改名」 */
    private View groupHeader(String modelDir, String groupName, int count, boolean writable) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);

        TextView tv = new TextView(this);
        tv.setText("◆ " + groupName + "  ·  " + count + " 个动作");
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(getColorCompat(R.color.operit_accent));
        tv.setIncludeFontPadding(false);
        row.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (writable) {
            TextView rename = chip(row, "改名", R.color.operit_text_secondary);
            rename.setOnClickListener(v -> promptRenameGroup(modelDir, groupName));
        }
        return row;
    }

    private View orphanHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(COLOR_MISSING);
        tv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 分区标题（表情区）：与动作组标题同款强调色，但不带「改名」 */
    private View sectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(getColorCompat(R.color.operit_accent));
        tv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 一条动作：名称 + 元数据 + 操作按钮（播 / 导出 / 复用 / 删 / 注册）。groupName 为 null 表示孤立文件 */
    private View motionRow(MotionLibrary.ModelActions m, MotionLibrary.Motion mo,
                           String groupName, int playIndex, boolean writable) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        box.setLayoutParams(blp);

        // 第一行：序号 + 名称 + 元数据 / 缺失
        LinearLayout line1 = new LinearLayout(this);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        String prefix = mo.declared ? (playIndex + 1) + ". " : "· ";
        title.setText(prefix + mo.displayName());
        title.setTextSize(13f);
        title.setTextColor(getColorCompat(R.color.operit_text_primary));
        title.setIncludeFontPadding(false);
        line1.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView meta = new TextView(this);
        if (!mo.exists) {
            meta.setText("文件缺失");
            meta.setTextColor(COLOR_MISSING);
        } else {
            meta.setText("  [" + trim(mo.duration) + "s "
                    + (mo.loop ? "循环" : "单次") + " " + mo.curveCount + "曲线]");
            meta.setTextColor(getColorCompat(R.color.operit_text_hint));
        }
        meta.setTextSize(11f);
        meta.setIncludeFontPadding(false);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = dp(6);
        line1.addView(meta, mlp);
        box.addView(line1);

        // 第二行：操作
        if (mo.exists) {
            LinearLayout acts = new LinearLayout(this);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            acts.setPadding(0, dp(6), 0, 0);

            if (mo.declared && groupName != null) {
                final int idx = playIndex;
                TextView play = chip(acts, m.isDefault ? "播" : "切播", R.color.brand_operit_light);
                play.setOnClickListener(v -> play(m.dirName, groupName, idx));
            }
            TextView exp = chip(acts, "导出", R.color.operit_text_secondary);
            exp.setOnClickListener(v -> exportMotion(m, mo));
            TextView reuse = chip(acts, "复用", R.color.operit_text_secondary);
            reuse.setOnClickListener(v -> reuseMotion(m, mo));
            TextView detail = chip(acts, "详情", R.color.operit_text_secondary);
            detail.setOnClickListener(v -> showMotionDetail(m, mo));
            if (writable) {
                if (mo.declared && groupName != null) {
                    TextView fade = chip(acts, "淡入淡出", R.color.operit_text_secondary);
                    fade.setOnClickListener(v -> promptMotionFade(m, groupName, mo));
                    TextView del = chip(acts, "删", R.color.operit_text_secondary);
                    del.setOnClickListener(v -> confirmRemove(m.dirName, groupName, mo));
                } else if (!mo.declared) {
                    TextView reg = chip(acts, "注册…", R.color.brand_operit_light);
                    reg.setOnClickListener(v -> promptRegisterOrphan(m.dirName, mo.file));
                }
            }
            box.addView(acts);
        }
        return box;
    }

    /**
     * 一条表情：名称 + 文件名 + 操作（播 / 导出 / 复用 / 移除 / 改名 / 注册）。
     * playIndex 为 -1 表示孤立文件（未注册，播不出）。
     */
    private View exprRow(MotionLibrary.ModelActions m, MotionLibrary.Expr ex,
                         int playIndex, boolean writable) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        box.setLayoutParams(blp);

        LinearLayout line1 = new LinearLayout(this);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        String prefix = ex.declared ? (playIndex + 1) + ". " : "· ";
        title.setText(prefix + ex.displayName());
        title.setTextSize(13f);
        title.setTextColor(getColorCompat(R.color.operit_text_primary));
        title.setIncludeFontPadding(false);
        line1.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView meta = new TextView(this);
        if (!ex.exists) {
            meta.setText("文件缺失");
            meta.setTextColor(COLOR_MISSING);
        } else {
            meta.setText("  [" + baseName(ex.file) + "]");
            meta.setTextColor(getColorCompat(R.color.operit_text_hint));
        }
        meta.setTextSize(11f);
        meta.setIncludeFontPadding(false);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = dp(6);
        line1.addView(meta, mlp);
        box.addView(line1);

        if (ex.exists) {
            LinearLayout acts = new LinearLayout(this);
            acts.setOrientation(LinearLayout.HORIZONTAL);
            acts.setPadding(0, dp(6), 0, 0);

            if (ex.declared) {
                TextView play = chip(acts, m.isDefault ? "播" : "切播", R.color.brand_operit_light);
                play.setOnClickListener(v -> playExpression(m.dirName, ex.name));
            }
            TextView exp = chip(acts, "导出", R.color.operit_text_secondary);
            exp.setOnClickListener(v -> exportExpression(m, ex));
            TextView detail = chip(acts, "详情", R.color.operit_text_secondary);
            detail.setOnClickListener(v -> showExprDetail(m, ex));
            TextView reuse = chip(acts, "复用", R.color.operit_text_secondary);
            reuse.setOnClickListener(v -> reuseExpression(m, ex));
            if (writable) {
                if (ex.declared) {
                    TextView rename = chip(acts, "改名", R.color.operit_text_secondary);
                    rename.setOnClickListener(v -> promptRenameExpression(m.dirName, ex.name));
                    TextView del = chip(acts, "移除", R.color.operit_text_secondary);
                    del.setOnClickListener(v -> confirmRemoveExpression(m.dirName, ex));
                } else {
                    TextView reg = chip(acts, "注册…", R.color.brand_operit_light);
                    reg.setOnClickListener(v -> promptRegisterExprOrphan(m.dirName, ex.file));
                }
            }
            box.addView(acts);
        }
        return box;
    }

    // ==================== 连招（v1.172.0） ====================

    /** 连招区：标题 + 新建入口 + 已存连招列表（无内容时给提示） */
    private View buildComboSection(MotionLibrary.ModelActions m) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        List<ComboPlan.Candidate> cands = comboCandidates(m);
        List<ComboPlan.Combo> combos = ComboStore.list(this, m.dirName);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = dp(10);
        head.setLayoutParams(hlp);

        TextView tv = new TextView(this);
        tv.setText("◆ 连招  ·  " + combos.size() + " 个");
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(getColorCompat(R.color.operit_accent));
        tv.setIncludeFontPadding(false);
        head.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!cands.isEmpty()) {
            TextView add = chip(head, "＋ 新建连招", R.color.brand_operit_light);
            add.setOnClickListener(v -> showComboBuilder(m));
        }
        box.addView(head);

        if (cands.isEmpty()) {
            box.addView(hint("该模型没有可播放的动作 / 表情，无法编连招。"));
            return box;
        }
        if (combos.isEmpty()) {
            box.addView(hint("还没有连招。点「＋ 新建连招」把本模型的若干动作 / 表情按顺序编成一段。"));
            return box;
        }
        for (ComboPlan.Combo c : combos) {
            box.addView(comboRow(m, c, cands));
        }
        return box;
    }

    /** 一条连招：名称 + 步数（含失效计数）+ 序列预览 + 播 / 删 */
    private View comboRow(MotionLibrary.ModelActions m, ComboPlan.Combo combo,
                          List<ComboPlan.Candidate> cands) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        box.setLayoutParams(blp);

        LinearLayout line1 = new LinearLayout(this);
        line1.setOrientation(LinearLayout.HORIZONTAL);
        line1.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("▶ " + combo.name);
        title.setTextSize(13f);
        title.setTextColor(getColorCompat(R.color.operit_text_primary));
        title.setIncludeFontPadding(false);
        line1.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int valid = ComboPlan.resolveAll(combo.steps, cands).size();
        TextView meta = new TextView(this);
        String metaText = "  [" + combo.size() + " 步";
        if (valid < combo.size()) metaText += "，可播 " + valid + " 步";
        metaText += "]";
        meta.setText(metaText);
        meta.setTextSize(11f);
        meta.setTextColor(valid == 0 ? COLOR_MISSING : getColorCompat(R.color.operit_text_hint));
        meta.setIncludeFontPadding(false);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = dp(6);
        line1.addView(meta, mlp);
        box.addView(line1);

        TextView seq = new TextView(this);
        seq.setText(comboSeqText(combo));
        seq.setTextSize(11f);
        seq.setTextColor(getColorCompat(R.color.operit_text_secondary));
        seq.setIncludeFontPadding(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        box.addView(seq, slp);

        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        acts.setPadding(0, dp(6), 0, 0);

        TextView play = chip(acts, m.isDefault ? "播" : "切播", R.color.brand_operit_light);
        play.setOnClickListener(v -> playCombo(m, combo));
        TextView copy = chip(acts, "复制", R.color.operit_text_secondary);
        copy.setOnClickListener(v -> duplicateCombo(m, combo));
        TextView del = chip(acts, "删", R.color.operit_text_secondary);
        del.setOnClickListener(v -> confirmDeleteCombo(m, combo));
        box.addView(acts);
        return box;
    }

    /** 把某模型的扫描结果拍平成解析候选（只取「声明 + 文件存在」的可播项） */
    private List<ComboPlan.Candidate> comboCandidates(MotionLibrary.ModelActions m) {
        List<ComboPlan.Candidate> out = new ArrayList<>();
        if (m == null) return out;
        for (MotionLibrary.Group g : m.groups) {
            for (int i = 0; i < g.motions.size(); i++) {
                MotionLibrary.Motion mo = g.motions.get(i);
                if (!mo.declared || !mo.exists) continue;
                out.add(new ComboPlan.Candidate(true, g.name, mo.file, null, i,
                        mo.duration, mo.loop));
            }
        }
        for (MotionLibrary.Expr e : m.expressions) {
            if (!e.declared || !e.exists) continue;
            out.add(new ComboPlan.Candidate(false, null, e.file, e.name, -1, 0, false));
        }
        return out;
    }

    /** 播放一条连招：先解析出可播步骤，非当前模型走「切播」二次确认 */
    private void playCombo(MotionLibrary.ModelActions m, ComboPlan.Combo combo) {
        PetService svc = PetService.getInstance();
        if (svc == null) {
            toast("桌宠未在运行，请先开启桌宠再试");
            return;
        }
        List<ComboPlan.Candidate> cands = comboCandidates(m);
        final List<ComboPlan.Resolved> steps = ComboPlan.resolveAll(combo.steps, cands);
        if (steps.isEmpty()) {
            toast("连招「" + combo.name + "」没有可播放的步骤（动作 / 表情可能已被删除或改名）");
            return;
        }
        if (steps.size() < combo.size()) {
            toast("连招有 " + (combo.size() - steps.size()) + " 步已失效，将播放其余 "
                    + steps.size() + " 步");
        }
        final String name = combo.name;
        Runnable start = () -> runCombo(svc, name, steps);
        if (isCurrentModel(m.dirName)) {
            start.run();
        } else {
            confirmSwitchAndPlay(svc, m.dirName, start);
        }
    }

    /** 开始逐级下发连招；用 generation 作废旧代次的延迟任务，避免多条连招串台 */
    private void runCombo(PetService svc, String name, List<ComboPlan.Resolved> steps) {
        final int gen = ++comboGeneration;
        comboHandler.removeCallbacksAndMessages(null);
        long total = 0;
        for (ComboPlan.Resolved r : steps) total += r.dwellMs;
        toast("▶ 连招「" + name + "」开始（" + steps.size() + " 步，约 "
                + String.format(Locale.ROOT, "%.1f", total / 1000.0) + " 秒）");
        playComboStep(svc, steps, 0, gen);
    }

    /**
     * 下发第 {@code i} 步并在其停留时长后调度下一步。动作 / 表情都经 GL 线程同一 FIFO
     * {@code queueEvent}，故「逐步下发」天然有序；新动作会打断上一步，无需等它自然结束。
     */
    private void playComboStep(PetService svc, List<ComboPlan.Resolved> steps, int i, int gen) {
        if (gen != comboGeneration) return;
        if (i >= steps.size()) {
            toast("连招播放完成");
            return;
        }
        ComboPlan.Resolved r = steps.get(i);
        if (r.motion) svc.onMotion(r.group, r.index, 3);
        else svc.onExpression(r.exprName);
        final int next = i + 1;
        comboHandler.postDelayed(() -> playComboStep(svc, steps, next, gen), r.dwellMs);
    }

    private void confirmDeleteCombo(MotionLibrary.ModelActions m, ComboPlan.Combo combo) {
        new AlertDialog.Builder(this)
                .setTitle("删除连招？")
                .setMessage("删除「" + combo.name + "」（" + combo.size() + " 步）。\n"
                        + "只删这份编排，不影响模型里的动作 / 表情。")
                .setPositiveButton("删除", (d, w) -> {
                    ComboStore.delete(this, m.dirName, combo.name);
                    toast("已删除连招「" + combo.name + "」");
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 单步试播：在编排弹窗里试听某一个动作 / 表情（非当前模型走「切播」确认） */
    private void previewCandidate(MotionLibrary.ModelActions m, ComboPlan.Candidate c) {
        PetService svc = PetService.getInstance();
        if (svc == null) {
            toast("桌宠未在运行，请先开启桌宠再试");
            return;
        }
        final ComboPlan.Resolved r = ComboPlan.resolve(
                new ComboPlan.Step(c.motion ? ComboPlan.KIND_MOTION : ComboPlan.KIND_EXPR,
                        c.group, c.file, c.name),
                comboCandidates(m));
        if (r == null) {
            toast("这一步暂时播不出");
            return;
        }
        Runnable play = () -> {
            if (r.motion) svc.onMotion(r.group, r.index, 3);
            else svc.onExpression(r.exprName);
        };
        if (isCurrentModel(m.dirName)) {
            play.run();
        } else {
            confirmSwitchAndPlay(svc, m.dirName, play);
        }
    }

    /** 复制一条连招为副本（另起一个不重名的名字，步骤原样拷贝） */
    private void duplicateCombo(MotionLibrary.ModelActions m, ComboPlan.Combo combo) {
        final List<ComboPlan.Combo> existing = ComboStore.list(this, m.dirName);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(6), dp(18), dp(6));
        final EditText etName = UiKit.input(this, col, "新连招名",
                ComboPlan.copyName(existing, combo.name + "-副本"));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("复制连招「" + combo.name + "」")
                .setView(col)
                .setPositiveButton("复制", null)
                .setNegativeButton("取消", null)
                .create();
        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String name = ComboPlan.sanitizeName(etName.getText().toString());
                    if (name.isEmpty()) {
                        toast("请填写连招名");
                        return;
                    }
                    if (ComboPlan.hasName(existing, name)) {
                        toast("已存在同名连招，换个名字");
                        return;
                    }
                    ComboPlan.Combo copy = new ComboPlan.Combo(name);
                    copy.steps.addAll(combo.steps);
                    ComboStore.save(this, m.dirName, copy);
                    toast("已复制为「" + name + "」（" + copy.size() + " 步）");
                    dialog.dismiss();
                    refresh();
                }));
        dialog.show();
    }

    /** 新建连招弹窗：填名 + 点「＋」依次追加可播动作 / 表情（「试」可单步试播），实时预览序列 */
    private void showComboBuilder(MotionLibrary.ModelActions m) {
        List<ComboPlan.Candidate> cands = comboCandidates(m);
        if (cands.isEmpty()) {
            toast("该模型没有可播放的动作 / 表情，无法编连招。");
            return;
        }
        final List<ComboPlan.Combo> existing = ComboStore.list(this, m.dirName);
        final ComboPlan.Combo draft = new ComboPlan.Combo(ComboPlan.suggestName(existing));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(4), dp(16), 0);

        EditText nameField = new EditText(this);
        nameField.setText(draft.name);
        nameField.setTextSize(14f);
        nameField.setSingleLine(true);
        nameField.setPadding(dp(10), dp(8), dp(10), dp(8));
        col.addView(nameField, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView seqView = new TextView(this);
        seqView.setTextSize(12f);
        seqView.setTextColor(getColorCompat(R.color.operit_text_secondary));
        seqView.setLineSpacing(dp(2), 1f);
        seqView.setIncludeFontPadding(false);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qlp.topMargin = dp(8);
        col.addView(seqView, qlp);

        TextView tip = new TextView(this);
        tip.setText("点下面的「＋」按顺序添加（最多 " + ComboPlan.MAX_STEPS + " 步）");
        tip.setTextSize(11f);
        tip.setTextColor(getColorCompat(R.color.operit_text_hint));
        tip.setIncludeFontPadding(false);
        col.addView(tip);

        ScrollView stepScroll = new ScrollView(this);
        stepScroll.setVerticalScrollBarEnabled(false);
        LinearLayout stepList = new LinearLayout(this);
        stepList.setOrientation(LinearLayout.VERTICAL);
        stepScroll.addView(stepList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams sslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(200));
        sslp.topMargin = dp(6);
        col.addView(stepScroll, sslp);

        for (ComboPlan.Candidate c : cands) {
            final ComboPlan.Candidate cand = c;
            LinearLayout crow = new LinearLayout(this);
            crow.setOrientation(LinearLayout.HORIZONTAL);
            crow.setGravity(Gravity.CENTER_VERTICAL);
            TextView add = chip(crow, "＋ " + candidateLabel(cand), R.color.brand_operit_light);
            add.setOnClickListener(v -> {
                if (draft.steps.size() >= ComboPlan.MAX_STEPS) {
                    toast("一条连招最多 " + ComboPlan.MAX_STEPS + " 步");
                    return;
                }
                draft.steps.add(new ComboPlan.Step(
                        cand.motion ? ComboPlan.KIND_MOTION : ComboPlan.KIND_EXPR,
                        cand.group, cand.file, cand.name));
                refreshSeqView(seqView, draft);
            });
            TextView tryIt = chip(crow, "试", R.color.operit_text_secondary);
            tryIt.setOnClickListener(v -> previewCandidate(m, cand));
            stepList.addView(crow);
        }

        LinearLayout editRow = new LinearLayout(this);
        editRow.setOrientation(LinearLayout.HORIZONTAL);
        editRow.setPadding(0, dp(6), 0, 0);
        TextView undo = chip(editRow, "撤销上一步", R.color.operit_text_secondary);
        undo.setOnClickListener(v -> {
            if (!draft.steps.isEmpty()) draft.steps.remove(draft.steps.size() - 1);
            refreshSeqView(seqView, draft);
        });
        TextView clear = chip(editRow, "清空", R.color.operit_text_secondary);
        clear.setOnClickListener(v -> {
            draft.steps.clear();
            refreshSeqView(seqView, draft);
        });
        col.addView(editRow);

        refreshSeqView(seqView, draft);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("新建连招 · " + m.dirName)
                .setView(col)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        // 自定义保存：校验名字非空、至少一步；同名则覆盖并提示
        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String name = ComboPlan.sanitizeName(nameField.getText().toString());
                    if (name.isEmpty()) {
                        toast("请填写连招名");
                        return;
                    }
                    if (draft.steps.isEmpty()) {
                        toast("请至少添加一步");
                        return;
                    }
                    boolean overwrite = ComboPlan.hasName(existing, name);
                    ComboPlan.Combo toSave = new ComboPlan.Combo(name);
                    toSave.steps.addAll(draft.steps);
                    ComboStore.save(this, m.dirName, toSave);
                    toast(overwrite ? "已覆盖同名连招「" + name + "」"
                            : "已保存连招「" + name + "」（" + toSave.size() + " 步）");
                    dialog.dismiss();
                    refresh();
                }));
        dialog.show();
    }

    /** 刷新弹窗里的「当前序列」预览 */
    private void refreshSeqView(TextView seqView, ComboPlan.Combo draft) {
        seqView.setText("当前序列（" + draft.size() + " 步）：" + comboSeqText(draft));
    }

    /** 序列预览文案（超过 6 步折叠） */
    private String comboSeqText(ComboPlan.Combo combo) {
        if (combo.steps.isEmpty()) return "（空）";
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ComboPlan.Step s : combo.steps) {
            if (shown >= 6) {
                sb.append(" → …（共 ").append(combo.size()).append(" 步）");
                break;
            }
            if (shown > 0) sb.append(" → ");
            sb.append(stepDesc(s));
            shown++;
        }
        return sb.toString();
    }

    private String stepDesc(ComboPlan.Step s) {
        if (s.isMotion()) {
            return (s.group == null || s.group.isEmpty() ? "" : s.group + "·") + shortFile(s.file);
        }
        String n = (s.name == null || s.name.isEmpty()) ? shortFile(s.file) : s.name;
        return "表情 " + n;
    }

    /** 候选项展示名：动作「组 #序号 名」/ 表情「表情 名」 */
    private String candidateLabel(ComboPlan.Candidate c) {
        if (c.motion) {
            String g = c.group == null || c.group.isEmpty() ? "" : c.group + " #" + (c.index + 1) + " ";
            return g + shortFile(c.file);
        }
        String n = c.name == null || c.name.isEmpty() ? shortFile(c.file) : c.name;
        return "表情 " + n;
    }

    /** 文件基名（去目录与 .motion3.json / .exp3.json 后缀） */
    private String shortFile(String file) {
        String b = baseName(file);
        String l = b.toLowerCase(Locale.ROOT);
        if (l.endsWith(".motion3.json")) b = b.substring(0, b.length() - ".motion3.json".length());
        else if (l.endsWith(".exp3.json")) b = b.substring(0, b.length() - ".exp3.json".length());
        return b;
    }

    // ==================== 行为 ====================

    private void play(String modelDir, String group, int index) {
        PetService svc = PetService.getInstance();
        if (svc == null) {
            toast("桌宠未在运行，请先开启桌宠再试");
            return;
        }
        if (isCurrentModel(modelDir)) {
            svc.onMotion(group, index, 3);
            toast("已播放 " + group + "[" + index + "]");
            return;
        }
        confirmSwitchAndPlay(svc, modelDir, () -> {
            svc.onMotion(group, index, 3);
            toast("已切到「" + modelDir + "」并播放 " + group + "[" + index + "]");
        });
    }

    /** 目标模型是否为当前默认模型 */
    private boolean isCurrentModel(String modelDir) {
        String cur = settings.getDefaultModelDir();
        return cur != null && cur.equals(modelDir);
    }

    /**
     * 跨模型点播（v1.171.0）：确认后切到目标模型再播放。
     * 切换（nativeChangeScene）与播放（nativeStartMotion）都经 GL 线程同一 FIFO
     * {@code queueEvent}，故「先切后播」顺序有保证，无需延迟等待。
     */
    private void confirmSwitchAndPlay(PetService svc, String modelDir, Runnable afterSwitch) {
        String cur = settings.getDefaultModelDir();
        new AlertDialog.Builder(this)
                .setTitle("跨模型点播")
                .setMessage("该内容属于模型「" + modelDir + "」，当前默认是「"
                        + (cur == null || cur.isEmpty() ? "—" : cur) + "」。\n\n"
                        + "切到「" + modelDir + "」后播放？会改变当前默认模型（桌宠重载为该模型）。")
                .setPositiveButton("切换并播放", (d, w) -> {
                    if (!svc.switchToModelByName(modelDir)) {
                        toast("切换失败：未找到模型「" + modelDir + "」");
                        return;
                    }
                    afterSwitch.run();
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void exportMotion(MotionLibrary.ModelActions m, MotionLibrary.Motion mo) {
        pendingExportModel = m.dirName;
        pendingExportImported = m.imported;
        pendingExportFile = mo.file;
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("application/json");
        it.putExtra(Intent.EXTRA_TITLE, mo.displayName() + ".motion3.json");
        try {
            startActivityForResult(it, REQ_EXPORT);
        } catch (Exception e) {
            toast("无法调起保存： " + UiKit.safeMsg(e));
        }
    }

    /** 把某个动作复制到另一个「导入」模型并注册进动作组（先选目标） */
    private void reuseMotion(MotionLibrary.ModelActions src, MotionLibrary.Motion mo) {
        List<String> targets = ModelManager.listImportedModelDirs(this);
        if (targets.isEmpty()) {
            toast("内置模型只读，且还没有可写入的导入模型。请先在「模型管理」里导入一个模型。");
            return;
        }
        String[] items = targets.toArray(new String[0]);
        final String group = mo.group != null ? mo.group : "TapBody";
        new AlertDialog.Builder(this)
                .setTitle("把「" + mo.displayName() + "」复用到哪个模型？")
                .setItems(items, (d, which) -> confirmReuse(src, mo, items[which], group))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 复用前先算一次参数兼容性，把「可能错位」这个隐性约束变成可见判定，再由用户决定 */
    private void confirmReuse(MotionLibrary.ModelActions src, MotionLibrary.Motion mo,
                              String target, String group) {
        Set<String> motionParams =
                MotionLibrary.motionParameterIds(this, src.dirName, src.imported, mo.file);
        Set<String> targetParams = MotionLibrary.modelParameterIds(this, target, true);
        MotionCompat.Verdict v = MotionCompat.judge(motionParams, targetParams);

        StringBuilder msg = new StringBuilder();
        msg.append("将复制进「").append(target).append("」并注册到「").append(group).append("」组。\n\n");
        msg.append("兼容性：").append(v.label);
        if (v.total > 0) {
            msg.append("（命中参数 ").append(v.matched).append("/").append(v.total);
            if (!v.missing.isEmpty()) {
                msg.append("，目标缺失：").append(String.join(", ", v.missing));
            }
            msg.append("）");
        }
        msg.append("\n\n仅同源模型间的复用才可靠；强行复用可在模型切换后点「播」验证。");

        new AlertDialog.Builder(this)
                .setTitle("确认复用")
                .setMessage(msg.toString())
                .setPositiveButton("仍然复用", (d, w) -> doCopyInto(src, mo, target, group))
                .setNegativeButton("取消", null)
                .show();
    }

    /** 把「未注册」的孤立文件挂进某个动作组（只改 json，不复制文件） */
    private void promptRegisterOrphan(String modelDir, String relFile) {
        EditText et = new EditText(this);
        et.setText("TapBody");
        et.setTextSize(14f);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("注册到哪个动作组？")
                .setMessage("把未注册文件「" + baseName(relFile) + "」挂进模型「" + modelDir
                        + "」的动作组（填已有组名即并入，填新名则新建组）。")
                .setView(et)
                .setPositiveButton("注册", (d, w) -> {
                    toast(ModelManager.registerExistingFileIntoModel(
                            this, modelDir, relFile, et.getText().toString().trim()));
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 从动作组中移除一个动作的注册（文件保留，变为未注册文件） */
    private void confirmRemove(String modelDir, String groupName, MotionLibrary.Motion mo) {
        new AlertDialog.Builder(this)
                .setTitle("从组中移除？")
                .setMessage("把「" + mo.displayName() + "」从「" + groupName + "」组移除注册。\n"
                        + "动作文件会保留，随后出现在「未注册文件」区，可再次注册。")
                .setPositiveButton("移除", (d, w) -> {
                    toast(ModelManager.removeMotionFromModel(this, modelDir, groupName, mo.file));
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 重命名动作组 */
    private void promptRenameGroup(String modelDir, String groupName) {
        EditText et = new EditText(this);
        et.setText(groupName);
        et.setTextSize(14f);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("重命名动作组")
                .setMessage("模型「" + modelDir + "」的动作组「" + groupName
                        + "」改名；若新名已存在则把动作并入其中。")
                .setView(et)
                .setPositiveButton("改名", (d, w) -> {
                    toast(ModelManager.renameMotionGroup(
                            this, modelDir, groupName, et.getText().toString().trim()));
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doCopyInto(MotionLibrary.ModelActions src, MotionLibrary.Motion mo,
                            String target, String group) {
        try {
            byte[] data = MotionLibrary.readBytes(this, src.dirName, src.imported, mo.file);
            String base = baseName(mo.file);
            String msg = ModelManager.registerMotionIntoModel(this, target, base, data, group);
            toast(msg);
            refresh();
        } catch (Exception e) {
            toast("读取动作失败： " + UiKit.safeMsg(e));
        }
    }

    // ==================== 表情行为 ====================

    /** 播放一个已声明表情（引擎按 Name 播，故无需 index） */
    private void playExpression(String modelDir, String name) {
        PetService svc = PetService.getInstance();
        if (svc == null) {
            toast("桌宠未在运行，请先开启桌宠再试");
            return;
        }
        if (isCurrentModel(modelDir)) {
            svc.onExpression(name);
            toast("已播放表情「" + name + "」");
            return;
        }
        confirmSwitchAndPlay(svc, modelDir, () -> {
            svc.onExpression(name);
            toast("已切到「" + modelDir + "」并播放表情「" + name + "」");
        });
    }

    private void exportExpression(MotionLibrary.ModelActions m, MotionLibrary.Expr ex) {
        pendingExportModel = m.dirName;
        pendingExportImported = m.imported;
        pendingExportFile = ex.file;
        Intent it = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("application/json");
        it.putExtra(Intent.EXTRA_TITLE, ex.displayName() + ".exp3.json");
        try {
            startActivityForResult(it, REQ_EXPORT);
        } catch (Exception e) {
            toast("无法调起保存： " + UiKit.safeMsg(e));
        }
    }

    /** 把某个表情复制到另一个「导入」模型并注册（先选目标，再定表情名） */
    private void reuseExpression(MotionLibrary.ModelActions src, MotionLibrary.Expr ex) {
        List<String> targets = ModelManager.listImportedModelDirs(this);
        if (targets.isEmpty()) {
            toast("内置模型只读，且还没有可写入的导入模型。请先在「模型管理」里导入一个模型。");
            return;
        }
        String[] items = targets.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("把表情「" + ex.displayName() + "」复用到哪个模型？")
                .setItems(items, (d, which) -> promptReuseExprName(src, ex, items[which]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void promptReuseExprName(MotionLibrary.ModelActions src, MotionLibrary.Expr ex,
                                     String target) {
        EditText et = new EditText(this);
        et.setText(ex.displayName());
        et.setTextSize(14f);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("以什么表情名注册？")
                .setMessage("将把 " + baseName(ex.file) + " 复制进「" + target
                        + "」并注册。表情重名会互相覆盖，请确认名称。")
                .setView(et)
                .setPositiveButton("复用", (d, w) ->
                        doReuseExpr(src, ex, target, et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doReuseExpr(MotionLibrary.ModelActions src, MotionLibrary.Expr ex,
                             String target, String name) {
        try {
            byte[] data = MotionLibrary.readBytes(this, src.dirName, src.imported, ex.file);
            String msg = ModelManager.registerExpressionIntoModel(
                    this, target, baseName(ex.file), data, name);
            toast(msg);
            refresh();
        } catch (Exception e) {
            toast("读取表情失败： " + UiKit.safeMsg(e));
        }
    }

    /** 把「未注册」的孤立表情挂成某个表情名（只改 json，不复制文件） */
    private void promptRegisterExprOrphan(String modelDir, String relFile) {
        EditText et = new EditText(this);
        et.setText(exprBaseName(relFile));
        et.setTextSize(14f);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("注册为哪个表情名？")
                .setMessage("把未注册表情「" + baseName(relFile) + "」挂进模型「" + modelDir
                        + "」。引擎按表情名播放（如 F01），重名会互相覆盖。")
                .setView(et)
                .setPositiveButton("注册", (d, w) -> {
                    toast(ModelManager.registerExistingFileAsExpression(
                            this, modelDir, relFile, et.getText().toString().trim()));
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 从模型 Expressions 中移除一个表情的注册（文件保留，变为未注册表情） */
    private void confirmRemoveExpression(String modelDir, MotionLibrary.Expr ex) {
        new AlertDialog.Builder(this)
                .setTitle("移除这个表情？")
                .setMessage("把「" + ex.displayName() + "」从模型的 Expressions 移除注册。\n"
                        + "表情文件会保留，随后出现在「未注册表情」区，可再次注册。")
                .setPositiveButton("移除", (d, w) -> {
                    toast(ModelManager.removeExpressionFromModel(this, modelDir, ex.name));
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 重命名表情的 Name（引擎按名播放，改名即改指向） */
    private void promptRenameExpression(String modelDir, String name) {
        EditText et = new EditText(this);
        et.setText(name);
        et.setTextSize(14f);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("重命名表情")
                .setMessage("模型「" + modelDir + "」的表情「" + name
                        + "」改名；引擎按名字播放，改名即换指向。")
                .setView(et)
                .setPositiveButton("改名", (d, w) -> {
                    toast(ModelManager.renameExpression(
                            this, modelDir, name, et.getText().toString().trim()));
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 详情 / 轻编辑 ====================

    /** 动作详情（只读）：组、时长、循环、曲线数、淡入淡出、参数清单 */
    private void showMotionDetail(MotionLibrary.ModelActions m, MotionLibrary.Motion mo) {
        StringBuilder sb = new StringBuilder();
        sb.append("所属模型：").append(m.dirName)
                .append(m.isDefault ? "（当前默认）" : "").append('\n');
        sb.append("动作组：").append(mo.group == null ? "（未注册）" : mo.group).append('\n');
        sb.append("文件：").append(mo.file).append('\n');
        if (!mo.exists) {
            sb.append("状态：文件缺失");
        } else {
            sb.append("时长：").append(trim(mo.duration)).append(" 秒\n");
            sb.append("循环：").append(mo.loop ? "是" : "否").append('\n');
            sb.append("曲线数：").append(mo.curveCount).append('\n');
            sb.append("淡入/淡出：").append(fadeText(mo.fadeIn)).append(" / ")
                    .append(fadeText(mo.fadeOut)).append(" 秒\n");
            Set<String> ids = MotionLibrary.motionParameterIds(this, m.dirName, m.imported, mo.file);
            sb.append("参数（").append(ids.size()).append("）：\n");
            if (ids.isEmpty()) sb.append("（无，或该文件无 Parameter 曲线）");
            else for (String id : ids) sb.append("· ").append(id).append('\n');
        }
        showLongDialog(mo.displayName() + " · 详情", sb.toString());
    }

    /**
     * 淡入 / 淡出「轻编辑」：仅导入模型可写，写回动作注册条目的
     * FadeInTime/FadeOutTime（引擎在 LAppModel 读取并应用，故会真正生效）。
     */
    private void promptMotionFade(MotionLibrary.ModelActions m, String groupName,
                                  MotionLibrary.Motion mo) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(4), dp(16), 0);

        EditText in = numberField(col, "淡入（秒）", Double.isNaN(mo.fadeIn) ? "1.0" : trim(mo.fadeIn));
        EditText out = numberField(col, "淡出（秒）", Double.isNaN(mo.fadeOut) ? "1.0" : trim(mo.fadeOut));

        new AlertDialog.Builder(this)
                .setTitle("淡入 / 淡出 · " + mo.displayName())
                .setMessage("写回动作「" + mo.displayName() + "」在「" + groupName
                        + "」组内注册条目的 FadeInTime/FadeOutTime（秒）。引擎按此值做进场/退场过渡。")
                .setView(col)
                .setPositiveButton("保存", (d, w) -> {
                    Double vi = parseNum(in.getText().toString());
                    Double vo = parseNum(out.getText().toString());
                    if (vi == null || vo == null) {
                        toast("请输入非负数字（秒），如 0.9");
                        return;
                    }
                    toast(ModelManager.setMotionFade(this, m.dirName, groupName, mo.file, vi, vo));
                    refresh();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 表情详情：名称 / 文件 / 状态 / 大小 */
    private void showExprDetail(MotionLibrary.ModelActions m, MotionLibrary.Expr ex) {
        StringBuilder sb = new StringBuilder();
        sb.append("所属模型：").append(m.dirName)
                .append(m.isDefault ? "（当前默认）" : "").append('\n');
        sb.append("表情名：").append(ex.name == null || ex.name.isEmpty() ? "（未注册）" : ex.name).append('\n');
        sb.append("文件：").append(ex.file).append('\n');
        if (!ex.exists) {
            sb.append("状态：文件缺失");
        } else {
            sb.append("状态：已声明，引擎按名播放\n");
            String size;
            try {
                size = MotionLibrary.readBytes(this, m.dirName, m.imported, ex.file).length + " 字节";
            } catch (Exception e) {
                size = "（读取失败： " + UiKit.safeMsg(e) + "）";
            }
            sb.append("大小：").append(size);
        }
        showLongDialog("表情 " + ex.displayName() + " · 详情", sb.toString());
    }

    private void showLongDialog(String title, String content) {
        TextView tv = new TextView(this);
        tv.setText(content);
        tv.setTextSize(13f);
        tv.setTextColor(getColorCompat(R.color.operit_text_primary));
        tv.setLineSpacing(dp(3), 1f);
        tv.setIncludeFontPadding(false);
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.setPadding(dp(16), dp(6), dp(16), 0);
        sv.addView(tv, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 一行「标签 + 数字输入框」，返回输入框 */
    private EditText numberField(LinearLayout col, String label, String value) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12f);
        tv.setTextColor(getColorCompat(R.color.operit_text_secondary));
        tv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(8);
        col.addView(tv, tlp);

        EditText et = new EditText(this);
        et.setText(value);
        et.setTextSize(14f);
        et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        et.setPadding(dp(10), dp(8), dp(10), dp(8));
        col.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return et;
    }

    /** 解析非负秒数；非法或负数返回 null */
    private static Double parseNum(String s) {
        if (s == null) return null;
        try {
            double v = Double.parseDouble(s.trim());
            return v < 0 ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    private static String fadeText(double v) {
        return Double.isNaN(v) ? "默认(1.0)" : trim(v);
    }

    private void pickImportTo(String targetModel) {
        pendingImportTarget = targetModel;
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_IMPORT);
        } catch (Exception e) {
            toast("无法调起文件选择： " + UiKit.safeMsg(e));
        }
    }

    private void promptGroupThenImport(Uri uri) {
        EditText et = new EditText(this);
        et.setText("TapBody");
        et.setTextSize(14f);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("注册到哪个动作组？")
                .setMessage("组名如 Idle / TapBody（填新名会新建组），文件将写入模型「"
                        + pendingImportTarget + "」并注册。")
                .setView(et)
                .setPositiveButton("确认", (d, w) -> doImport(uri, et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doImport(Uri uri, String group) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                toast("无法读取所选文件");
                return;
            }
            byte[] data = readAll(in);
            String name = uri.getLastPathSegment();
            if (name == null || name.isEmpty()) name = "imported.motion3.json";
            String g = group == null || group.isEmpty() ? "TapBody" : group;
            String msg = ModelManager.registerMotionIntoModel(this, pendingImportTarget, name, data, g);
            toast(msg);
            refresh();
        } catch (Exception e) {
            toast("导入失败： " + UiKit.safeMsg(e));
        }
    }

    private void pickExprImportTo(String targetModel) {
        pendingExprImportTarget = targetModel;
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        try {
            startActivityForResult(it, REQ_EXPR_IMPORT);
        } catch (Exception e) {
            toast("无法调起文件选择： " + UiKit.safeMsg(e));
        }
    }

    private void promptNameThenImportExpr(Uri uri) {
        String seg = uri.getLastPathSegment();
        EditText et = new EditText(this);
        et.setText(exprBaseName(seg));
        et.setTextSize(14f);
        et.setPadding(dp(12), dp(8), dp(12), dp(8));
        new AlertDialog.Builder(this)
                .setTitle("注册为哪个表情名？")
                .setMessage("文件将写入模型「" + pendingExprImportTarget
                        + "」并注册。引擎按表情名播放（如 F01），重名会互相覆盖。")
                .setView(et)
                .setPositiveButton("确认", (d, w) -> doImportExpr(uri, et.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void doImportExpr(Uri uri, String exprName) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                toast("无法读取所选文件");
                return;
            }
            byte[] data = readAll(in);
            String name = baseName(uri.getLastPathSegment());
            if (name == null || name.isEmpty()) name = "imported.exp3.json";
            String msg = ModelManager.registerExpressionIntoModel(
                    this, pendingExprImportTarget, name, data, exprName);
            toast(msg);
            refresh();
        } catch (Exception e) {
            toast("导入失败： " + UiKit.safeMsg(e));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_EXPORT) {
            Uri uri = data.getData();
            if (uri == null || pendingExportFile == null) return;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) {
                    toast("无法写入所选位置");
                    return;
                }
                os.write(MotionLibrary.readBytes(this, pendingExportModel,
                        pendingExportImported, pendingExportFile));
                toast("已导出 " + baseName(pendingExportFile));
            } catch (Exception e) {
                toast("导出失败： " + UiKit.safeMsg(e));
            }
            pendingExportFile = null;
        } else if (requestCode == REQ_IMPORT) {
            Uri uri = data.getData();
            if (uri == null || pendingImportTarget == null) return;
            promptGroupThenImport(uri);
        } else if (requestCode == REQ_EXPR_IMPORT) {
            Uri uri = data.getData();
            if (uri == null || pendingExprImportTarget == null) return;
            promptNameThenImportExpr(uri);
        }
    }

    // ==================== 小工具 ====================

    private TextView chip(LinearLayout row, String text, int colorRes) {
        TextView c = UiKit.chipButton(this, row, text, colorRes);
        ViewGroup.LayoutParams lp = c.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).rightMargin = dp(6);
        }
        return c;
    }

    private TextView tag(String text, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(11f);
        tv.setTextColor(color);
        tv.setBackgroundResource(R.drawable.bg_chip_outline);
        tv.setPadding(dp(8), dp(3), dp(8), dp(3));
        tv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13f);
        tv.setTextColor(getColorCompat(R.color.operit_text_secondary));
        tv.setLineSpacing(dp(2), 1f);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private static String baseName(String path) {
        String p = path == null ? "" : path;
        int slash = p.lastIndexOf('/');
        return slash >= 0 ? p.substring(slash + 1) : p;
    }

    /** 表情文件基名（去目录、去 .exp3.json 后缀），用于「注册表情名」的默认值 */
    private static String exprBaseName(String path) {
        String n = baseName(path);
        if (n.toLowerCase(Locale.ROOT).endsWith(".exp3.json")) {
            n = n.substring(0, n.length() - ".exp3.json".length());
        }
        return n;
    }

    private static String trim(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int getColorCompat(int res) {
        return getResources().getColor(res);
    }

    private int dp(float v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
