package com.digitallife.update;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 应用内更新检查的纯逻辑（JVM 可测）：版本号比较 + GitHub Releases 解析。
 *
 * <p>只做字符串解析与数值比较，<b>不碰网络、不碰 Android</b>——网络与线程在
 * {@link UpdateClient}，UI 在 {@code ui/AboutActivity}。这样版本比较这类容易出错的
 * 算术（"1.9" vs "1.10"、非数字 tag、预发布后缀）都能用单测钉死。</p>
 */
public final class UpdateChecker {

    /** 兜底下载页：Release 缺 html_url 时用它，保证「前往下载」永远有去处 */
    private static final String FALLBACK_URL =
            "https://github.com/hit-droid/Digital-Life-Cure/releases";

    private UpdateChecker() {
    }

    /** 一条 GitHub Release 的精简视图 */
    public static final class Release {
        /** 原始 tag，如 {@code v1.138.0} */
        public final String tag;
        /** 归一化版本，如 {@code 1.138.0} */
        public final String version;
        /** 详情 / 下载页 */
        public final String url;
        /** 更新说明，可能为空串 */
        public final String notes;
        public final boolean prerelease;

        Release(String tag, String version, String url, String notes, boolean prerelease) {
            this.tag = tag;
            this.version = version;
            this.url = url;
            this.notes = notes == null ? "" : notes;
            this.prerelease = prerelease;
        }
    }

    /** 解析 {@code /releases/latest} 返回的单个对象；非法或缺 tag 返回 null */
    public static Release parseRelease(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            return from(new JSONObject(json));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 releases 列表，返回版本最高的一条；没有可用项返回 null。
     *
     * <p>跳过 {@code draft}；{@code prerelease} 默认跳过（{@code allowPrerelease=false}）。
     * 按<b>版本号大小</b>而非数组顺序取最高，避免上游返回顺序变化时选错。</p>
     */
    public static Release pickLatest(String jsonArray, boolean allowPrerelease) {
        if (jsonArray == null || jsonArray.trim().isEmpty()) return null;
        try {
            JSONArray arr = new JSONArray(jsonArray);
            Release best = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.optBoolean("draft", false)) continue;
                boolean pre = o.optBoolean("prerelease", false);
                if (pre && !allowPrerelease) continue;
                Release r = from(o);
                if (r == null) continue;
                if (best == null || compare(r.version, best.version) > 0) best = r;
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private static Release from(JSONObject o) {
        String tag = o.optString("tag_name", "").trim();
        if (tag.isEmpty()) return null;
        String version = normalize(tag);
        if (version == null) return null;
        String url = o.optString("html_url", "").trim();
        if (url.isEmpty()) url = FALLBACK_URL;
        return new Release(tag, version, url, o.optString("body", ""),
                o.optBoolean("prerelease", false));
    }

    /** tag 是否比当前版本新；任一侧无法解析一律返回 false（宁可漏报，不可误报） */
    public static boolean isNewer(String current, String tag) {
        String a = normalize(current);
        String b = normalize(tag);
        if (a == null || b == null) return false;
        return compare(b, a) > 0;
    }

    /** 归一化版本比较：a &gt; b 返回正数、a &lt; b 返回负数、相等 0；无法解析按相等处理 */
    public static int compare(String a, String b) {
        int[] x = segments(a);
        int[] y = segments(b);
        if (x == null || y == null) return 0;
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int vx = i < x.length ? x[i] : 0;
            int vy = i < y.length ? y[i] : 0;
            if (vx != vy) return vx < vy ? -1 : 1;
        }
        return 0;
    }

    /**
     * 归一化版本：去 {@code v/V} 前缀、丢弃 {@code -}/{@code +} 之后的后缀（预发布 / 构建元数据），
     * 各段转数字重新拼接。不含数字段则返回 null。
     */
    public static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (s.charAt(0) == 'v' || s.charAt(0) == 'V') s = s.substring(1);
        int cut = s.length();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-' || c == '+') {
                cut = i;
                break;
            }
        }
        s = s.substring(0, cut).trim();
        int[] segs = segments(s);
        if (segs == null || segs.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(segs[i]);
        }
        return sb.toString();
    }

    /** 点分段转整数；任一段不含数字返回 null */
    private static int[] segments(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        String[] parts = t.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) return null;
            int num = 0;
            boolean any = false;
            for (int j = 0; j < p.length(); j++) {
                char c = p.charAt(j);
                if (c < '0' || c > '9') break;
                num = num * 10 + (c - '0');
                any = true;
                if (num > 1_000_000) {   // 防溢出：非版本号的超长数字段
                    return null;
                }
            }
            if (!any) return null;
            out[i] = num;
        }
        return out;
    }
}
