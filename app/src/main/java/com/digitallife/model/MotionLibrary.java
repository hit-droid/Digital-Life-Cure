package com.digitallife.model;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 跨模型动作库：把内置（assets/）+ 导入（files/models/）两类模型里的动作集中读出来。
 *
 * <p>动作 = 模型 json 里声明的动作组（Idle / TapBody / 自定义）；组内每项 {@code {File, Loop}}
 * 指向模型目录内的 {@code .motion3.json}。只有被声明进组的动作，Live2D 引擎才播得出；
 * 磁盘上孤立、未声明的 {@code .motion3.json} 播不出来，单独归到 {@link ModelActions#orphans}。</p>
 *
 * <p>与 C++ 侧加载顺序一致：优先 {@code .model.json}，其次 {@code .model3.json}
 * （见 {@code LAppLive2DManager}）。本类**只读**，不修改任何文件——写回在 {@link ModelManager}。</p>
 */
public final class MotionLibrary {

    private static final String TAG = "MotionLibrary";
    private static final String MOTION_SUFFIX = ".motion3.json";

    private MotionLibrary() {
    }

    /** 单个动作（以「文件」为粒度；同一文件被多个组引用时，各组各有一份记录） */
    public static final class Motion {
        /** 相对模型目录的路径（统一用 '/' 分隔） */
        public final String file;
        /** 所属动作组名；孤立文件为 null */
        public final String group;
        /** 是否被动作组声明（= 引擎能播） */
        public final boolean declared;
        /** 文件是否真实存在 */
        public final boolean exists;
        /** 时长（秒）；解析失败为 0 */
        public final double duration;
        public final boolean loop;
        public final int curveCount;

        Motion(String file, String group, boolean declared, boolean exists,
               double duration, boolean loop, int curveCount) {
            this.file = file;
            this.group = group;
            this.declared = declared;
            this.exists = exists;
            this.duration = duration;
            this.loop = loop;
            this.curveCount = curveCount;
        }

        /** 动作基名（去掉 .motion3.json）。用于展示与导出文件名 */
        public String displayName() {
            String n = file;
            int slash = n.lastIndexOf('/');
            if (slash >= 0) n = n.substring(slash + 1);
            if (n.toLowerCase(Locale.ROOT).endsWith(MOTION_SUFFIX)) {
                n = n.substring(0, n.length() - MOTION_SUFFIX.length());
            }
            return n;
        }
    }

    /** 一个动作组及其成员 */
    public static final class Group {
        public final String name;
        public final List<Motion> motions = new ArrayList<>();

        Group(String name) {
            this.name = name;
        }
    }

    /** 一个模型里的全部动作 */
    public static final class ModelActions {
        public final String dirName;
        public final boolean imported;
        public final boolean isDefault;
        /** 实际读取到的模型 json 名；null = 没找到 json */
        public final String jsonName;
        public final List<Group> groups;
        public final List<Motion> orphans;
        /** 目录内 .motion3.json 总数（含孤立） */
        public final int motionFileCount;
        /** 至少有一个「声明 + 文件存在」的动作，即真有可播放动作 */
        public boolean playable;

        ModelActions(String dirName, boolean imported, boolean isDefault, String jsonName,
                     List<Group> groups, List<Motion> orphans, int motionFileCount) {
            this.dirName = dirName;
            this.imported = imported;
            this.isDefault = isDefault;
            this.jsonName = jsonName;
            this.groups = groups;
            this.orphans = orphans;
            this.motionFileCount = motionFileCount;
        }

        public int groupCount() {
            return groups.size();
        }

        /** 声明动作数（组内动作之和，同一文件被多组引用会重复计数——这是「动作组视角」的口径） */
        public int declaredCount() {
            int n = 0;
            for (Group g : groups) n += g.motions.size();
            return n;
        }
    }

    /** 扫描全部模型（内置按名排序在前，导入在后） */
    public static List<ModelActions> scanAll(Context ctx, String defaultDir) {
        String def = defaultDir == null ? "" : defaultDir;
        List<ModelActions> out = new ArrayList<>();
        List<String> builtin = listBuiltinModelDirs(ctx);
        for (String d : builtin) out.add(scanOne(ctx, d, false, d.equals(def)));
        List<String> imported = ModelManager.listImportedModelDirs(ctx);
        for (String d : imported) {
            if (containsDir(out, d)) continue;
            out.add(scanOne(ctx, d, true, d.equals(def)));
        }
        return out;
    }

    /** 目标模型是否为「导入（可写）」模型 */
    public static boolean isImported(Context ctx, String dirName) {
        return ModelManager.listImportedModelDirs(ctx).contains(dirName);
    }

    /** 打开模型目录内某文件的输入流（内置走 assets，导入走 files） */
    public static InputStream openStream(Context ctx, String dirName, boolean imported, String rel)
            throws Exception {
        if (imported) {
            return new java.io.FileInputStream(new File(modelFile(ctx, dirName), rel));
        }
        return ctx.getAssets().open(dirName + "/" + rel);
    }

    /** 读取模型目录内某文件的全部字节 */
    public static byte[] readBytes(Context ctx, String dirName, boolean imported, String rel)
            throws Exception {
        try (InputStream in = openStream(ctx, dirName, imported, rel)) {
            return readAll(in);
        }
    }

    /**
     * 读取一个动作文件引用的参数 ID（{@code Curves} 中 {@code Target=Parameter} 的 {@code Id}）。
     * 用于跨模型复用的兼容性判定（见 {@link MotionCompat}）。解析失败返回空集。
     */
    public static Set<String> motionParameterIds(Context ctx, String dirName, boolean imported,
                                                 String rel) {
        Set<String> out = new LinkedHashSet<>();
        try {
            JSONObject o = new JSONObject(new String(readBytes(ctx, dirName, imported, rel),
                    StandardCharsets.UTF_8));
            JSONArray curves = o.optJSONArray("Curves");
            if (curves != null) {
                for (int i = 0; i < curves.length(); i++) {
                    JSONObject c = curves.optJSONObject(i);
                    if (c == null) continue;
                    if (!"Parameter".equals(c.optString("Target", "Parameter"))) continue;
                    String id = c.optString("Id", "");
                    if (!id.isEmpty()) out.add(id);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 读取目标模型拥有的参数 ID 清单（{@code .cdi3.json} 的 {@code Parameters[].Id}）。
     *
     * @return 参数 ID 集合；<b>null</b> 表示该模型没有 {@code .cdi3.json}（取不到清单，无法判定）
     */
    public static Set<String> modelParameterIds(Context ctx, String dirName, boolean imported) {
        try {
            List<String> files = new ArrayList<>();
            if (imported) collectFiles(modelFile(ctx, dirName), "", files);
            else collectAssets(ctx, dirName, "", files);
            String cdi = null;
            for (String f : files) {
                if (f.toLowerCase(Locale.ROOT).endsWith(".cdi3.json")) {
                    cdi = f;
                    break;
                }
            }
            if (cdi == null) return null;
            JSONObject o = new JSONObject(new String(readBytes(ctx, dirName, imported, cdi),
                    StandardCharsets.UTF_8));
            JSONArray ps = o.optJSONArray("Parameters");
            Set<String> out = new HashSet<>();
            if (ps != null) {
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject p = ps.optJSONObject(i);
                    if (p == null) continue;
                    String id = p.optString("Id", "");
                    if (!id.isEmpty()) out.add(id);
                }
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    // ==================== 内部 ====================

    private static File modelFile(Context ctx, String dirName) {
        return new File(new File(ctx.getFilesDir(), "models"), dirName);
    }

    private static boolean containsDir(List<ModelActions> list, String d) {
        for (ModelActions m : list) if (m.dirName.equals(d)) return true;
        return false;
    }

    /**
     * 内置模型：assets 顶层目录中「含模型 json」者（本项目即 huohuo）。
     *
     * <p>取首段作为候选目录名：真机 {@code list("")} 只给顶层条目（{@code "huohuo"}），
     * 而 Robolectric 会给被扁平化的相对路径（{@code "huohuo/huohuo.model.json"}）；
     * 按 '/' 切首段后两种契约都能归一。</p>
     */
    private static List<String> listBuiltinModelDirs(Context ctx) {
        List<String> out = new ArrayList<>();
        try {
            String[] top = ctx.getAssets().list("");
            if (top == null) return out;
            Set<String> candidates = new HashSet<>();
            for (String name : top) {
                int slash = name.indexOf('/');
                candidates.add(slash >= 0 ? name.substring(0, slash) : name);
            }
            for (String name : candidates) {
                List<String> files = new ArrayList<>();
                collectAssets(ctx, name, "", files);
                if (firstModelJson(files) != null) out.add(name);
            }
        } catch (Exception e) {
            Log.w(TAG, "listBuiltinModelDirs failed", e);
        }
        Collections.sort(out);
        return out;
    }

    private static ModelActions scanOne(Context ctx, String dir, boolean imported, boolean isDefault) {
        // 1) 目录内全部文件（递归）
        List<String> files = new ArrayList<>();
        if (imported) {
            collectFiles(modelFile(ctx, dir), "", files);
        } else {
            collectAssets(ctx, dir, "", files);
        }
        Collections.sort(files);
        Set<String> fileSet = new HashSet<>(files);

        // 2) 模型 json（优先 .model.json，其次 .model3.json）
        String jsonName = firstModelJson(files);
        String content = null;
        if (jsonName != null) {
            try {
                content = new String(readBytes(ctx, dir, imported, jsonName), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }

        // 3) 解析声明的动作组
        List<Group> groups = new ArrayList<>();
        Set<String> declared = new HashSet<>();
        if (content != null) {
            try {
                JSONObject root = new JSONObject(content);
                JSONObject motions = null;
                JSONObject fr = root.optJSONObject("FileReferences");
                if (fr != null) motions = fr.optJSONObject("Motions");
                if (motions == null) motions = root.optJSONObject("Motions");
                if (motions == null) motions = root.optJSONObject("motions");
                if (motions != null) {
                    List<String> names = new ArrayList<>();
                    Iterator<String> it = motions.keys();
                    while (it.hasNext()) names.add(it.next());
                    Collections.sort(names);
                    Map<String, double[]> metaCache = new HashMap<>();
                    for (String g : names) {
                        JSONArray arr = motions.optJSONArray(g);
                        if (arr == null) continue;
                        Group grp = new Group(g);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject item = arr.optJSONObject(i);
                            if (item == null) continue;
                            String file = normalize(item.optString("File", ""));
                            if (file.isEmpty()) continue;
                            boolean exists = fileSet.contains(file);
                            double[] meta = exists ? meta(ctx, dir, imported, file, metaCache)
                                                   : null;
                            double dur = meta != null ? meta[0] : 0;
                            boolean loop = meta != null ? meta[1] > 0.5 : item.optBoolean("Loop", false);
                            int curves = meta != null ? (int) meta[2] : 0;
                            grp.motions.add(new Motion(file, g, true, exists, dur, loop, curves));
                            declared.add(file);
                        }
                        if (!grp.motions.isEmpty()) groups.add(grp);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "parse model json failed: " + dir, e);
            }
        }

        // 4) 孤立文件：所有 .motion3.json 中未被声明的
        int motionCount = 0;
        List<Motion> orphans = new ArrayList<>();
        for (String f : files) {
            if (!f.toLowerCase(Locale.ROOT).endsWith(MOTION_SUFFIX)) continue;
            motionCount++;
            if (declared.contains(f)) continue;
            double[] meta = meta(ctx, dir, imported, f, null);
            orphans.add(new Motion(f, null, false, true,
                    meta != null ? meta[0] : 0,
                    meta != null && meta[1] > 0.5,
                    meta != null ? (int) meta[2] : 0));
        }

        ModelActions ma = new ModelActions(dir, imported, isDefault, jsonName,
                groups, orphans, motionCount);
        for (Group g : groups) {
            for (Motion m : g.motions) {
                if (m.exists) {
                    ma.playable = true;
                    break;
                }
            }
        }
        return ma;
    }

    /** 挑模型主 json：优先第一个 .model.json，其次第一个 .model3.json */
    private static String firstModelJson(List<String> files) {
        for (String f : files) {
            if (f.toLowerCase(Locale.ROOT).endsWith(".model.json")) return f;
        }
        for (String f : files) {
            if (f.toLowerCase(Locale.ROOT).endsWith(".model3.json")) return f;
        }
        return null;
    }

    private static double[] meta(Context ctx, String dir, boolean imported, String rel,
                                 Map<String, double[]> cache) {
        if (cache != null && cache.containsKey(rel)) return cache.get(rel);
        double[] out = null;
        try {
            JSONObject o = new JSONObject(new String(readBytes(ctx, dir, imported, rel),
                    StandardCharsets.UTF_8));
            JSONObject m = o.optJSONObject("Meta");
            double dur = m != null ? m.optDouble("Duration", 0) : 0;
            double loop = (m != null && m.optBoolean("Loop", false)) ? 1 : 0;
            double curves = m != null ? m.optInt("CurveCount", 0) : 0;
            out = new double[]{dur, loop, curves};
        } catch (Exception ignored) {
        }
        if (cache != null) cache.put(rel, out);
        return out;
    }

    private static void collectFiles(File root, String relDir, List<String> out) {
        File d = relDir.isEmpty() ? root : new File(root, relDir);
        File[] kids = d.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            String rel = relDir.isEmpty() ? k.getName() : relDir + "/" + k.getName();
            if (k.isDirectory()) collectFiles(root, rel, out);
            else out.add(rel);
        }
    }

    /**
     * 递归收集 assets 目录下全部文件（相对 {@code dir}，'/' 分隔）。
     *
     * <p>{@code list(path)} 的两种契约都要兼容：真机返回**直接子项**（子目录名无 '/'），
     * Robolectric 返回**扁平化相对路径**（含 '/'，如 {@code "huohuo.2048/texture_00.png"}）。
     * 含 '/' 的条目本身就是「路径 → 文件」，直接记账，不再下钻（否则会死循环）。</p>
     */
    private static void collectAssets(Context ctx, String dir, String relDir, List<String> out) {
        try {
            String path = relDir.isEmpty() ? dir : dir + "/" + relDir;
            String[] kids = ctx.getAssets().list(path);
            if (kids == null) return;
            for (String k : kids) {
                String rel = relDir.isEmpty() ? k : relDir + "/" + k;
                if (k.indexOf('/') >= 0) {
                    out.add(rel);
                    continue;
                }
                String[] sub = ctx.getAssets().list(path + "/" + k);
                if (sub != null && sub.length > 0) collectAssets(ctx, dir, rel, out);
                else out.add(rel);
            }
        } catch (Exception ignored) {
        }
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/').trim();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
