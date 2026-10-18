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

    // ── 表情写回（v1.169.0）─────────────────────────────────────

    /** 造一个 model3 模型：FileReferences.Expressions 里一条声明 {Name, File} */
    private File writeModel3WithExpr(String name, String exprName, String exprFile) throws Exception {
        JSONObject item = new JSONObject().put("Name", exprName).put("File", exprFile);
        JSONObject fr = new JSONObject().put("Expressions", new JSONArray().put(item));
        JSONObject root = new JSONObject().put("Version", 3).put("FileReferences", fr);
        File json = new File(modelDir(name), name + ".model3.json");
        write(json, root.toString(2));
        write(new File(modelDir(name), exprFile), "{\"Version\":3}");
        return json;
    }

    private JSONArray expressionsOf(File json) throws Exception {
        return readJson(json).getJSONObject("FileReferences").getJSONArray("Expressions");
    }

    @Test
    public void registerExpressionIntoModel_writesFileAndRegisters() throws Exception {
        // model3 已有 FileReferences（无 Expressions）→ 新建落 FileReferences.Expressions
        File json = new File(modelDir("ex1"), "ex1.model3.json");
        write(json, new JSONObject().put("Version", 3)
                .put("FileReferences", new JSONObject()).toString(2));

        byte[] data = "{\"Version\":3}".getBytes(StandardCharsets.UTF_8);
        String msg = ModelManager.registerExpressionIntoModel(
                ctx(), "ex1", "f07.exp3.json", data, "F07");
        assertTrue(msg, msg.contains("注册为表情"));

        JSONArray arr = expressionsOf(json);
        assertEquals(1, arr.length());
        assertEquals("F07", arr.getJSONObject(0).getString("Name"));
        assertEquals("f07.exp3.json", arr.getJSONObject(0).getString("File"));
        assertTrue(new File(modelDir("ex1"), "f07.exp3.json").exists());
    }

    @Test
    public void registerExpressionIntoModel_rejectsDuplicateName() throws Exception {
        JSONObject fr = new JSONObject().put("Expressions",
                new JSONArray().put(new JSONObject().put("Name", "F01").put("File", "f01.exp3.json")));
        File json = new File(modelDir("ex2"), "ex2.model3.json");
        write(json, new JSONObject().put("Version", 3).put("FileReferences", fr).toString(2));
        write(new File(modelDir("ex2"), "f01.exp3.json"), "{}");

        byte[] data = "{}".getBytes(StandardCharsets.UTF_8);
        String msg = ModelManager.registerExpressionIntoModel(
                ctx(), "ex2", "dup.exp3.json", data, "F01");
        assertTrue(msg, msg.contains("已被占用"));
        // 未新增条目、未落文件
        assertEquals(1, expressionsOf(json).length());
        assertFalse(new File(modelDir("ex2"), "dup.exp3.json").exists());
    }

    @Test
    public void registerExpressionIntoModel_rejectsBuiltin() {
        byte[] data = "{}".getBytes(StandardCharsets.UTF_8);
        String msg = ModelManager.registerExpressionIntoModel(
                ctx(), "huohuo", "f.exp3.json", data, "F01");
        assertTrue(msg, msg.contains("只读") || msg.contains("不可写"));
    }

    @Test
    public void registerExistingFileAsExpression_registersWithoutCopying() throws Exception {
        File json = new File(modelDir("ex3"), "ex3.model3.json");
        write(json, new JSONObject().put("Version", 3)
                .put("FileReferences", new JSONObject()).toString(2));
        write(new File(modelDir("ex3"), "orphan.exp3.json"), "{}");

        String msg = ModelManager.registerExistingFileAsExpression(
                ctx(), "ex3", "orphan.exp3.json", "F09");
        assertTrue(msg, msg.contains("注册为表情"));
        JSONArray arr = expressionsOf(json);
        assertEquals(1, arr.length());
        assertEquals("F09", arr.getJSONObject(0).getString("Name"));
        assertEquals("orphan.exp3.json", arr.getJSONObject(0).getString("File"));
        // 只注册、不复制：目录里仍只有 1 个 .exp3.json
        File[] exp = modelDir("ex3").listFiles((d, n) -> n.endsWith(".exp3.json"));
        assertEquals(1, exp == null ? 0 : exp.length);
    }

    @Test
    public void registerExistingFileAsExpression_intoModelJson_usesFileReferences() throws Exception {
        // .model.json（非 model3）：表情新建仍落 FileReferences.Expressions，与扫描层一致
        File json = new File(modelDir("ex7"), "ex7.model.json");
        write(json, new JSONObject().put("Version", 2).toString());
        write(new File(modelDir("ex7"), "o.exp3.json"), "{}");

        String msg = ModelManager.registerExistingFileAsExpression(ctx(), "ex7", "o.exp3.json", "F01");
        assertTrue(msg, msg.contains("注册为表情"));
        JSONObject root = readJson(json);
        assertEquals(1, root.getJSONObject("FileReferences").getJSONArray("Expressions").length());
        assertFalse(root.has("Expressions"));
    }

    @Test
    public void removeExpressionFromModel_unregistersButKeepsFile() throws Exception {
        File json = writeModel3WithExpr("ex4", "F03", "f03.exp3.json");
        String msg = ModelManager.removeExpressionFromModel(ctx(), "ex4", "F03");
        assertTrue(msg, msg.contains("移除"));

        assertEquals(0, expressionsOf(json).length());
        // 表情文件保留（变为未注册表情）
        assertTrue(new File(modelDir("ex4"), "f03.exp3.json").exists());
    }

    @Test
    public void renameExpression_changesName() throws Exception {
        File json = writeModel3WithExpr("ex5", "F02", "f02.exp3.json");
        String msg = ModelManager.renameExpression(ctx(), "ex5", "F02", "F22");
        assertTrue(msg, msg.contains("重命名"));

        JSONArray arr = expressionsOf(json);
        assertEquals(1, arr.length());
        assertEquals("F22", arr.getJSONObject(0).getString("Name"));
        // 只改 Name，指向的文件不变
        assertEquals("f02.exp3.json", arr.getJSONObject(0).getString("File"));
    }

    @Test
    public void renameExpression_rejectsDuplicateName() throws Exception {
        JSONObject fr = new JSONObject().put("Expressions",
                new JSONArray()
                        .put(new JSONObject().put("Name", "F01").put("File", "f01.exp3.json"))
                        .put(new JSONObject().put("Name", "F02").put("File", "f02.exp3.json")));
        File json = new File(modelDir("ex6"), "ex6.model3.json");
        write(json, new JSONObject().put("Version", 3).put("FileReferences", fr).toString(2));

        String msg = ModelManager.renameExpression(ctx(), "ex6", "F01", "F02");
        assertTrue(msg, msg.contains("已被占用"));
        // 未改动
        JSONArray arr = expressionsOf(json);
        assertEquals("F01", arr.getJSONObject(0).getString("Name"));
    }

    @Test
    public void registeredExpression_isVisibleToScanLayer() throws Exception {
        // 落点对齐锁：ModelManager 写回后，MotionLibrary 扫描层应能读到同一个表情，
        // 否则界面里「看到的表情」和「改到的表情」会分处两地
        writeModel3WithExpr("ex9", "F01", "f01.exp3.json");
        ModelManager.registerExistingFileAsExpression(ctx(), "ex9", "f01.exp3.json", "F02");

        MotionLibrary.ModelActions target = null;
        for (MotionLibrary.ModelActions m : MotionLibrary.scanAll(ctx(), "ex9")) {
            if (m.dirName.equals("ex9")) {
                target = m;
                break;
            }
        }
        assertTrue("扫描层应能发现模型 ex9", target != null);
        boolean found = false;
        for (MotionLibrary.Expr e : target.expressions) {
            if ("F02".equals(e.name)) found = true;
        }
        assertTrue("扫描层应能读到新注册的表情 F02", found);
    }

    // ── 淡入/淡出轻编辑（v1.170.0）─────────────────────────────

    private MotionLibrary.ModelActions findModel(String dir) {
        for (MotionLibrary.ModelActions m : MotionLibrary.scanAll(ctx(), dir)) {
            if (m.dirName.equals(dir)) return m;
        }
        return null;
    }

    @Test
    public void setMotionFade_writesToRegistrationEntry() throws Exception {
        File json = writeModel3("fd1", "Idle", "idle.motion3.json");
        String msg = ModelManager.setMotionFade(
                ctx(), "fd1", "Idle", "idle.motion3.json", 0.5, 1.2);
        assertTrue(msg, msg.contains("淡入"));

        JSONObject item = motionsOf(json).getJSONArray("Idle").getJSONObject(0);
        assertEquals(0.5, item.getDouble("FadeInTime"), 1e-6);
        assertEquals(1.2, item.getDouble("FadeOutTime"), 1e-6);
    }

    @Test
    public void setMotionFade_clampsNegativeToZero() throws Exception {
        File json = writeModel3("fd2", "Idle", "idle.motion3.json");
        ModelManager.setMotionFade(ctx(), "fd2", "Idle", "idle.motion3.json", -3, 0.25);
        JSONObject item = motionsOf(json).getJSONArray("Idle").getJSONObject(0);
        assertEquals(0.0, item.getDouble("FadeInTime"), 1e-6);
        assertEquals(0.25, item.getDouble("FadeOutTime"), 1e-6);
    }

    @Test
    public void setMotionFade_rejectsUnknownGroup() throws Exception {
        writeModel3("fd3", "Idle", "idle.motion3.json");
        String msg = ModelManager.setMotionFade(
                ctx(), "fd3", "Nope", "idle.motion3.json", 1, 1);
        assertTrue(msg, msg.contains("未找到动作组"));
    }

    @Test
    public void setMotionFade_rejectsBuiltin() {
        String msg = ModelManager.setMotionFade(
                ctx(), "huohuo", "Idle", "Scene1.motion3.json", 1, 1);
        assertTrue(msg, msg.contains("只读") || msg.contains("不可写"));
    }

    @Test
    public void setMotionFade_isVisibleToScanLayer() throws Exception {
        // 落点对齐锁：改完注册条目后，扫描层读到的 fadeIn/fadeOut 应同步
        writeModel3("fd4", "Idle", "idle.motion3.json");
        ModelManager.setMotionFade(ctx(), "fd4", "Idle", "idle.motion3.json", 0.7, 0.8);

        MotionLibrary.ModelActions target = findModel("fd4");
        assertTrue("扫描层应能发现模型 fd4", target != null);
        MotionLibrary.Motion mo = target.groups.get(0).motions.get(0);
        assertEquals(0.7, mo.fadeIn, 1e-6);
        assertEquals(0.8, mo.fadeOut, 1e-6);
    }

    @Test
    public void scanLayer_missingFadeIsNaN() throws Exception {
        // 注册条目未声明 FadeInTime/FadeOutTime 时记为 NaN（引擎默认 1.0），供详情区分
        writeModel3("fd5", "Idle", "idle.motion3.json");
        MotionLibrary.ModelActions target = findModel("fd5");
        assertTrue(target != null);
        MotionLibrary.Motion mo = target.groups.get(0).motions.get(0);
        assertTrue(Double.isNaN(mo.fadeIn));
        assertTrue(Double.isNaN(mo.fadeOut));
    }
}
