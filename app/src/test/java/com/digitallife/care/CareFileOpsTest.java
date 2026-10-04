package com.digitallife.care;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * CareFileOps 纯逻辑单测（v1.143.0 从 CareTools 上帝类下沉）。
 *
 * <p>重点覆盖两类容易出事的地方：Zip Slip 防护 {@code safeResolve} 的边界，
 * 以及路径/尺寸/文件名格式化的纯函数行为。全部为 JVM 测试，不需要 Robolectric。
 */
public class CareFileOpsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // ── safeResolve：Zip Slip 防护 ──────────────────────────────

    @Test
    public void safeResolve_normalRelativePath_ok() throws Exception {
        File base = tmp.newFolder("base");
        File f = CareFileOps.safeResolve(base, "a/b/c.txt");
        assertNotNull(f);
        assertEquals(new File(base, "a/b/c.txt").getCanonicalPath(), f.getCanonicalPath());
    }

    @Test
    public void safeResolve_absolutePath_rejected() throws Exception {
        File base = tmp.newFolder("base");
        assertNull(CareFileOps.safeResolve(base, "/etc/passwd"));
    }

    @Test
    public void safeResolve_dotDot_rejected() throws Exception {
        File base = tmp.newFolder("base");
        assertNull(CareFileOps.safeResolve(base, "../outside.txt"));
        assertNull(CareFileOps.safeResolve(base, "a/../../outside.txt"));
    }

    @Test
    public void safeResolve_nullOrEmpty_rejected() throws Exception {
        File base = tmp.newFolder("base");
        assertNull(CareFileOps.safeResolve(base, null));
        assertNull(CareFileOps.safeResolve(base, ""));
    }

    // ── stripModelJsonSuffix ────────────────────────────────────

    @Test
    public void stripModelJsonSuffix_bothSuffixesAndCase() {
        assertEquals("xiaoxi", CareFileOps.stripModelJsonSuffix("xiaoxi.model3.json"));
        assertEquals("xiaoxi", CareFileOps.stripModelJsonSuffix("xiaoxi.model.json"));
        // 后缀匹配忽略大小写，但文件名本身大小写原样保留
        assertEquals("XIAOXI", CareFileOps.stripModelJsonSuffix("XIAOXI.MODEL3.JSON"));
        assertEquals("readme.txt", CareFileOps.stripModelJsonSuffix("readme.txt"));
    }

    // ── sanitizeDirName ─────────────────────────────────────────

    @Test
    public void sanitizeDirName_blankFallsBackToModel() {
        assertEquals("model", CareFileOps.sanitizeDirName(null));
        assertEquals("model", CareFileOps.sanitizeDirName(""));
        assertEquals("model", CareFileOps.sanitizeDirName("   "));
    }

    @Test
    public void sanitizeDirName_replacesIllegalCharsAndTrims() {
        assertEquals("a_b_c_d_e_f_g_h_i", CareFileOps.sanitizeDirName("a\\b/c:d*e?f\"g<h>i"));
        assertEquals("my_model", CareFileOps.sanitizeDirName("  my_model  "));
    }

    // ── formatSize ──────────────────────────────────────────────

    @Test
    public void formatSize_bytesKbMb() {
        assertEquals("0 B", CareFileOps.formatSize(0));
        assertEquals("1023 B", CareFileOps.formatSize(1023));
        assertEquals("1.0 KB", CareFileOps.formatSize(1024));
        assertEquals("1.0 MB", CareFileOps.formatSize(1024 * 1024));
        assertEquals("1.5 MB", CareFileOps.formatSize(1024 * 1024 * 3 / 2));
    }

    // ── relPath ─────────────────────────────────────────────────

    @Test
    public void relPath_stripsRootPrefixAndSeparator() throws Exception {
        File root = tmp.newFolder("root");
        File child = new File(root, "moc/x.moc3");
        assertEquals("moc" + File.separator + "x.moc3", CareFileOps.relPath(root, child));
    }

    // ── firstFile / countFiles ──────────────────────────────────

    @Test
    public void firstFile_matchesSuffixOrNull() throws Exception {
        File dir = tmp.newFolder("models");
        Files.write(new File(dir, "a.model3.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        assertNotNull(CareFileOps.firstFile(dir, ".model3.json"));
        assertNull(CareFileOps.firstFile(dir, ".motion3.json"));
    }

    @Test
    public void countFiles_recursesSubdirs() throws Exception {
        File dir = tmp.newFolder("models");
        new File(dir, "sub").mkdirs();
        Files.write(new File(dir, "a.motion3.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "sub/b.motion3.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "sub/ignore.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        assertEquals(2, CareFileOps.countFiles(dir, ".motion3.json"));
    }

    // ── readFile / deleteRecursive / copyRecursive ──────────────

    @Test
    public void readFile_normalizesNewlinesAndTrims() throws Exception {
        File f = new File(tmp.newFolder("m"), "a.txt");
        Files.write(f.toPath(), "line1\r\nline2\n\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("line1\nline2", CareFileOps.readFile(f));
    }

    @Test
    public void deleteRecursive_removesNestedTree() throws Exception {
        File dir = tmp.newFolder("victim");
        File sub = new File(dir, "sub");
        sub.mkdirs();
        Files.write(new File(sub, "a.txt").toPath(), "x".getBytes(StandardCharsets.UTF_8));
        CareFileOps.deleteRecursive(dir);
        assertTrue("目录应被递归删除", !dir.exists());
    }

    @Test
    public void copyRecursive_copiesNestedTree() throws Exception {
        File src = tmp.newFolder("src");
        File sub = new File(src, "sub");
        sub.mkdirs();
        Files.write(new File(sub, "a.txt").toPath(), "hi".getBytes(StandardCharsets.UTF_8));
        File dst = new File(tmp.getRoot(), "dst");

        CareFileOps.copyRecursive(src, dst);

        File copied = new File(new File(dst, "sub"), "a.txt");
        assertTrue("递归复制应带上子目录文件", copied.isFile());
        assertEquals("hi", CareFileOps.readFile(copied));
    }
}
