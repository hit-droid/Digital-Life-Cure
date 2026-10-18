package com.digitallife.care;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 连招持久化：按**模型目录名**分别存放（同一 prefs 文件 {@code care_combos}，key = 模型目录名）。
 *
 * <p>连招只引用模型内已有的动作 / 表情，不写模型 json，因此**不要求模型可写**——内置模型也能
 * 编连招。落盘格式为 {@code [{name, steps:[{kind,group,file,name}]}]}，见 {@link ComboPlan}。</p>
 *
 * <p>值都嵌在同一个字符串里，读写即整表 JSON 往返；同名（忽略大小写）保存即覆盖。</p>
 */
public final class ComboStore {

    private static final String PREFS = "care_combos";

    private ComboStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读某模型下的全部连招（顺序 = 保存顺序） */
    public static List<ComboPlan.Combo> list(Context ctx, String modelDir) {
        List<ComboPlan.Combo> out = new ArrayList<>();
        if (modelDir == null || modelDir.isEmpty()) return out;
        String raw = prefs(ctx).getString(modelDir, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            return fromJson(new JSONArray(raw));
        } catch (Exception e) {
            return out;
        }
    }

    /** 新增或覆盖同名连招（同名忽略大小写） */
    public static void save(Context ctx, String modelDir, ComboPlan.Combo combo) {
        if (modelDir == null || modelDir.isEmpty() || combo == null) return;
        List<ComboPlan.Combo> list = list(ctx, modelDir);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).name != null && list.get(i).name.equalsIgnoreCase(combo.name)) {
                list.remove(i);
            }
        }
        list.add(combo);
        write(ctx, modelDir, list);
    }

    /** 删除某模型下的一个连招（严格同名） */
    public static void delete(Context ctx, String modelDir, String name) {
        if (modelDir == null || modelDir.isEmpty() || name == null) return;
        List<ComboPlan.Combo> list = list(ctx, modelDir);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (name.equals(list.get(i).name)) list.remove(i);
        }
        write(ctx, modelDir, list);
    }

    private static void write(Context ctx, String modelDir, List<ComboPlan.Combo> list) {
        prefs(ctx).edit().putString(modelDir, toJson(list).toString()).apply();
    }

    /** 序列化为 JSON 数组（缺省字段不写，读回时为 null） */
    static JSONArray toJson(List<ComboPlan.Combo> list) {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (ComboPlan.Combo c : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", c.name);
                JSONArray steps = new JSONArray();
                for (ComboPlan.Step s : c.steps) {
                    JSONObject so = new JSONObject();
                    so.put("kind", s.kind);
                    if (s.group != null) so.put("group", s.group);
                    if (s.file != null) so.put("file", s.file);
                    if (s.name != null) so.put("name", s.name);
                    steps.put(so);
                }
                o.put("steps", steps);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        return arr;
    }

    /** 反序列化；缺名 / 坏字段的条目直接跳过，保证读不抛 */
    static List<ComboPlan.Combo> fromJson(JSONArray arr) {
        List<ComboPlan.Combo> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = ComboPlan.sanitizeName(o.optString("name", ""));
            if (name.isEmpty()) continue;
            ComboPlan.Combo c = new ComboPlan.Combo(name);
            JSONArray steps = o.optJSONArray("steps");
            if (steps != null) {
                for (int j = 0; j < steps.length(); j++) {
                    JSONObject so = steps.optJSONObject(j);
                    if (so == null) continue;
                    c.steps.add(new ComboPlan.Step(
                            so.optString("kind", ComboPlan.KIND_MOTION),
                            so.optString("group", null),
                            so.optString("file", null),
                            so.optString("name", null)));
                }
            }
            out.add(c);
        }
        return out;
    }
}
