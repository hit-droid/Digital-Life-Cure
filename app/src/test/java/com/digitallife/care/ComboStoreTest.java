package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * 连招持久化单测（v1.172.0）。
 *
 * <p>锁四件事：①保存后可读回且步骤（动作 group+file / 表情 name）不丢；②同名忽略大小写即覆盖
 * （不叠成两条）；③不同模型互不干扰（key = 模型目录名）；④坏数据（缺名条目）读取时跳过、不抛。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ComboStoreTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void reset() {
        ctx().getSharedPreferences("care_combos", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private ComboPlan.Combo combo(String name, String... pairs) {
        ComboPlan.Combo c = new ComboPlan.Combo(name);
        for (String p : pairs) c.steps.add(new ComboPlan.Step(
                p.startsWith("m:") ? ComboPlan.KIND_MOTION : ComboPlan.KIND_EXPR,
                p.startsWith("m:") ? "Idle" : null,
                p.substring(2),
                p.startsWith("m:") ? null : "F01"));
        return c;
    }

    @Test
    public void saveThenList_roundTripsSteps() {
        ComboStore.save(ctx(), "demo", combo("打招呼", "m:idle.motion3.json", "e:f01.exp3.json"));

        List<ComboPlan.Combo> list = ComboStore.list(ctx(), "demo");
        assertEquals(1, list.size());
        ComboPlan.Combo c = list.get(0);
        assertEquals("打招呼", c.name);
        assertEquals(2, c.size());
        assertTrue(c.steps.get(0).isMotion());
        assertEquals("Idle", c.steps.get(0).group);
        assertEquals("idle.motion3.json", c.steps.get(0).file);
        assertEquals("F01", c.steps.get(1).name);
    }

    @Test
    public void save_sameNameOverwritesCaseInsensitively() {
        ComboStore.save(ctx(), "demo", combo("Hi", "m:idle.motion3.json"));
        ComboStore.save(ctx(), "demo", combo("hi", "m:tap.motion3.json"));

        List<ComboPlan.Combo> list = ComboStore.list(ctx(), "demo");
        assertEquals(1, list.size());
        assertEquals("tap.motion3.json", list.get(0).steps.get(0).file);
    }

    @Test
    public void delete_removesOnlyThatCombo() {
        ComboStore.save(ctx(), "demo", combo("A", "m:idle.motion3.json"));
        ComboStore.save(ctx(), "demo", combo("B", "m:tap.motion3.json"));

        ComboStore.delete(ctx(), "demo", "A");
        List<ComboPlan.Combo> list = ComboStore.list(ctx(), "demo");
        assertEquals(1, list.size());
        assertEquals("B", list.get(0).name);
    }

    @Test
    public void models_areIsolated() {
        ComboStore.save(ctx(), "m1", combo("A", "m:idle.motion3.json"));
        ComboStore.save(ctx(), "m2", combo("B", "m:tap.motion3.json"));

        assertEquals(1, ComboStore.list(ctx(), "m1").size());
        assertEquals("A", ComboStore.list(ctx(), "m1").get(0).name);
        assertEquals(1, ComboStore.list(ctx(), "m2").size());
        assertEquals("B", ComboStore.list(ctx(), "m2").get(0).name);
    }

    @Test
    public void list_unknownModelIsEmpty() {
        assertTrue(ComboStore.list(ctx(), "nope").isEmpty());
        assertTrue(ComboStore.list(ctx(), null).isEmpty());
    }

    // ==================== 按名查找（v1.178.0，供 ComboPlayer / 自动化复用） ====================

    @Test
    public void find_matchesCaseInsensitively() {
        ComboStore.save(ctx(), "demo", combo("Hi", "m:idle.motion3.json"));
        ComboPlan.Combo c = ComboStore.find(ctx(), "demo", "hi");
        assertEquals("Hi", c.name);
        assertEquals("idle.motion3.json", c.steps.get(0).file);
    }

    @Test
    public void find_missingOrBlankReturnsNull() {
        ComboStore.save(ctx(), "demo", combo("A", "m:idle.motion3.json"));
        assertEquals(null, ComboStore.find(ctx(), "demo", "B"));
        assertEquals(null, ComboStore.find(ctx(), "demo", ""));
        assertEquals(null, ComboStore.find(ctx(), "demo", null));
        assertEquals(null, ComboStore.find(ctx(), "nope", "A"));
    }

    @Test
    public void fromJson_skipsNamelessEntries() throws Exception {
        JSONObject good = new JSONObject().put("name", "ok")
                .put("steps", new JSONArray().put(new JSONObject()
                        .put("kind", ComboPlan.KIND_EXPR).put("name", "F01")));
        JSONObject bad = new JSONObject().put("steps", new JSONArray());
        JSONArray arr = new JSONArray().put(bad).put(good);

        List<ComboPlan.Combo> list = ComboStore.fromJson(arr);
        assertEquals(1, list.size());
        assertEquals("ok", list.get(0).name);
    }

    // ==================== 导出 / 导入（v1.177.0） ====================

    @Test
    public void bundle_roundTripsThroughParseImport() throws Exception {
        List<ComboPlan.Combo> out = new java.util.ArrayList<>();
        out.add(combo("打招呼", "m:idle.motion3.json", "e:f01.exp3.json"));
        out.add(combo("睡觉", "m:sleep.motion3.json"));

        String json = ComboStore.toBundle(out, "demo");
        JSONObject bundle = new JSONObject(json);
        assertEquals("demo", bundle.getString("model"));
        assertEquals(2, bundle.getJSONArray("combos").length());

        List<ComboPlan.Combo> back = ComboStore.parseImport(json);
        assertEquals(2, back.size());
        assertEquals("打招呼", back.get(0).name);
        assertEquals(2, back.get(0).size());
        assertEquals("Idle", back.get(0).steps.get(0).group);
        assertEquals("F01", back.get(0).steps.get(1).name);
        assertEquals("睡觉", back.get(1).name);
        assertEquals("sleep.motion3.json", back.get(1).steps.get(0).file);
    }

    @Test
    public void parseImport_acceptsBareArray() {
        JSONArray arr = new JSONArray();
        arr.put(new JSONObject());  // 无名，读回时跳过
        List<ComboPlan.Combo> list = ComboStore.parseImport(arr.toString());
        assertTrue(list.isEmpty());

        String okArray = ComboStore.toJson(java.util.Collections.singletonList(
                combo("A", "m:idle.motion3.json"))).toString();
        List<ComboPlan.Combo> ok = ComboStore.parseImport(okArray);
        assertEquals(1, ok.size());
        assertEquals("A", ok.get(0).name);
    }

    @Test
    public void parseImport_isTolerantOfGarbage() {
        assertTrue(ComboStore.parseImport(null).isEmpty());
        assertTrue(ComboStore.parseImport("").isEmpty());
        assertTrue(ComboStore.parseImport("   ").isEmpty());
        assertTrue(ComboStore.parseImport("not json").isEmpty());
        assertTrue(ComboStore.parseImport("{\"x\":1}").isEmpty());
        assertTrue(ComboStore.parseImport("[1,2,3]").isEmpty());
    }
}
