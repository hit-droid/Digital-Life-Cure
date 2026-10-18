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
}
