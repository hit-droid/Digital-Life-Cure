package com.digitallife.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * BackupArchive 纯逻辑单测：三个顶层前缀的映射、Zip Slip 防护、未知条目忽略、models 不覆盖。
 */
public class BackupArchiveTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 用指定条目名/内容拼一个 zip */
    private static byte[] zip(String[] names, String[] contents) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (int i = 0; i < names.length; i++) {
                zos.putNextEntry(new ZipEntry(names[i]));
                if (contents[i] != null) {
                    zos.write(contents[i].getBytes(StandardCharsets.UTF_8));
                }
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private File[] roots() throws Exception {
        return new File[]{
                tmp.newFolder("files"),
                tmp.newFolder("shared_prefs"),
                tmp.newFolder("databases")};
    }

    @Test
    public void restore_mapsThreePrefixes() throws Exception {
        File[] r = roots();
        byte[] z = zip(
                new String[]{"files/a.txt", "files/sub/c.txt", "shared_prefs/pet.xml", "databases/memory.db"},
                new String[]{"aaa", "ccc", "<prefs/>", "sqlite"});

        BackupArchive.Result res = BackupArchive.restore(z, r[0], r[1], r[2]);

        assertEquals(4, res.restored);
        assertEquals(0, res.skipped);
        assertEquals("aaa", read(new File(r[0], "a.txt")));
        assertEquals("ccc", read(new File(r[0], "sub/c.txt")));
        assertEquals("<prefs/>", read(new File(r[1], "pet.xml")));
        assertEquals("sqlite", read(new File(r[2], "memory.db")));
    }

    @Test
    public void restore_rejectsZipSlip() throws Exception {
        File[] r = roots();
        byte[] z = zip(
                new String[]{"files/../evil.txt", "files/../../evil2.txt", "files/a/../../evil3.txt", "/abs.txt"},
                new String[]{"x", "x", "x", "x"});

        BackupArchive.Result res = BackupArchive.restore(z, r[0], r[1], r[2]);

        assertEquals("全部条目都应被拒绝", 0, res.restored);
        assertEquals(4, res.skipped);
        assertFalse("不能写到 files 之外", new File(r[0].getParentFile(), "evil.txt").exists());
        assertFalse(new File(r[0].getParentFile().getParentFile(), "evil2.txt").exists());
    }

    @Test
    public void restore_ignoresUnknownPrefix() throws Exception {
        File[] r = roots();
        byte[] z = zip(
                new String[]{"etc/passwd", "other/x.txt", "files/ok.txt"},
                new String[]{"root", "x", "ok"});

        BackupArchive.Result res = BackupArchive.restore(z, r[0], r[1], r[2]);

        assertEquals(1, res.restored);
        assertEquals(2, res.skipped);
        assertEquals("ok", read(new File(r[0], "ok.txt")));
    }

    @Test
    public void restore_neverOverwritesModels() throws Exception {
        File[] r = roots();
        File models = new File(r[0], DataPort.KEEP_MODELS);
        assertTrue(models.mkdirs());
        // 预置一个模型文件，模拟「已导入模型」
        File existing = new File(models, "m.json");
        try (java.io.FileOutputStream os = new java.io.FileOutputStream(existing)) {
            os.write("orig".getBytes(StandardCharsets.UTF_8));
        }
        byte[] z = zip(
                new String[]{"files/models/m.json", "files/models/new.json", "files/keep.txt"},
                new String[]{"hacked", "hacked", "keep"});

        BackupArchive.Result res = BackupArchive.restore(z, r[0], r[1], r[2]);

        assertEquals("models 下的条目被跳过，只剩 keep.txt", 1, res.restored);
        assertEquals(2, res.skipped);
        assertEquals("已导入模型不能被覆盖", "orig", read(existing));
        assertFalse("models 下不应新增文件", new File(models, "new.json").exists());
        assertEquals("keep", read(new File(r[0], "keep.txt")));
    }

    @Test
    public void restore_createsDirEntries() throws Exception {
        File[] r = roots();
        byte[] z = zip(new String[]{"files/empty/"}, new String[]{null});

        BackupArchive.Result res = BackupArchive.restore(z, r[0], r[1], r[2]);

        assertEquals(0, res.restored);
        assertTrue(new File(r[0], "empty").isDirectory());
    }

    @Test
    public void resolveTarget_basics() throws Exception {
        File[] r = roots();
        assertNotNull(BackupArchive.resolveTarget("files/a.txt", r[0], r[1], r[2]));
        assertNull("未知前缀", BackupArchive.resolveTarget("weird/a.txt", r[0], r[1], r[2]));
        assertNull("空相对路径", BackupArchive.resolveTarget("files/", r[0], r[1], r[2]));
        assertNull("绝对路径", BackupArchive.resolveTarget("/files/a", r[0], r[1], r[2]));
        // 反斜杠会被归一为 /，随后 .. 段被拒
        assertNull(BackupArchive.resolveTarget("files\\..\\a", r[0], r[1], r[2]));
        // 空 root 时安全返回 null
        assertNull(BackupArchive.resolveTarget("files/a.txt", null, r[1], r[2]));
    }

    @Test
    public void restore_nullZip_throws() throws Exception {
        File[] r = roots();
        try {
            BackupArchive.restore(null, r[0], r[1], r[2]);
            org.junit.Assert.fail("null zip 应抛异常");
        } catch (java.io.IOException expected) {
            // 预期
        }
    }
}
