package com.digitallife.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1.180.0「Agent 工具补齐」的契约：{@code get_calendar_today}（今日日程）与
 * {@code get_files_recent}（最近文件）两处纯逻辑的边界。
 *
 * <p>锁死：日期/大小格式化、排序与截断、隐藏文件与噪音目录排除、坏 JSON 不崩、
 * 以及带 Context 的真实入口不抛异常。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class InfoToolsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    // ==================== RecentFiles ====================

    @Test
    public void clampMax_fallsBackToDefaultAndCapsUpperBound() {
        assertEquals(RecentFiles.DEFAULT_MAX, RecentFiles.clampMax(0));
        assertEquals(RecentFiles.DEFAULT_MAX, RecentFiles.clampMax(-5));
        assertEquals(5, RecentFiles.clampMax(5));
        assertEquals(RecentFiles.MAX_MAX, RecentFiles.clampMax(999));
    }

    @Test
    public void humanSize_rendersReadableUnits() {
        assertEquals("0B", RecentFiles.humanSize(0));
        assertEquals("512B", RecentFiles.humanSize(512));
        assertEquals("1.0KB", RecentFiles.humanSize(1024));
        assertEquals("1.5KB", RecentFiles.humanSize(1536));
        assertEquals("1.0MB", RecentFiles.humanSize(1024 * 1024));
        assertEquals("1.0GB", RecentFiles.humanSize(1024L * 1024L * 1024L));
    }

    @Test
    public void collect_sortsNewestFirstAndRespectsCap() throws Exception {
        File root = tmp.newFolder("recent");
        File older = write(root, "older.txt", "aaa");
        File newer = write(root, "newer.txt", "bbbb");
        assertTrue("需要能设置修改时间", older.setLastModified(1_000_000_000_000L));
        assertTrue("需要能设置修改时间", newer.setLastModified(1_700_000_000_000L));

        List<File> all = RecentFiles.collect(java.util.Collections.singletonList(root), 500);
        assertEquals(2, all.size());
        assertEquals("最新修改的应排在最前", "newer.txt", all.get(0).getName());

        List<File> capped = RecentFiles.collect(java.util.Collections.singletonList(root), 1);
        assertEquals("cap 应截断结果", 1, capped.size());
        assertEquals("newer.txt", capped.get(0).getName());
    }

    @Test
    public void collect_skipsHiddenZeroByteAndNoiseDirs() throws Exception {
        File root = tmp.newFolder("noise");
        write(root, ".hidden.txt", "x");           // 隐藏文件
        write(root, "cover.nomedia", "x");         // 占位标记
        write(root, "empty.txt", "");              // 0 字节
        write(root, "real.txt", "hello");          // 唯一应保留

        File cache = new File(root, "cache");
        assertTrue(cache.mkdirs());
        write(cache, "tmp.bin", "cccc");
        File models = new File(root, "models");
        assertTrue(models.mkdirs());
        write(models, "moc3.bin", "dddddd");

        List<File> all = RecentFiles.collect(java.util.Collections.singletonList(root), 500);
        assertEquals("只应留下 real.txt", 1, all.size());
        assertEquals("real.txt", all.get(0).getName());
    }

    @Test
    public void render_handlesEmptyListReadably() {
        assertTrue(RecentFiles.render(null, 10).contains("未找到"));
        assertTrue(RecentFiles.render(java.util.Collections.emptyList(), 10).contains("未找到"));
    }

    @Test
    public void render_includesNameSizeTimeAndPath() throws Exception {
        File root = tmp.newFolder("render");
        File f = write(root, "note.txt", "hello world");
        List<File> files = RecentFiles.collect(java.util.Collections.singletonList(root), 500);
        String text = RecentFiles.render(files, 10);
        assertTrue(text.contains("note.txt"));
        assertTrue(text.contains("11B"));
        assertTrue(text.contains(f.getAbsolutePath()));
    }

    @Test
    public void recent_withContextNeverThrows() {
        assertNotNull(RecentFiles.recent(ctx));
        assertNotNull(RecentFiles.recent(null));
        assertTrue(RecentFiles.recent(null).contains("上下文为空"));
    }

    // ==================== Agenda ====================

    @Test
    public void formatRoutines_rendersWorkflowComboPausedAndSkipsGarbage() throws Exception {
        Map<String, String> all = new LinkedHashMap<>();
        all.put("晨间简报", "{\"cronExpr\":\"0 9 * * *\",\"workflowName\":\"晨间简报\"}");
        all.put("打招呼", "{\"cronExpr\":\"*/30 * * * *\",\"kind\":\"combo\",\"comboName\":\"挥手\"}");
        all.put("巡检", "{\"cronExpr\":\"0 * * * *\",\"workflowName\":\"巡检\",\"enabled\":false}");
        all.put("坏数据", "{这不是 JSON");
        all.put("非字符串", null);

        List<String> lines = Agenda.formatRoutines(all, System.currentTimeMillis());
        assertEquals("坏数据/空值应跳过", 3, lines.size());
        String joined = String.join("\n", lines);
        assertTrue(joined, joined.contains("每天 09:00"));
        assertTrue(joined, joined.contains("工作流 晨间简报"));
        assertTrue(joined, joined.contains("连招 挥手"));
        assertTrue(joined, joined.contains("（已暂停）"));
        // 按任务名排序：巡检 / 打招呼 / 晨间简报
        assertTrue(joined, lines.get(0).startsWith("巡检"));
    }

    @Test
    public void formatRoutines_emptyOrNullYieldsNothing() {
        assertTrue(Agenda.formatRoutines(null, 0).isEmpty());
        assertTrue(Agenda.formatRoutines(new LinkedHashMap<>(), 0).isEmpty());
    }

    @Test
    public void routines_emptyStoreReadsAsPlaceholder() {
        ctx.getSharedPreferences(Agenda.PREFS_SCHEDULE, Context.MODE_PRIVATE)
                .edit().clear().commit();
        assertTrue(Agenda.routines(ctx, System.currentTimeMillis()).contains("暂无"));
    }

    @Test
    public void today_withSeededStoresNeverThrowsAndMentionsBothSections() {
        ctx.getSharedPreferences(Agenda.PREFS_SCHEDULE, Context.MODE_PRIVATE).edit()
                .putString("晨间简报",
                        "{\"cronExpr\":\"0 9 * * *\",\"workflowName\":\"晨间简报\"}")
                .commit();
        long soon = System.currentTimeMillis() + 600_000L;
        ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit()
                .putString("reminder_test", "喝水|" + soon)
                .commit();

        String text = Agenda.today(ctx);
        assertNotNull(text);
        assertTrue(text.contains("今日日程"));
        assertTrue(text.contains("每日例行"));
        assertTrue(text, text.contains("晨间简报"));
        assertTrue("应带出今日提醒", text.contains("喝水"));

        // null 上下文不崩
        assertTrue(Agenda.today(null).contains("上下文为空"));
    }

    private static File write(File dir, String name, String content) throws Exception {
        File f = new File(dir, name);
        if (content.isEmpty()) {
            assertTrue(f.createNewFile());
            return f;
        }
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(content.getBytes("UTF-8"));
        } finally {
            out.close();
        }
        return f;
    }
}
