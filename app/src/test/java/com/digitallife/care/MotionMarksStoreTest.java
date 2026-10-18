package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * 动作池标记持久化单测（v1.175.0）。锁「收藏切换落盘」「最近播放最新在前 + 同名去重 + 封顶」
 * 「按身份键移除」「清空只清最近」「JSON 往返保留两种类型」「坏数据跳过」几个契约。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class MotionMarksStoreTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void reset() {
        ctx().getSharedPreferences("care_motion_marks", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static MotionMarks.Ref motion(String file) {
        return new MotionMarks.Ref("demo", true, "Idle", file, null);
    }

    private static MotionMarks.Ref expr(String name, String file) {
        return new MotionMarks.Ref("demo", false, null, file, name);
    }

    @Test
    public void toggleFavorite_addsThenRemoves() {
        MotionMarks.Ref a = motion("a.motion3.json");
        assertFalse(MotionMarks.isFavorite(ctx(), a));
        MotionMarks.toggleFavorite(ctx(), a);
        assertTrue(MotionMarks.isFavorite(ctx(), a));
        assertEquals(1, MotionMarks.favorites(ctx()).size());
        MotionMarks.toggleFavorite(ctx(), a);
        assertFalse(MotionMarks.isFavorite(ctx(), a));
        assertTrue(MotionMarks.favorites(ctx()).isEmpty());
    }

    @Test
    public void pushRecent_latestFirstAndCaps() {
        for (int i = 0; i < MotionMarks.MAX_RECENT + 3; i++) {
            MotionMarks.pushRecent(ctx(), motion("m" + i + ".motion3.json"));
        }
        List<MotionMarks.Ref> recents = MotionMarks.recents(ctx());
        assertEquals(MotionMarks.MAX_RECENT, recents.size());
        // 最新在前：最后写入的 m(MAX+2) 排首位
        assertEquals("m" + (MotionMarks.MAX_RECENT + 2) + ".motion3.json", recents.get(0).file);
    }

    @Test
    public void pushRecent_sameRefMovesToFrontWithoutDup() {
        MotionMarks.Ref a = motion("a.motion3.json");
        MotionMarks.pushRecent(ctx(), a);
        MotionMarks.pushRecent(ctx(), motion("b.motion3.json"));
        MotionMarks.pushRecent(ctx(), a);
        List<MotionMarks.Ref> recents = MotionMarks.recents(ctx());
        assertEquals(2, recents.size());
        assertEquals("a.motion3.json", recents.get(0).file);
    }

    @Test
    public void removeRecent_dropsOnlyThatRef() {
        MotionMarks.Ref a = expr("F01", "f01.exp3.json");
        MotionMarks.pushRecent(ctx(), motion("a.motion3.json"));
        MotionMarks.pushRecent(ctx(), a);
        MotionMarks.removeRecent(ctx(), a);
        List<MotionMarks.Ref> recents = MotionMarks.recents(ctx());
        assertEquals(1, recents.size());
        assertTrue(recents.get(0).motion);
    }

    @Test
    public void clearRecents_emptiesOnlyRecents() {
        MotionMarks.toggleFavorite(ctx(), motion("a.motion3.json"));
        MotionMarks.pushRecent(ctx(), motion("b.motion3.json"));
        MotionMarks.clearRecents(ctx());
        assertTrue(MotionMarks.recents(ctx()).isEmpty());
        assertEquals(1, MotionMarks.favorites(ctx()).size());
    }

    @Test
    public void jsonRoundTrip_keepsBothKinds() {
        MotionMarks.toggleFavorite(ctx(), motion("idle.motion3.json"));
        MotionMarks.toggleFavorite(ctx(), expr("F01", "f01.exp3.json"));
        List<MotionMarks.Ref> favs = MotionMarks.favorites(ctx());
        assertEquals(2, favs.size());
        // 表情后加入故在前
        assertFalse(favs.get(0).motion);
        assertEquals("F01", favs.get(0).name);
        assertTrue(favs.get(1).motion);
        assertEquals("Idle", favs.get(1).group);
        assertEquals("demo", favs.get(1).modelDir);
    }

    @Test
    public void fromJson_skipsEntriesWithoutModelDir() throws Exception {
        JSONArray arr = new JSONArray()
                .put(new JSONObject().put("motion", true))               // 缺 modelDir → 跳过
                .put(new JSONObject().put("modelDir", "demo").put("motion", true)
                        .put("group", "Idle").put("file", "idle.motion3.json"));
        List<MotionMarks.Ref> out = MotionMarks.fromJson(arr);
        assertEquals(1, out.size());
        assertEquals("demo", out.get(0).modelDir);
        assertEquals("Idle", out.get(0).group);
    }
}
