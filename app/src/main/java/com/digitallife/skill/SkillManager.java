package com.digitallife.skill;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SKILL.md 技能包管理器（参考 OpenMinis 的技能系统）。
 * 技能 = files/skills/&lt;name&gt;/SKILL.md，front-matter 携带 name/description 用于
 * 触发判断，正文按需由模型经 load_skill 工具读取——元数据常驻、正文按需加载。
 * 首次启动时把 assets/skills/ 下的内置技能复制到 files/skills/。
 */
public class SkillManager {

    private static final String ROOT = "skills";

    public static class Skill {
        public String name;
        public String description;
        public String body; // SKILL.md 正文（front-matter 之后的内容）
        public String dir;
    }

    /** 首启复制 assets/skills/ 内置技能到 files/skills/（幂等，不覆盖用户已有技能） */
    public static void ensureDefaults(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), ROOT);
            if (!dir.exists() && !dir.mkdirs()) return;
            AssetManager am = ctx.getAssets();
            String[] names = am.list(ROOT);
            if (names == null) return;
            for (String skillDir : names) {
                File targetDir = new File(dir, skillDir);
                File markdown = new File(targetDir, "SKILL.md");
                if (markdown.exists()) continue; // 已存在（含用户改过的），不覆盖
                try (InputStream in = am.open(ROOT + "/" + skillDir + "/SKILL.md")) {
                    if (!targetDir.exists() && !targetDir.mkdirs()) continue;
                    copy(in, markdown);
                } catch (IOException ignored) {
                    // assets 里该技能缺 SKILL.md，跳过
                }
            }
        } catch (IOException ignored) {
        }
    }

    public static List<Skill> list(Context ctx) {
        List<Skill> out = new ArrayList<>();
        File dir = new File(ctx.getFilesDir(), ROOT);
        File[] entries = dir.exists() ? dir.listFiles() : null;
        if (entries == null) return out;
        for (File d : entries) {
            if (!d.isDirectory()) continue;
            File md = new File(d, "SKILL.md");
            if (!md.isFile()) continue;
            Skill s = parse(md, d.getName());
            if (s != null) out.add(s);
        }
        return out;
    }

    /** 按名称加载技能（模型确认命中后读取正文） */
    public static Skill find(Context ctx, String name) {
        for (Skill s : list(ctx)) {
            if (s.name.equalsIgnoreCase(name)) return s;
        }
        return null;
    }

    /** 人类可读的技能清单（名称 + 描述，供 skill_summary 工具返回） */
    public static String summarize(Context ctx) {
        List<Skill> skills = list(ctx);
        if (skills.isEmpty()) return "当前没有已安装技能";
        StringBuilder sb = new StringBuilder("已安装 " + skills.size() + " 个技能：");
        for (Skill s : skills) {
            sb.append("\n- ").append(s.name).append("：").append(s.description);
        }
        sb.append("\n\n确认命中某个技能时，用 load_skill 加载它的完整执行流程。");
        return sb.toString();
    }

    // ============ 内部 ============

    private static Skill parse(File md, String fallbackName) {
        try {
            StringBuilder raw = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(md), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    raw.append(line).append('\n');
                }
            }
            String text = raw.toString();
            Skill s = new Skill();
            s.dir = md.getParentFile().getName();
            s.name = fallbackName;
            s.description = "";
            // 极简 front-matter 解析：--- 起始，逐行 name:/description:
            if (text.startsWith("---")) {
                int end = text.indexOf("\n---", 3);
                if (end > 0) {
                    String fm = text.substring(3, end);
                    for (String line : fm.split("\n")) {
                        String t = line.trim();
                        if (t.startsWith("name:")) {
                            s.name = t.substring(5).trim();
                        } else if (t.startsWith("description:")) {
                            s.description = t.substring(12).trim();
                        }
                    }
                    s.body = text.substring(end + 4).trim();
                }
            }
            if (s.body == null || s.body.isEmpty()) s.body = text;
            if (s.name == null || s.name.isEmpty()) s.name = fallbackName;
            return s;
        } catch (IOException e) {
            return null;
        }
    }

    private static void copy(InputStream in, File out) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
            }
        }
    }
}
