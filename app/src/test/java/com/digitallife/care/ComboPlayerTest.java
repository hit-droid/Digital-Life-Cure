package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.digitallife.service.PetService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * ComboPlayer 单测（v1.178.0）。
 *
 * <p>连招播放被抽成可复用组件后，界面 / 开机 / 定时三处共用。这里锁住与 Android / 桌宠无关的
 * 纯边界：①扫描目录不存在 → 候选为空；②桌宠没运行时播放不崩、回落 {@code -1}；③{@code flatten}
 * 容 {@code null}。真正的逐步调度依赖 {@code PetService}，由集成 / 手工验证覆盖。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ComboPlayerTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    @Before
    public void reset() {
        ctx().getSharedPreferences("care_combos", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void flatten_nullIsEmpty() {
        assertTrue(ComboPlayer.flatten(null).isEmpty());
    }

    @Test
    public void candidatesFor_blankOrUnknownModelIsEmpty() {
        assertTrue(ComboPlayer.candidatesFor(ctx(), "nope").isEmpty());
        assertTrue(ComboPlayer.candidatesFor(ctx(), "").isEmpty());
        assertTrue(ComboPlayer.candidatesFor(ctx(), null).isEmpty());
    }

    @Test
    public void play_withoutPetService_returnsMinusOne() {
        ComboPlan.Combo c = new ComboPlan.Combo("打招呼");
        c.steps.add(new ComboPlan.Step(ComboPlan.KIND_MOTION, "Idle", "idle.motion3.json", null));
        ComboStore.save(ctx(), "demo", c);

        ComboPlayer player = new ComboPlayer(ctx(), null);
        assertFalse(player.isPlaying());
        assertEquals(-1, player.play("demo", "打招呼"));
    }

    @Test
    public void playSteps_withoutService_returnsMinusOne() {
        // 单测环境没有运行中的桌宠（PetService.getInstance() == null），播放应静默回落 -1
        assertEquals(null, PetService.getInstance());
        ComboPlayer player = new ComboPlayer(ctx(), null);
        assertEquals(-1, player.playSteps(PetService.getInstance(), "x", 1, null));
        assertFalse(player.isPlaying());
    }

    @Test
    public void cancel_isSafeWhenIdle() {
        ComboPlayer player = new ComboPlayer(ctx(), null);
        player.cancel();
        assertFalse(player.isPlaying());
    }
}
