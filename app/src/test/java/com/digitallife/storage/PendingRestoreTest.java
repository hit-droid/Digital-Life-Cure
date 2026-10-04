package com.digitallife.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * PendingRestore 纯逻辑单测：暂存/应用/清理，以及重复暂存的覆盖语义。
 */
public class PendingRestoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] zip(String name, String content) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
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
    public void noPending_returnsZero() throws Exception {
        File cache = tmp.newFolder("cache");
        File[] r = roots();
        assertFalse(PendingRestore.hasPending(cache));
        assertEquals(0, PendingRestore.applyIfPending(cache, r[0], r[1], r[2]));
    }

    @Test
    public void stageThenApply_restoresAndClearsPending() throws Exception {
        File cache = tmp.newFolder("cache");
        File[] r = roots();

        PendingRestore.stage(cache, zip("shared_prefs/pet.xml", "<prefs/>"));
        assertTrue(PendingRestore.hasPending(cache));

        int n = PendingRestore.applyIfPending(cache, r[0], r[1], r[2]);

        assertEquals(1, n);
        assertEquals("<prefs/>", read(new File(r[1], "pet.xml")));
        assertFalse("应用后必须清除待恢复文件", PendingRestore.hasPending(cache));
        assertEquals("再次应用应为空操作", 0, PendingRestore.applyIfPending(cache, r[0], r[1], r[2]));
    }

    @Test
    public void stage_twice_overwritesPrevious() throws Exception {
        File cache = tmp.newFolder("cache");
        File[] r = roots();

        PendingRestore.stage(cache, zip("files/a.txt", "first"));
        PendingRestore.stage(cache, zip("files/a.txt", "second"));
        assertEquals(1, PendingRestore.applyIfPending(cache, r[0], r[1], r[2]));

        assertEquals("后来的暂存应覆盖先前的", "second", read(new File(r[0], "a.txt")));
    }

    @Test
    public void stage_inNullCache_throws() {
        try {
            PendingRestore.stage(null, new byte[]{1});
            org.junit.Assert.fail("cacheDir 为 null 应抛异常");
        } catch (java.io.IOException expected) {
            // 预期
        }
    }

    @Test
    public void apply_createsMissingTargetDirs() throws Exception {
        File cache = tmp.newFolder("cache");
        File files = new File(tmp.getRoot(), "new-files");
        File prefs = new File(tmp.getRoot(), "new-prefs");
        File dbs = new File(tmp.getRoot(), "new-dbs");

        PendingRestore.stage(cache, zip("databases/memory.db", "sqlite"));
        assertEquals(1, PendingRestore.applyIfPending(cache, files, prefs, dbs));
        assertTrue(new File(dbs, "memory.db").isFile());
    }
}
