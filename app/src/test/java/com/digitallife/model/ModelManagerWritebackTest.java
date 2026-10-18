package com.digitallife.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * ModelManager 动作写回单测（v1.166.0）。
 *
 * <p>覆盖动作池新增的三项写回：把「未注册文件」注册进组、从组中移除注册、重命名动作组。
 * 三者都改模型 json，且要落到与扫描层（MotionLibrary）**同一处**（model3 → FileReferences.Motions，
 * model.json → 根级 Motions），否则界面里「看到的组」和「改到的组」会对不上——这里把这条锁住。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ModelManagerWritebackTest {

    private Context ctx() {
        return RuntimeEnvironment.getApplication();
    }

    private File modelsRoot() {
        return new File(ctx().getFilesDir(), "models");
    }

    private File modelDir(String name) {
        return new File(modelsRoot(), name);
    }

    private void write(File f, String content) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /** 造一个 model3 模型：FileReferences.Motions 里一个组含一个动作 */
    private File writeModel3(String name, String group, String motionFile) throws Exception {
        JSONObject item = new JSONObject().put("File", motionFile);
        JSONObject motions = new JSONObject().put(group, new JSONArray().put(item));
        JSONObject fr = new JSONObject().put("Motions", motions);
        JSONObject root = new JSONObject().put("Version", 3).put("FileReferences", fr);
        File json = new File(modelDir(name), name + ".model3.json");
        write(json, root.toString(2));
        write(new File(modelDir(name), motionFile), "{\"Version\":3}");
        return json;
    }

    private JSONObject readJson(File f) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
    }

    private JSONObject motionsOf(File json) throws Exception {
        JSONObject root = readJson(json);
        return root.getJSONObject("FileReferences").getJSONObject("Motions");
    }

    // ── registerExistingFileIntoModel ───────────────────────────

    @Test
    public void registerExisting_addsToGroup() throws Exception {
        File json = writeModel3("mdl", "Idle", "idle.motion3.json");
        write(new File(modelDir("mdl"), "orphan.motion3.json"), "{\"Version\":3}");

        String msg = ModelManager.registerExistingFileIntoModel(
                ctx(), "mdl", "orphan.motion3.json", "TapBody");
        assertTrue(msg, msg.contains("注册"));

        JSONArray arr = motionsOf(json).getJSONArray("TapBody");
        assertEquals(1, arr.length());
        assertEquals("orphan.motion3.json", arr.getJSONObject(0).getString("File"));
    }

    @Test
    public void registerExisting_dedupesSameFile() throws Exception {
        File json = writeModel3("mdl2", "Idle", "idle.motion3.json");
        String msg = ModelManager.registerExistingFileIntoModel(
                ctx(), "mdl2", "idle.motion3.json", "Idle");
        assertTrue(msg, msg.contains("已在"));
        // 仍是 1 条，没有重复插入
        assertEquals(1, motionsOf(json).getJSONArray("Idle").length());
    }

    @Test
    public void registerExisting_rejectsBuiltin() {
        String msg = ModelManager.registerExistingFileIntoModel(
                ctx(), "huohuo", "x.motion3.json", "Idle");
        assertTrue(msg, msg.contains("只读") || msg.contains("不可写"));
    }

    @Test
    public void registerExisting_intoModelJson_usesRootMotions() throws Exception {
        // .model.json（非 model3）：没有 Motions 时应落到根级 Motions，与扫描层一致
        File json = new File(modelDir("mdl5"), "mdl5.model.json");
        write(json, new JSONObject().put("Version", 2).toString());
        write(new File(modelDir("mdl5"), "b.motion3.json"), "{\"Version\":3}");

        String msg = ModelManager.registerExistingFileIntoModel(
                ctx(), "mdl5", "b.motion3.json", "Idle");
        assertTrue(msg, msg.contains("注册"));

        JSONObject root = readJson(json);
        assertTrue(root.has("Motions"));
        assertEquals(1, root.getJSONObject("Motions").getJSONArray("Idle").length());
        assertFalse(root.has("FileReferences"));
    }

    // ── removeMotionFromModel ───────────────────────────────────

    @Test
    public void remove_unregistersButKeepsFile() throws Exception {
        File json = writeModel3("mdl3", "Idle", "idle.motion3.json");
        String msg = ModelManager.removeMotionFromModel(ctx(), "mdl3", "Idle", "idle.motion3.json");
        assertTrue(msg, msg.contains("移除"));

        // 组因此变空 → 整个组被删
        assertFalse(motionsOf(json).has("Idle"));
        // 动作文件保留（变为未注册文件）
        assertTrue(new File(modelDir("mdl3"), "idle.motion3.json").exists());
    }

    @Test
    public void remove_keepsOtherMotionsInGroup() throws Exception {
        JSONObject i1 = new JSONObject().put("File", "a.motion3.json");
        JSONObject i2 = new JSONObject().put("File", "b.motion3.json");
        JSONObject motions = new JSONObject().put("Idle", new JSONArray().put(i1).put(i2));
        JSONObject root = new JSONObject().put("FileReferences",
                new JSONObject().put("Motions", motions));
        File json = new File(modelDir("mdl6"), "mdl6.model3.json");
        write(json, root.toString(2));
        write(new File(modelDir("mdl6"), "a.motion3.json"), "{}");
        write(new File(modelDir("mdl6"), "b.motion3.json"), "{}");

        String msg = ModelManager.removeMotionFromModel(ctx(), "mdl6", "Idle", "a.motion3.json");
        assertTrue(msg, msg.contains("移除"));
        JSONArray arr = motionsOf(json).getJSONArray("Idle");
        assertEquals(1, arr.length());
        assertEquals("b.motion3.json", arr.getJSONObject(0).getString("File"));
    }

    @Test
    public void remove_notPresent_reportsNoChange() throws Exception {
        File json = writeModel3("mdl7", "Idle", "idle.motion3.json");
        String msg = ModelManager.removeMotionFromModel(ctx(), "mdl7", "Idle", "nope.motion3.json");
        assertTrue(msg, msg.contains("不在"));
        assertEquals(1, motionsOf(json).getJSONArray("Idle").length());
    }

    // ── renameMotionGroup ───────────────────────────────────────

    @Test
    public void rename_movesKey() throws Exception {
        File json = writeModel3("mdl4", "Idle", "idle.motion3.json");
        String msg = ModelManager.renameMotionGroup(ctx(), "mdl4", "Idle", "Wait");
        assertTrue(msg, msg.contains("重命名"));

        JSONObject motions = motionsOf(json);
        assertFalse(motions.has("Idle"));
        assertEquals(1, motions.getJSONArray("Wait").length());
    }

    @Test
    public void rename_intoExistingGroup_merges() throws Exception {
        JSONObject idle = new JSONObject().put("Idle",
                new JSONArray().put(new JSONObject().put("File", "i.motion3.json")));
        JSONObject wait = new JSONObject().put("Wait",
                new JSONArray().put(new JSONObject().put("File", "w.motion3.json")));
        JSONObject all = new JSONObject();
        all.put("Idle", idle.getJSONArray("Idle"));
        all.put("Wait", wait.getJSONArray("Wait"));
        File json = new File(modelDir("mdl8"), "mdl8.model3.json");
        write(json, new JSONObject().put("FileReferences",
                new JSONObject().put("Motions", all)).toString(2));

        String msg = ModelManager.renameMotionGroup(ctx(), "mdl8", "Idle", "Wait");
        assertTrue(msg, msg.contains("并入"));
        JSONObject motions = motionsOf(json);
        assertFalse(motions.has("Idle"));
        assertEquals(2, motions.getJSONArray("Wait").length());
    }

    @Test
    public void rename_rejectsSameName() throws Exception {
        writeModel3("mdl9", "Idle", "idle.motion3.json");
        String msg = ModelManager.renameMotionGroup(ctx(), "mdl9", "Idle", "Idle");
        assertTrue(msg, msg.contains("相同"));
    }
}
