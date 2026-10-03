package com.digitallife.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 记忆相关度：查询关键词提取与命中计数（纯逻辑，无 Android 依赖，可单测）。
 *
 * 背景（v1.121.0）：项目里原本有两套分叉的"关键词召回"实现——
 * - {@link MemoryStore#retrieveRelatedFacts}：中文 2~6 字滑窗 + 英文 ≥3 字符 + 停用词；
 * - {@code com.digitallife.memory.MemoryRetriever}：直接用「整句 query」做 {@code contains}。
 * 后者对中文几乎必然召回为空（用户问"我喜欢喝什么咖啡"永远匹配不到记忆"喜欢喝美式"），
 * 于是对话大脑那条路的相关召回形同虚设。这里统一收敛成一份实现，两边共用。
 */
public final class MemoryRelevance {

    private static final Pattern EN_WORD = Pattern.compile("[a-z0-9]{3,}");
    private static final int ZH_MIN = 2;
    private static final int ZH_MAX = 6;

    private MemoryRelevance() {}

    /**
     * 提取查询关键词：英文/数字连续片段（≥3 字符）+ 中文 2~6 字滑窗片段，去停用词。
     * 保持零依赖，不引入 HanLP/Jieba 等分词库。
     */
    public static List<String> extractKeywords(String text) {
        ArrayList<String> kws = new ArrayList<>();
        if (text == null) return kws;
        String lower = text.toLowerCase(Locale.ROOT);

        Matcher en = EN_WORD.matcher(lower);
        while (en.find()) {
            String w = en.group();
            if (!STOP_WORDS.contains(w)) kws.add(w);
        }

        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                buf.append(c);
            } else {
                flushChinese(buf, kws);
            }
        }
        flushChinese(buf, kws);
        return kws;
    }

    /** 统计内容命中了多少个关键词。 */
    public static int countHits(List<String> keywords, String content) {
        if (keywords == null || keywords.isEmpty() || content == null || content.isEmpty()) return 0;
        String lower = content.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String k : keywords) {
            if (k != null && !k.isEmpty() && lower.contains(k)) hits++;
        }
        return hits;
    }

    /** 查询与内容是否存在任何关键词命中。 */
    public static boolean matches(String query, String content) {
        return countHits(extractKeywords(query), content) > 0;
    }

    /**
     * 相关度归一化到 (0,1]：命中关键词数 / 关键词总数。
     * 全部命中为 1.0；无命中为 0。
     */
    public static double relevance(String query, String content) {
        List<String> kws = extractKeywords(query);
        if (kws.isEmpty()) return 0;
        int hits = countHits(kws, content);
        if (hits == 0) return 0;
        double r = hits / (double) kws.size();
        return r > 1.0 ? 1.0 : r;
    }

    private static void flushChinese(StringBuilder buf, List<String> out) {
        if (buf.length() == 0) return;
        String s = buf.toString();
        for (int len = ZH_MIN; len <= Math.min(ZH_MAX, s.length()); len++) {
            for (int i = 0; i + len <= s.length(); i++) {
                String sub = s.substring(i, i + len);
                if (!STOP_WORDS.contains(sub)) out.add(sub);
            }
        }
        buf.setLength(0);
    }

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            // 英文
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "had", "her", "was", "one", "our", "out",
            "this", "that", "with", "have", "from", "they", "been", "said", "what", "when", "make", "like", "him",
            "into", "time", "very", "than", "only", "know", "just", "also", "your", "over", "such", "more",
            // 中文常见停用词
            "的", "了", "是", "在", "我", "你", "他", "她", "它", "们", "和", "与", "或", "也", "都", "就",
            "把", "被", "从", "到", "给", "很", "还", "可", "能", "让", "上", "下", "不", "没",
            "啊", "吗", "呢", "吧", "哦", "呀", "嗯", "啦", "哈"
    ));
}
