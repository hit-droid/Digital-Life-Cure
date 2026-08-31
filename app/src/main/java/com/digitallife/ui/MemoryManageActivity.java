package com.digitallife.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.digitallife.R;
import com.digitallife.util.MemoryStore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MemoryManageActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MemoryStore ms;
    private LinearLayout listContainer;
    private TextView tvEmpty;
    private volatile boolean destroyed = false;
    private int currentTab = 0;
    private static final int REQ_IMPORT = 1001;
    private Button segBtnFacts;
    private Button segBtnSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        destroyed = false;
        ms = new MemoryStore(this);
        buildUi();
        refreshList();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColorCompat(R.color.operit_bg));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_operit_topbar);
        topBar.setPadding(dp(6), dp(12), dp(6), dp(12));

        ImageButton btnBack = iconButton(R.drawable.ic_back);
        btnBack.setContentDescription("返回");
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, btnLp(40, 40));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("记忆管理");
        tvTitle.setTextSize(17f);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnRefresh = new Button(this);
        btnRefresh.setContentDescription("刷新");   // 自动生成：a11y
        btnRefresh.setText("刷新");
        btnRefresh.setTextSize(13f);
        btnRefresh.setTextColor(Color.WHITE);
        btnRefresh.setAllCaps(false);
        btnRefresh.setBackgroundResource(R.drawable.bg_btn_primary);
        btnRefresh.setPadding(dp(12), dp(4), dp(12), dp(4));
        btnRefresh.setOnClickListener(v -> refreshList());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        rlp.setMargins(dp(6), 0, dp(6), 0);
        topBar.addView(btnRefresh, rlp);

        // v1.24.0：自动提取按钮
        Button btnExtract = new Button(this);
        btnExtract.setText("AI 提取");
        btnExtract.setTextSize(13f);
        btnExtract.setTextColor(Color.WHITE);
        btnExtract.setAllCaps(false);
        btnExtract.setBackgroundResource(R.drawable.bg_btn_glass);
        btnExtract.setPadding(dp(12), dp(4), dp(12), dp(4));
        btnExtract.setOnClickListener(v -> triggerExtraction());
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        elp.setMargins(dp(6), 0, dp(6), 0);
        topBar.addView(btnExtract, elp);
        root.addView(topBar);

        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackgroundColor(getColorCompat(R.color.operit_surface));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        segBtnFacts = makeSegButton("事实 / 画像 / 事件", 0);
        segBtnSummary = makeSegButton("每日摘要", 1);
        seg.addView(segBtnFacts, segLp());
        seg.addView(segBtnSummary, segLp());
        root.addView(seg, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setPadding(dp(12), dp(10), dp(12), dp(10));
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listContainer, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        tvEmpty = new TextView(this);
        tvEmpty.setText("暂无记忆");
        tvEmpty.setTextSize(14f);
        tvEmpty.setTextColor(getColorCompat(R.color.operit_text_hint));
        tvEmpty.setGravity(Gravity.CENTER);
        tvEmpty.setPadding(0, dp(30), 0, 0);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(12), dp(10), dp(12), dp(12));
        Button btnExport = new Button(this);
        btnExport.setText("导出备份");
        btnExport.setTextSize(14f);
        btnExport.setTextColor(Color.WHITE);
        btnExport.setAllCaps(false);
        btnExport.setBackgroundResource(R.drawable.bg_btn_primary);
        btnExport.setOnClickListener(v -> doExport());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        btnLp.setMargins(0, 0, dp(6), 0);
        bottom.addView(btnExport, btnLp);

        Button btnImport = new Button(this);
        btnImport.setText("导入备份");
        btnImport.setTextSize(14f);
        btnImport.setTextColor(getColorCompat(R.color.operit_accent));
        btnImport.setAllCaps(false);
        btnImport.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnImport.setOnClickListener(v -> doImport());
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        ilp.setMargins(dp(6), 0, 0, 0);
        bottom.addView(btnImport, ilp);
        root.addView(bottom);

        refreshSegState();
        setContentView(root);
    }

    private Button makeSegButton(String text, int tab) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(13f);
        b.setAllCaps(false);
        b.setOnClickListener(v -> {
            currentTab = tab;
            refreshSegState();
            refreshList();
        });
        return b;
    }

    private void refreshSegState() {
        if (segBtnFacts == null || segBtnSummary == null) return;
        applySegStyle(segBtnFacts, currentTab == 0);
        applySegStyle(segBtnSummary, currentTab == 1);
    }

    private void applySegStyle(Button b, boolean isActive) {
        b.setBackgroundColor(getColorCompat(isActive
                ? R.color.operit_primary_container
                : android.R.color.transparent));
        b.setTextColor(getColorCompat(isActive
                ? R.color.operit_accent
                : R.color.operit_text_secondary));
    }

    private LinearLayout.LayoutParams segLp() {
        return new LinearLayout.LayoutParams(0, dp(44), 1f);
    }

    private void refreshList() {
        listContainer.removeAllViews();
        // v1.24.0：在顶部加词云视图
        renderWordCloud();
        if (currentTab == 0) {
            List<MemoryStore.Fact> facts = ms.getAllFacts();
            if (facts.isEmpty()) {
                listContainer.addView(tvEmpty);
                return;
            }
            for (MemoryStore.Fact f : facts) {
                listContainer.addView(buildFactRow(f));
            }
        } else {
            List<MemoryStore.DailySummary> sums = ms.getAllSummaries();
            if (sums.isEmpty()) {
                listContainer.addView(tvEmpty);
                return;
            }
            for (MemoryStore.DailySummary s : sums) {
                listContainer.addView(buildSummaryRow(s));
            }
        }
    }

    private View buildFactRow(MemoryStore.Fact f) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(10);
        box.setLayoutParams(blp);

        TextView tvCat = new TextView(this);
        tvCat.setText("[" + f.category + "]  " + fmt(f.lastConfirmed));
        tvCat.setTextSize(11f);
        tvCat.setTextColor(getColorCompat(R.color.operit_text_secondary));
        box.addView(tvCat);

        TextView tvContent = new TextView(this);
        tvContent.setText(f.content);
        tvContent.setTextSize(14f);
        tvContent.setTextColor(getColorCompat(R.color.operit_text_primary));
        tvContent.setPadding(0, dp(4), 0, 0);
        box.addView(tvContent);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(makeSmallButton("编辑", v -> editFact(f)));
        actions.addView(makeSmallButton("删除", v -> deleteFact(f)));
        box.addView(actions);
        return box;
    }

    private View buildSummaryRow(MemoryStore.DailySummary s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));
        box.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.bottomMargin = dp(10);
        box.setLayoutParams(blp);

        TextView tvDate = new TextView(this);
        tvDate.setText(s.date + "  " + fmt(s.createdAt));
        tvDate.setTextSize(11f);
        tvDate.setTextColor(getColorCompat(R.color.operit_text_secondary));
        box.addView(tvDate);

        TextView tvSummary = new TextView(this);
        tvSummary.setText(s.summary);
        tvSummary.setTextSize(14f);
        tvSummary.setTextColor(getColorCompat(R.color.operit_text_primary));
        tvSummary.setPadding(0, dp(4), 0, 0);
        box.addView(tvSummary);

        if (s.moodSummary != null && !s.moodSummary.isEmpty()) {
            TextView tvMood = new TextView(this);
            tvMood.setText("心情：" + s.moodSummary);
            tvMood.setTextSize(12f);
            tvMood.setTextColor(getColorCompat(R.color.operit_text_secondary));
            tvMood.setPadding(0, dp(2), 0, 0);
            box.addView(tvMood);
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(makeSmallButton("编辑", v -> editSummary(s)));
        actions.addView(makeSmallButton("删除", v -> deleteSummary(s)));
        box.addView(actions);
        return box;
    }

    private Button makeSmallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setTextColor(getColorCompat(R.color.brand));
        b.setBackgroundResource(R.drawable.bg_btn_secondary);
        b.setPadding(dp(14), dp(4), dp(14), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.setMargins(dp(6), 0, 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }

    private void editFact(MemoryStore.Fact f) {
        EditText et = new EditText(this);
        et.setText(f.content);
        et.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("编辑事实")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String txt = et.getText().toString();
                    if (txt.trim().isEmpty()) {
                        toast("内容不能为空");
                        return;
                    }
                    ms.updateFact(f.id, txt);
                    toast("已保存");
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteFact(MemoryStore.Fact f) {
        new AlertDialog.Builder(this)
                .setTitle("删除记忆")
                .setMessage(f.content)
                .setPositiveButton("删除", (d, w) -> {
                    ms.deleteFact(f.id);
                    toast("已删除");
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void editSummary(MemoryStore.DailySummary s) {
        EditText et = new EditText(this);
        et.setText(s.summary);
        et.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("编辑摘要")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String txt = et.getText().toString();
                    if (txt.trim().isEmpty()) {
                        toast("内容不能为空");
                        return;
                    }
                    ms.updateSummary(s.id, txt, s.moodSummary);
                    toast("已保存");
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteSummary(MemoryStore.DailySummary s) {
        new AlertDialog.Builder(this)
                .setTitle("删除摘要")
                .setMessage(s.summary)
                .setPositiveButton("删除", (d, w) -> {
                    ms.deleteSummary(s.id);
                    toast("已删除");
                    refreshList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doExport() {
        try {
            String json = ms.exportJson();
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            File file = new File(dir, "memory_backup_" + System.currentTimeMillis() + ".json");
            try (FileWriter fw = new FileWriter(file)) {
                fw.write(json);
            }
            toast("已导出：" + file.getAbsolutePath());
        } catch (Exception e) {
            toast("导出失败：" + UiKit.safeMsg(e));
        }
    }

    private void doImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;
            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(getContentResolver().openInputStream(uri)))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
                ms.importJson(sb.toString());
                toast("导入成功");
                refreshList();
            } catch (org.json.JSONException e) {
                toast("备份文件无效：" + UiKit.safeMsg(e));
            } catch (Exception e) {
                toast("导入失败：" + UiKit.safeMsg(e));
            }
        }
    }

    private String fmt(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
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

    private LinearLayout.LayoutParams btnLp(int w, int h) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(w), dp(h));
        lp.setMargins(dp(4), 0, dp(4), 0);
        return lp;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
    }

    /** v1.24.0：渲染词云 */
    private void renderWordCloud() {
        try {
            com.digitallife.memory.MemoryRetriever retriever =
                    new com.digitallife.memory.MemoryRetriever(this);
            java.util.List<com.digitallife.memory.MemoryEntry> entries = retriever.retrieve("");
            if (entries.isEmpty()) return;
            com.digitallife.memory.MemoryGraphView cloud =
                    new com.digitallife.memory.MemoryGraphView(this);
            cloud.setEntries(entries);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(160));
            lp.bottomMargin = dp(8);
            listContainer.addView(cloud, lp);
        } catch (Exception e) {
            // 静默失败
        }
    }

    /** v1.24.0：触发 LLM 提取 */
    private void triggerExtraction() {
        com.digitallife.memory.MemoryExtractor extractor =
                new com.digitallife.memory.MemoryExtractor(this);
        Toast.makeText(this, "正在让 AI 整理记忆…", Toast.LENGTH_SHORT).show();
        extractor.extractNow(new com.digitallife.memory.MemoryExtractor.Listener() {
            @Override
            public void onExtracted(int newCount, int forgottenCount) {
                if (destroyed) return;
                handler.post(() -> {
                    String msg = "提取完成：新增 " + newCount + " 条";
                    if (forgottenCount > 0) msg += "，遗忘 " + forgottenCount + " 条";
                    Toast.makeText(MemoryManageActivity.this, msg, Toast.LENGTH_SHORT).show();
                    refreshList();
                });
            }
            @Override
            public void onError(String err) {
                if (destroyed) return;
                handler.post(() -> Toast.makeText(MemoryManageActivity.this,
                        "提取失败：" + err, Toast.LENGTH_SHORT).show());
            }
        });
    }
}
