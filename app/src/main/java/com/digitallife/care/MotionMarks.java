package com.digitallife.care;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 动作标记（动作池 v1.175.0）：跨模型的**收藏**与**最近播放**。
 *
 * <p>动作池此前只能「按模型一张张翻」，常用的那几个动作每次都要翻到对应模型再点。本类把
 * 「收藏」固定到列表顶部、「最近播放」记最近点过的若干个，避免反复翻找。两者都是纯引用
 * （不复制文件），落盘于 {@code care_motion_marks}。</p>
 *
 * <p>引用用 {@link Ref}（模型目录 + 类型 + 组 / 文件 / 名）表达；身份以 {@link Ref#id()} 为准，
 * 同名去重。纯逻辑（去重 / 封顶 / 切换）与存储分开，前者可直接单测。</p>
 */
public final class MotionMarks {

    private static final String PREFS = "care_motion_marks";
    private static final String KEY_FAV = "favorites";
    private static final String KEY_RECENT = "recent";

    /** 最近播放保留条数 */
    public static final int MAX_RECENT = 15;

    private MotionMarks() {
    }

    /** 一条稳定引用：{@code modelDir} + 动作（组 + 文件）/ 表情（名 + 文件） */
    public static final class Ref {
        public final String modelDir;
        public final boolean motion;
        public final String group;
        public final String file;
        public final String name;

        public Ref(String modelDir, boolean motion, String group, String file, String name) {
            this.modelDir = modelDir;
            this.motion = motion;
            this.group = group;
            this.file = file;
            this.name = name;
        }

        /** 去重用的稳定身份键 */
        public String id() {
            return nz(modelDir) + "|" + (motion ? "m" : "e") + "|"
                    + nz(group) + "|" + nz(file) + "|" + nz(name);
        }
    }

    // ==================== 纯逻辑 ====================

    public static boolean contains(List<Ref> list, Ref ref) {
        if (list == null || ref == null) return false;
        String id = ref.id();
        for (Ref r : list) {
            if (r != null && id.equals(r.id())) return true;
        }
        return false;
    }

    /** 把 {@code ref} 放到最前（同名去重）并裁剪到 {@code cap} 条 */
    public static List<Ref> pushed(List<Ref> list, Ref ref, int cap) {
        List<Ref> out = new ArrayList<>();
        if (ref == null) {
            if (list != null) out.addAll(list);
            return out;
        }
        out.add(ref);
        if (list != null) {
            String id = ref.id();
            for (Ref r : list) {
                if (r != null && !id.equals(r.id())) out.add(r);
            }
        }
        while (out.size() > cap) out.remove(out.size() - 1);
        return out;
    }

    /** 切换收藏：已存在则移除，不存在则插到最前 */
    public static List<Ref> toggled(List<Ref> list, Ref ref) {
        List<Ref> out = new ArrayList<>();
        if (ref == null) {
            if (list != null) out.addAll(list);
            return out;
        }
        boolean existed = contains(list, ref);
        String id = ref.id();
        if (list != null) {
            for (Ref r : list) {
                if (r != null && !id.equals(r.id())) out.add(r);
            }
        }
        if (!existed) out.add(0, ref);
        return out;
    }

    /** 从列表中移除某引用（不存在则原样返回），用于清掉已失效的收藏 / 最近播放条目 */
    public static List<Ref> removed(List<Ref> list, Ref ref) {
        List<Ref> out = new ArrayList<>();
        if (list == null) return out;
        if (ref == null) {
            out.addAll(list);
            return out;
        }
        String id = ref.id();
        for (Ref r : list) {
            if (r != null && !id.equals(r.id())) out.add(r);
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ==================== 存储 ====================

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<Ref> favorites(Context ctx) {
        return read(ctx, KEY_FAV);
    }

    public static boolean isFavorite(Context ctx, Ref ref) {
        return contains(favorites(ctx), ref);
    }

    public static void toggleFavorite(Context ctx, Ref ref) {
        if (ref == null) return;
        write(ctx, KEY_FAV, toggled(favorites(ctx), ref));
    }

    public static List<Ref> recents(Context ctx) {
        return read(ctx, KEY_RECENT);
    }

    public static void pushRecent(Context ctx, Ref ref) {
        if (ref == null) return;
        write(ctx, KEY_RECENT, pushed(recents(ctx), ref, MAX_RECENT));
    }

    public static void removeRecent(Context ctx, Ref ref) {
        if (ref == null) return;
        write(ctx, KEY_RECENT, removed(recents(ctx), ref));
    }

    public static void clearRecents(Context ctx) {
        prefs(ctx).edit().remove(KEY_RECENT).apply();
    }

    private static List<Ref> read(Context ctx, String key) {
        List<Ref> out = new ArrayList<>();
        String raw = prefs(ctx).getString(key, null);
        if (raw == null || raw.isEmpty()) return out;
        try {
            return fromJson(new JSONArray(raw));
        } catch (Exception e) {
            return out;
        }
    }

    private static void write(Context ctx, String key, List<Ref> list) {
        prefs(ctx).edit().putString(key, toJson(list).toString()).apply();
    }

    static JSONArray toJson(List<Ref> list) {
        JSONArray arr = new JSONArray();
        if (list == null) return arr;
        for (Ref r : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("modelDir", r.modelDir);
                o.put("motion", r.motion);
                if (r.group != null) o.put("group", r.group);
                if (r.file != null) o.put("file", r.file);
                if (r.name != null) o.put("name", r.name);
            } catch (Exception ignored) {
            }
            arr.put(o);
        }
        return arr;
    }

    static List<Ref> fromJson(JSONArray arr) {
        List<Ref> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String modelDir = o.optString("modelDir", "");
            if (modelDir.isEmpty()) continue;
            out.add(new Ref(modelDir, o.optBoolean("motion", true),
                    o.optString("group", null), o.optString("file", null),
                    o.optString("name", null)));
        }
        return out;
    }
}
