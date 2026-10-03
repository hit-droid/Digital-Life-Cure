package com.digitallife.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * DataPort 纯逻辑单测：导出清单、zip 内容、清除范围（必须保留 models）、空目录容错。
 */
public class DataPortTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static void write(File f, String content) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Set<String> pathsOf(List<DataPort.Entry> entries) {
        Set<String> s = new HashSet<>();
        for (DataPort.Entry e : entries) s.add(e.path);
        return s;
    }

    private static Set<String> zipNames(byte[] zip) throws Exception {
        Set<String> names = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) names.add(e.getName());
        }
        return names;
    }

    /** 搭一套典型目录：files（含 models）、shared_prefs、databases */
    private File[] scaffold() throws Exception {
        File filesDir = tmp.newFolder("files");
        File prefsDir = tmp.newFolder("shared_prefs");
        File dbDir = tmp.newFolder("databases");

        write(new File(filesDir, "a.txt"), "aaa");
        write(new File(new File(filesDir, "sub"), "c.txt"), "ccc");
        write(new File(new File(filesDir, DataPort.KEEP_MODELS), "m.bin"), "model");
        write(new File(prefsDir, "pet_settings.xml"), "<prefs/>");
        write(new File(dbDir, "memory.db"), "sqlite");
        return new File[]{filesDir, prefsDir, dbDir};
    }

    @Test
    public void exportEntries_listsAll_exceptModels() throws Exception {
        File[] d = scaffold();
        Set<String> paths = pathsOf(DataPort.exportEntries(d[0], d[1], d[2]));

        assertTrue(paths.contains("files/a.txt"));
        assertTrue(paths.contains("files/sub"));
        assertTrue(paths.contains("shared_prefs/pet_settings.xml"));
        assertTrue(paths.contains("databases/memory.db"));
        assertFalse("models 必须被排除", paths.contains("files/models"));
    }

    @Test
    public void exportZip_containsFilesButNotModels() throws Exception {
        File[] d = scaffold();
        List<DataPort.Entry> entries = DataPort.exportEntries(d[0], d[1], d[2]);

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int count = DataPort.exportZip(bos, entries);

        assertEquals("应写入 4 个文件（a.txt / sub-c.txt / prefs / db）", 4, count);
        Set<String> names = zipNames(bos.toByteArray());
        assertTrue(names.contains("files/a.txt"));
        assertTrue(names.contains("files/sub/c.txt"));
        assertTrue(names.contains("shared_prefs/pet_settings.xml"));
        assertTrue(names.contains("databases/memory.db"));
        assertFalse("zip 里不应有模型", names.contains("files/models/m.bin"));
        assertFalse(names.contains("files/models"));
    }

    @Test
    public void clear_removesUserData_butKeepsModels() throws Exception {
        File[] d = scaffold();
        int deleted = DataPort.clear(d[0], d[1], d[2]);

        assertTrue(deleted > 0);
        assertFalse(new File(d[0], "a.txt").exists());
        assertFalse(new File(d[0], "sub").exists());
        assertFalse(new File(d[1], "pet_settings.xml").exists());
        assertFalse(new File(d[2], "memory.db").exists());
        assertTrue("已导入的模型必须保留", new File(d[0], "models/m.bin").exists());
    }

    @Test
    public void missingDirs_areSkippedSafely() throws Exception {
        File filesDir = tmp.newFolder("only-files");
        write(new File(filesDir, "x.txt"), "x");

        List<DataPort.Entry> entries = DataPort.exportEntries(
                filesDir, new File(tmp.getRoot(), "nope-prefs"), null);
        Set<String> paths = pathsOf(entries);
        assertTrue(paths.contains("files/x.txt"));
        assertEquals("不存在的目录不应产生条目", 1, entries.size());

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        assertEquals(1, DataPort.exportZip(bos, entries));
    }

    @Test
    public void nullRoots_areSafe() {
        assertTrue(DataPort.exportEntries(null, null, null).isEmpty());
        assertEquals(0, DataPort.clear(null, null, null));
    }

    @Test
    public void isExcluded_onlyModels() {
        assertTrue(DataPort.isExcluded(DataPort.KEEP_MODELS));
        assertFalse(DataPort.isExcluded("skills"));
        assertFalse(DataPort.isExcluded(null));
    }

    @Test
    public void emptyDirs_produceNoFileCount() throws Exception {
        File filesDir = tmp.newFolder("empty-files");
        File prefsDir = tmp.newFolder("empty-prefs");
        File dbDir = tmp.newFolder("empty-db");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int count = DataPort.exportZip(bos, DataPort.exportEntries(filesDir, prefsDir, dbDir));
        assertEquals(0, count);
    }
}
