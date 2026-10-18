package com.digitallife.care;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
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
import com.digitallife.model.MotionLibrary;
import com.digitallife.service.PetService;
import com.digitallife.ui.UiKit;
import com.digitallife.util.Settings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;

/**
 * 动作池：把散落在各模型里的动作集中到一个库里看。
 *
 * <p>背景（v1.165.0）：动作本机其实有两套来源——内置模型 {@code assets/} 与导入模型
 * {@code files/models/}，每个模型的 {@code FileReferences.Motions} 声明了自己的动作组；
 * 但此前**只能回对话里让 Agent 念一遍**（{@code CareTools.list_motions}），没有任何界面能
 * 跨模型浏览。本页补上这个入口：只读浏览 + 点播当前模型动作 + 导出 / 复用 / 导入。</p>
 *
 * <p>「动作」口径含**未声明的孤立文件**：磁盘上存在但没被任何动作组引用的 {@code .motion3.json}
 * 引擎播不出，单独标「未注册文件（播不出）」，方便排查。</p>
 *
 * <p>写入边界：只能写到**导入模型**（{@code files/models/}）；内置模型在 assets、只读。
 * 复用受 Live2D 参数 ID 约束，跨不同骨骼的模型可能错位/无效果。</p>
 */
public class MotionPoolActivity extends Activity {

    private static final int REQ_EXPORT = 5101;
    private static final int REQ_IMPORT = 5102;

    private static final int COLOR_MISSING = Color.rgb(224, 96, 96);
    private static final int COLOR_CURRENT = Color.rgb(232, 152, 60);
    private static final int COLOR_BUILTIN = Color.rgb(130, 130, 150);
    private static final int COLOR_IMPORTED = Color.rgb(80, 150, 220);
    private static final int COLOR_PLAYABLE = Color.rgb(90, 160, 100);

    private Settings settings;
    private LinearLayout listContainer;
    private TextView tvSummary;

    /** 待写出的导出 */
    private String pendingExportModel;
    private boolean pendingExportImported;
    private String pendingExportFile;
    /** 待导入的目标模型 */
    private String pendingImportTarget;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        settings = new Settings(this);
        buildUi();
        refresh();
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
        listContainer.removeAllViews();
        List<MotionLibrary.ModelActions> models =
                MotionLibrary.scanAll(this, settings.getDefaultModelDir());

        int groups = 0;
        int files = 0;
        int orphans = 0;
        for (MotionLibrary.ModelActions m : models) {
            groups += m.groupCount();
            files += m.motionFileCount;
            orphans += m.orphans.size();
        }
        tvSummary.setText("共 " + models.size() + " 个模型 · " + groups + " 个动作组 · "
                + files + " 个动作文件 · 其中 " + orphans + " 个未注册（播不出）");

        if (models.isEmpty()) {
            listContainer.addView(hint("还没有任何模型，也没有动作文件。\n"
                    + "可在 Agent 对话里发送模型 zip 安装，或先导入一个模型。"));
            return;
        }
        for (MotionLibrary.ModelActions m : models) {
            listContainer.addView(buildModelCard(m));
        }
    }

    // ==================== 模型卡片 ====================

    private View buildModelCard(MotionLibrary.ModelActions m) {
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

        boolean empty = m.groups.isEmpty() && m.orphans.isEmpty();
        if (empty) {
            card.addView(hint("该模型没有动作文件（也没有可播放的动作）。\n"
                    + "可在 Agent 对话里说「给 " + m.dirName + " 补动作」。"));
        }

        // 可写模型才给「导入动作」入口
        if (ModelManager.isWritableModel(this, m.dirName)) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(8), 0, 0);
            TextView imp = chip(row, "导入动作到此模型…", R.color.brand_operit_light);
            imp.setOnClickListener(v -> pickImportTo(m.dirName));
            card.addView(row);
        }

        // 动作组
        for (MotionLibrary.Group g : m.groups) {
            card.addView(groupHeader(g.name + "  ·  " + g.motions.size() + " 个动作"));
            for (int i = 0; i < g.motions.size(); i++) {
                card.addView(motionRow(m, g.motions.get(i), g.name, i));
            }
        }

        // 未注册文件
        if (!m.orphans.isEmpty()) {
            card.addView(orphanHeader("未注册文件（播不出）"));
            for (MotionLibrary.Motion o : m.orphans) {
                card.addView(motionRow(m, o, null, -1));
            }
        }
        return card;
    }

    private View groupHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText("◆ " + text);
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

    /** 一条动作：名称 + 元数据 +（播 / 导出 / 复用）按钮。groupName 为 null 表示孤立文件 */
    private View motionRow(MotionLibrary.ModelActions m, MotionLibrary.Motion mo,
                           String groupName, int playIndex) {
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
                TextView play = chip(acts, "播", R.color.brand_operit_light);
                play.setOnClickListener(v -> play(m.dirName, groupName, idx));
            }
            TextView exp = chip(acts, "导出", R.color.operit_text_secondary);
            exp.setOnClickListener(v -> exportMotion(m, mo));
            TextView reuse = chip(acts, "复用", R.color.operit_text_secondary);
            reuse.setOnClickListener(v -> reuseMotion(m, mo));
            box.addView(acts);
        }
        return box;
    }

    // ==================== 行为 ====================

    private void play(String modelDir, String group, int index) {
        String current = settings.getDefaultModelDir();
        if (!modelDir.equals(current)) {
            toast("该动作属于模型「" + modelDir + "」，请先在「模型管理」里切换为默认模型再播放");
            return;
        }
        PetService svc = PetService.getInstance();
        if (svc == null) {
            toast("桌宠未在运行，请先开启桌宠再试");
            return;
        }
        svc.onMotion(group, index, 3);
        toast("已播放 " + group + "[" + index + "]");
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

    /** 把某个动作复制到另一个「导入」模型并注册进动作组 */
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
                .setMessage("将文件复制进目标模型目录，并注册到「" + group + "」组。\n"
                        + "提示：Live2D 动作绑定参数 ID，跨不同骨骼的模型复用可能错位/无效果，"
                        + "建议只用于同源模型。")
                .setItems(items, (d, which) -> doCopyInto(src, mo, items[which], group))
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
