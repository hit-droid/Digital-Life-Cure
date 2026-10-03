package com.digitallife.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.digitallife.memory.MemoryExtractionParser.NewMemory;
import com.digitallife.memory.MemoryExtractionParser.Result;

import org.junit.Test;

/**
 * {@link MemoryExtractionParser} 的回归。
 *
 * 这段解析原先埋在 MemoryExtractor.handleResult 里、完全无测试，
 * 却直接决定哪些内容会被写进长期记忆。重点覆盖几个真实会出错的边界：
 * ```json 包裹、正文里混解释文字、分类名乱写、weight 越界、条数超限、超长内容。
 */
public class MemoryExtractionParserTest {

    // ==================== JSON 抽取 ====================

    @Test
    public void parse_plainJsonObject() {
        String raw = "{\"new_memories\":[{\"content\":\"喜欢喝美式\","
                + "\"category\":\"preference\",\"weight\":0.8}],\"forgotten\":[]}";
        Result r = MemoryExtractionParser.parse(raw);
        assertNotNull(r);
        assertEquals(1, r.memories.size());
        assertEquals("喜欢喝美式", r.memories.get(0).content);
        assertEquals("preference", r.memories.get(0).category);
        assertEquals(0.8, r.memories.get(0).weight, 1e-9);
        assertTrue(r.forgotten.isEmpty());
    }

    @Test
    public void parse_fencedJsonBlock() {
        String raw = "好的，提取结果如下：\n```json\n{\"new_memories\":[{\"content\":\"养了一只猫\","
                + "\"category\":\"fact\",\"weight\":0.6}]}\n```\n以上。";
        Result r = MemoryExtractionParser.parse(raw);
        assertNotNull(r);
        assertEquals(1, r.memories.size());
        assertEquals("养了一只猫", r.memories.get(0).content);
    }

    @Test
    public void extractJson_ignoresProseAroundObject() {
        String raw = "前置说明，真正的对象在后面 {\"a\":1} 结束";
        assertEquals("{\"a\":1}", MemoryExtractionParser.extractJson(raw));
    }

    @Test
    public void extractJson_bracesInsideStringDoNotBreakDepth() {
        String raw = "{\"content\":\"他写了 {abc} 这段\",\"n\":1}";
        assertEquals(raw, MemoryExtractionParser.extractJson(raw));
    }

    @Test
    public void extractJson_escapedQuoteInsideString() {
        String raw = "{\"content\":\"他说\\\"你好\\\"\",\"n\":1}";
        assertEquals(raw, MemoryExtractionParser.extractJson(raw));
    }

    @Test
    public void extractJson_nullOrNoBraceReturnsNull() {
        assertNull(MemoryExtractionParser.extractJson(null));
        assertNull(MemoryExtractionParser.extractJson("没有任何 JSON"));
    }

    // ==================== parse 失败 / 空 ====================

    @Test
    public void parse_noJsonReturnsNull() {
        assertNull(MemoryExtractionParser.parse("我不知道该记什么"));
        assertNull(MemoryExtractionParser.parse(null));
    }

    @Test
    public void parse_malformedJsonReturnsNull() {
        assertNull(MemoryExtractionParser.parse("{\"new_memories\": [ oops }"));
    }

    @Test
    public void parse_missingFieldsReturnEmptyResult() {
        Result r = MemoryExtractionParser.parse("{}");
        assertNotNull(r);
        assertTrue(r.memories.isEmpty());
        assertTrue(r.forgotten.isEmpty());
    }

    // ==================== 分类归一 ====================

    @Test
    public void normalizeCategory_mapsModelWording() {
        assertEquals("profile", MemoryExtractionParser.normalizeCategory("personality"));
        assertEquals("profile", MemoryExtractionParser.normalizeCategory("性格特征"));
        assertEquals("preference", MemoryExtractionParser.normalizeCategory("偏好"));
        assertEquals("habit", MemoryExtractionParser.normalizeCategory("habit"));
        assertEquals("important_date", MemoryExtractionParser.normalizeCategory("纪念日"));
        assertEquals("relationship", MemoryExtractionParser.normalizeCategory("relationship"));
        assertEquals("event", MemoryExtractionParser.normalizeCategory("event"));
        assertEquals("fact", MemoryExtractionParser.normalizeCategory("随便写"));
        assertEquals("fact", MemoryExtractionParser.normalizeCategory(null));
    }

