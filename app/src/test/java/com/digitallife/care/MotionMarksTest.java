package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 动作池标记纯逻辑单测（v1.175.0）。锁三件事：①身份键按「模型 + 类型 + 组 / 文件 / 名」区分；
 * ②「最近播放」置顶、同名去重、按上限裁剪；③收藏的切换语义（不存在则插到最前、存在则移除）
 * 与「按身份键移除」（清失效条目）。不碰存储。
 */
public class MotionMarksTest {

    private static MotionMarks.Ref motion(String dir, String group, String file) {
        return new MotionMarks.Ref(dir, true, group, file, null);
    }

    private static MotionMarks.Ref expr(String dir, String name, String file) {
        return new MotionMarks.Ref(dir, false, null, file, name);
    }

    @Test
    public void id_distinguishesByKindAndFields() {
        assertEquals(motion("d", "Idle", "a.motion3.json").id(),
                motion("d", "Idle", "a.motion3.json").id());
        assertFalse(motion("d", "Idle", "a.motion3.json").id()
                .equals(motion("d", "TapBody", "a.motion3.json").id()));
        assertFalse(motion("d", "Idle", "a.motion3.json").id()
                .equals(motion("d", "Idle", "b.motion3.json").id()));
        // 类型位不同：动作与表情即便字段巧合也不相等
        assertFalse(motion("d", null, "x").id().equals(expr("d", "x", "x").id()));
    }

    @Test
    public void contains_matchesById() {
        List<MotionMarks.Ref> list = Arrays.asList(motion("d", "Idle", "a.motion3.json"));
        assertTrue(MotionMarks.contains(list, motion("d", "Idle", "a.motion3.json")));
        assertFalse(MotionMarks.contains(list, motion("d", "Idle", "b.motion3.json")));
        assertFalse(MotionMarks.contains(null, motion("d", "Idle", "a.motion3.json")));
    }

    @Test
    public void pushed_putsFrontDedupesAndCaps() {
        MotionMarks.Ref a = motion("d", "Idle", "a.motion3.json");
        MotionMarks.Ref b = motion("d", "Idle", "b.motion3.json");
        MotionMarks.Ref c = motion("d", "Idle", "c.motion3.json");

        List<MotionMarks.Ref> out = MotionMarks.pushed(new ArrayList<>(Arrays.asList(a, b)), c, 10);
        assertEquals(c.id(), out.get(0).id());
        assertEquals(3, out.size());

        // 重新播放已在列的 b → 提到最前、仍只一条
        out = MotionMarks.pushed(out, b, 10);
        assertEquals(b.id(), out.get(0).id());
        assertEquals(3, out.size());

        // 封顶：cap=2 时砍尾（新的 a 在最前）
        out = MotionMarks.pushed(out, a, 2);
        assertEquals(2, out.size());
        assertEquals(a.id(), out.get(0).id());

        // null 列表 / null ref 不抛
        assertEquals(1, MotionMarks.pushed(null, a, 10).size());
        assertTrue(MotionMarks.pushed(null, null, 10).isEmpty());
    }

    @Test
    public void toggled_addsFrontThenRemoves() {
        MotionMarks.Ref a = motion("d", "Idle", "a.motion3.json");
        MotionMarks.Ref b = motion("d", "Idle", "b.motion3.json");

        List<MotionMarks.Ref> add = MotionMarks.toggled(new ArrayList<>(Arrays.asList(a)), b);
        assertEquals(2, add.size());
        assertEquals(b.id(), add.get(0).id());

        List<MotionMarks.Ref> rm = MotionMarks.toggled(add, a);
        assertEquals(1, rm.size());
        assertEquals(b.id(), rm.get(0).id());
        assertFalse(MotionMarks.contains(rm, a));
    }

    @Test
    public void removed_dropsByIdAndIsNullSafe() {
        MotionMarks.Ref a = motion("d", "Idle", "a.motion3.json");
        MotionMarks.Ref b = expr("d", "F01", "f01.exp3.json");
        List<MotionMarks.Ref> list = new ArrayList<>(Arrays.asList(a, b));

        List<MotionMarks.Ref> out = MotionMarks.removed(list, a);
        assertEquals(1, out.size());
        assertEquals(b.id(), out.get(0).id());
        // 不存在时原样
        assertEquals(1, MotionMarks.removed(out, a).size());
        // null 列表 / null ref 不抛
        assertTrue(MotionMarks.removed(null, a).isEmpty());
        assertEquals(2, MotionMarks.removed(list, null).size());
    }
}
