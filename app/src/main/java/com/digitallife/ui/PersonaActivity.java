package com.digitallife.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
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
import com.digitallife.persona.Persona;
import com.digitallife.persona.PersonaManager;

import java.util.List;

/**
 * 角色管理（v1.24.0）。
 * 列表展示所有角色，可创建/编辑/删除/切换。
 * 借鉴 Operit AI 的角色卡管理设计。
 */
public class PersonaActivity extends Activity {

    private LinearLayout listContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UiKit.color(this, R.color.operit_bg));

        // 顶栏
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundResource(R.drawable.bg_operit_topbar);
        topBar.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12),
                UiKit.dp(this, 12), UiKit.dp(this, 12));
        topBar.setElevation(UiKit.dp(this, 4));

        Button btnBack = new Button(this);
        btnBack.setText("←");
        btnBack.setTextSize(20f);
        btnBack.setTextColor(Color.WHITE);
        btnBack.setBackgroundColor(Color.TRANSPARENT);
        btnBack.setAllCaps(false);
        btnBack.setOnClickListener(v -> finish());
        topBar.addView(btnBack, new LinearLayout.LayoutParams(
                UiKit.dp(this, 36), UiKit.dp(this, 36)));

        TextView title = new TextView(this);
        title.setText("角色管理");
        title.setTextSize(18f);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = UiKit.dp(this, 8);
        topBar.addView(title, tlp);

        Button btnNew = new Button(this);
        btnNew.setText("＋ 新建");
        btnNew.setTextSize(13f);
        btnNew.setTextColor(Color.WHITE);
        btnNew.setAllCaps(false);
        btnNew.setBackgroundResource(R.drawable.bg_btn_primary);
        btnNew.setPadding(UiKit.dp(this, 10), 0,
                UiKit.dp(this, 10), 0);
        btnNew.setOnClickListener(v -> showEditDialog(null));
        topBar.addView(btnNew, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 32)));
        root.addView(topBar);

        // 列表
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12),
                UiKit.dp(this, 12), UiKit.dp(this, 12));
        scroll.addView(listContainer);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        renderList();
        return root;
    }

    private void renderList() {
        listContainer.removeAllViews();
        PersonaManager pm = PersonaManager.get(this);
        Persona active = pm.active();
        List<Persona> all = pm.all();
        for (Persona p : all) {
            listContainer.addView(buildPersonaRow(p, p.id.equals(active.id)));
        }
        if (all.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无角色，点击右上角新建");
            empty.setTextSize(14f);
            empty.setTextColor(UiKit.color(this, R.color.operit_text_hint));
            empty.setPadding(UiKit.dp(this, 20), UiKit.dp(this, 40),
                    UiKit.dp(this, 20), UiKit.dp(this, 20));
            listContainer.addView(empty);
        }
    }

    private View buildPersonaRow(Persona p, boolean isActive) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(isActive
                ? R.drawable.bg_card_active : R.drawable.bg_card);
        card.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12),
                UiKit.dp(this, 14), UiKit.dp(this, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = UiKit.dp(this, 8);
        card.setLayoutParams(lp);

        // 顶行：名称 + 当前标记
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(p.name);
        name.setTextSize(16f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(UiKit.color(this, R.color.operit_text_primary));
        headRow.addView(name, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (isActive) {
            TextView badge = new TextView(this);
            badge.setText("当前");
            badge.setTextSize(11f);
            badge.setTextColor(Color.WHITE);
            badge.setBackgroundResource(R.drawable.bg_btn_primary);
            badge.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 2),
                    UiKit.dp(this, 8), UiKit.dp(this, 2));
            headRow.addView(badge);
        }
        card.addView(headRow);

        // 副标题
        if (p.subtitle != null && !p.subtitle.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(p.subtitle);
            sub.setTextSize(12f);
            sub.setTextColor(UiKit.color(this, R.color.operit_text_secondary));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = UiKit.dp(this, 2);
            sub.setLayoutParams(slp);
            card.addView(sub);
        }

        // 人格预览
        if (p.personality != null && !p.personality.isEmpty()) {
            String preview = p.personality.length() > 80
                    ? p.personality.substring(0, 80) + "…" : p.personality;
            TextView per = new TextView(this);
            per.setText(preview);
            per.setTextSize(12f);
            per.setTextColor(UiKit.color(this, R.color.operit_text_hint));
            per.setLineSpacing(2f, 1f);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            plp.topMargin = UiKit.dp(this, 6);
            per.setLayoutParams(plp);
            card.addView(per);
        }

        // 操作按钮行
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = UiKit.dp(this, 10);
        actionRow.setLayoutParams(arlp);

        if (!isActive) {
            Button btnSwitch = new Button(this);
            btnSwitch.setText("切换");
            btnSwitch.setTextSize(12f);
            btnSwitch.setTextColor(Color.WHITE);
            btnSwitch.setAllCaps(false);
            btnSwitch.setBackgroundResource(R.drawable.bg_btn_primary);
            btnSwitch.setPadding(UiKit.dp(this, 12), 0,
                    UiKit.dp(this, 12), 0);
            btnSwitch.setOnClickListener(v -> {
                PersonaManager.get(this).switchTo(p.id);
                Toast.makeText(this, "已切换到「" + p.name + "」", Toast.LENGTH_SHORT).show();
                renderList();
            });
            actionRow.addView(btnSwitch);
        }

        Button btnEdit = new Button(this);
        btnEdit.setText("编辑");
        btnEdit.setTextSize(12f);
        btnEdit.setTextColor(Color.WHITE);
        btnEdit.setAllCaps(false);
        btnEdit.setBackgroundResource(R.drawable.bg_btn_secondary);
        btnEdit.setPadding(UiKit.dp(this, 12), 0,
                UiKit.dp(this, 12), 0);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 30));
        elp.leftMargin = UiKit.dp(this, 8);
        btnEdit.setLayoutParams(elp);
        btnEdit.setOnClickListener(v -> showEditDialog(p));
        actionRow.addView(btnEdit);

        if (!p.isDefault) {
            Button btnDel = new Button(this);
            btnDel.setText("删除");
            btnDel.setTextSize(12f);
            btnDel.setTextColor(Color.WHITE);
            btnDel.setAllCaps(false);
            btnDel.setBackgroundResource(R.drawable.bg_btn_secondary);
            btnDel.setPadding(UiKit.dp(this, 12), 0,
                    UiKit.dp(this, 12), 0);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, UiKit.dp(this, 30));
            dlp.leftMargin = UiKit.dp(this, 8);
            btnDel.setLayoutParams(dlp);
            btnDel.setOnClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("删除「" + p.name + "」?")
                        .setMessage("该操作不可恢复。")
                        .setPositiveButton("删除", (d, w) -> {
                            PersonaManager.get(this).store().delete(p.id);
                            renderList();
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
            actionRow.addView(btnDel);
        }
        card.addView(actionRow);
        return card;
    }

    private void showEditDialog(Persona existing) {
        boolean isNew = existing == null;
        Persona target = isNew ? new Persona() : existing;
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(20), dp(20), 0);

        final EditText etName = new EditText(this);
        etName.setHint("角色名（如：小汐）");
        etName.setText(isNew ? "" : target.name);
        container.addView(etName);

        final EditText etSub = new EditText(this);
        etSub.setHint("副标题（如：活泼的二次元少女）");
        etSub.setText(isNew ? "" : (target.subtitle != null ? target.subtitle : ""));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(12);
        etSub.setLayoutParams(slp);
        container.addView(etSub);

        final EditText etPer = new EditText(this);
        etPer.setHint("人格描述（注入 system prompt）");
        etPer.setText(isNew ? "" : (target.personality != null ? target.personality : ""));
        etPer.setMinLines(5);
        etPer.setGravity(Gravity.TOP);
        etPer.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(12);
        etPer.setLayoutParams(plp);
        container.addView(etPer);

        new AlertDialog.Builder(this)
                .setTitle(isNew ? "新建角色" : "编辑角色")
                .setView(container)
                .setPositiveButton("保存", (d, w) -> {
                    String name = etName.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "角色名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    target.name = name;
                    target.subtitle = etSub.getText().toString().trim();
                    target.personality = etPer.getText().toString().trim();
                    if (isNew) {
                        PersonaManager.get(this).store().add(target);
                    } else {
                        PersonaManager.get(this).store().update(target);
                    }
                    renderList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