    @Test
    public void parse_normalizesCategoryInsteadOfPassingThrough() {
        String raw = "{\"new_memories\":[{\"content\":\"喜欢跑步\",\"category\":\"personality\","
                + "\"weight\":0.5}]}";
        Result r = MemoryExtractionParser.parse(raw);
        assertNotNull(r);
        assertEquals("profile", r.memories.get(0).category);
    }

    // ==================== weight 夹取 ====================

    @Test
    public void parse_clampsWeightIntoUnitRange() {
        String raw = "{\"new_memories\":["
                + "{\"content\":\"a\",\"category\":\"fact\",\"weight\":-3},"
                + "{\"content\":\"b\",\"category\":\"fact\",\"weight\":9},"
                + "{\"content\":\"c\",\"category\":\"fact\",\"weight\":0.42}]}";
        Result r = MemoryExtractionParser.parse(raw);
        assertNotNull(r);
        assertEquals(3, r.memories.size());
        assertEquals(0.0, r.memories.get(0).weight, 1e-9);
        assertEquals(1.0, r.memories.get(1).weight, 1e-9);
        assertEquals(0.42, r.memories.get(2).weight, 1e-9);
    }

    @Test
    public void parse_defaultsWeightWhenAbsent() {
        String raw = "{\"new_memories\":[{\"content\":\"没写权重\",\"category\":\"fact\"}]}";
        Result r = MemoryExtractionParser.parse(raw);
        assertNotNull(r);
        assertEquals(0.6, r.memories.get(0).weight, 1e-9);
    }

    // ==================== 条数与长度上限 ====================

    @Test
    public void parse_capsNewMemoriesAtMax() {
        StringBuilder sb = new StringBuilder("{\"new_memories\":[");
        for (int i = 0; i < 8; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"content\":\"m").append(i).append("\",\"category\":\"fact\",\"weight\":0.5}");
        }
        sb.append("]}");
        Result r = MemoryExtractionParser.parse(sb.toString());
        assertNotNull(r);
        assertEquals(MemoryExtractionParser.MAX_NEW, r.memories.size());
        assertEquals("m0", r.memories.get(0).content);
        assertEquals("m4", r.memories.get(4).content);
    }

    @Test
    public void parse_truncatesOverlongContent() {
        StringBuilder longS = new StringBuilder();
        for (int i = 0; i < 200; i++) longS.append('x');
        String raw = "{\"new_memories\":[{\"content\":\"" + longS
                + "\",\"category\":\"fact\",\"weight\":0.5}]}";
        Result r = MemoryExtractionParser.parse(raw);
        assertNotNull(r);
        assertEquals(MemoryExtractionParser.MAX_CONTENT_CHARS, r.memories.get(0).content.length());
    }

    @Test
    public void parse_skipsEmptyContentAndBlankForgotten() {
        String raw = "{\"new_memories\":["
                + "{\"content\":\"   \",\"category\":\"fact\",\"weight\":0.5},"
                + "{\"content\":\"有效\",\"category\":\"fact\",\"weight\":0.5}],"
                + "\"forgotten\":[\"\",\"  \",\"旧事\"]}";
        Result r = MemoryExtractionParser.parse(raw);
        assertNotNull(r);
        assertEquals(1, r.memories.size());
        assertEquals("有效", r.memories.get(0).content);
        assertEquals(1, r.forgotten.size());
        assertEquals("旧事", r.forgotten.get(0));
    }

    @Test
    public void parse_capsForgottenAtMax() {
        String raw = "{\"forgotten\":[\"a\",\"b\",\"c\",\"d\",\"e\"]}";
        Result r = MemoryExtractionParser.parse(raw);
        assertNotNull(r);
        assertEquals(MemoryExtractionParser.MAX_FORGET, r.forgotten.size());
        assertEquals("a", r.forgotten.get(0));
        assertEquals("c", r.forgotten.get(2));
    }

    @Test
    public void parse_skipsNonObjectEntries() {
        String raw = "{\"new_memories\":[\"字符串项\",{\"content\":\"有效\","
                + "\"category\":\"fact\",\"weight\":0.5}]}";
        Result r = MemoryExtractionParser.parse(raw);
        assertNotNull(r);
        assertEquals(1, r.memories.size());
        assertEquals("有效", r.memories.get(0).content);
    }

    @Test
    public void newMemory_isImmutableValue() {
        NewMemory m = new NewMemory("c", "fact", 0.5);
        assertEquals("c", m.content);
        assertEquals("fact", m.category);
        assertEquals(0.5, m.weight, 1e-9);
    }
}
