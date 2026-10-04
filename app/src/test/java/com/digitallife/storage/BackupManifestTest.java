package com.digitallife.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.json.JSONException;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BackupManifest 纯逻辑单测：清单构建/解析往返、校验通过，以及缺条目 / 大小不符 /
 * CRC 不符 / 多出条目 / 版本与 CRC 非法等拒绝路径。
 */
public class BackupManifestTest {

    private static Map<String, long[]> actual(String path, long size, long crc) {
        Map<String, long[]> m = new LinkedHashMap<>();
        m.put(path, new long[]{size, crc});
        return m;
    }

    @Test
    public void buildParse_roundTrip() throws Exception {
        List<BackupManifest.Item> items = Arrays.asList(
                new BackupManifest.Item("files/a.txt", 3, 0x1a2b3c4dL),
                new BackupManifest.Item("databases/memory.db", 12345, 0L));

        List<BackupManifest.Item> parsed =
                BackupManifest.parse(BackupManifest.build(1700000000000L, items));

        assertEquals(2, parsed.size());
        assertEquals("files/a.txt", parsed.get(0).path);
        assertEquals(3, parsed.get(0).size);
        assertEquals(0x1a2b3c4dL, parsed.get(0).crc);
        assertEquals("databases/memory.db", parsed.get(1).path);
        assertEquals(12345, parsed.get(1).size);
        assertEquals(0L, parsed.get(1).crc);
    }

    @Test
    public void build_nullItems_isEmptyList() throws Exception {
        assertTrue(BackupManifest.parse(BackupManifest.build(1L, null)).isEmpty());
    }

    @Test
    public void verify_allMatch_passes() throws Exception {
        BackupManifest.verify(
                Arrays.asList(new BackupManifest.Item("files/a.txt", 3, 7L)),
                actual("files/a.txt", 3, 7L));
    }

    @Test
    public void verify_missingEntry_rejects() throws Exception {
        try {
            BackupManifest.verify(
                    Arrays.asList(new BackupManifest.Item("files/a.txt", 3, 7L)),
                    new LinkedHashMap<>());
            fail("缺条目应拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("缺少条目"));
        }
    }

    @Test
    public void verify_sizeMismatch_rejects() throws Exception {
        try {
            BackupManifest.verify(
                    Arrays.asList(new BackupManifest.Item("files/a.txt", 3, 7L)),
                    actual("files/a.txt", 4, 7L));
            fail("大小不符应拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("大小不符"));
        }
    }

    @Test
    public void verify_crcMismatch_rejects() throws Exception {
        try {
            BackupManifest.verify(
                    Arrays.asList(new BackupManifest.Item("files/a.txt", 3, 7L)),
                    actual("files/a.txt", 3, 8L));
            fail("CRC 不符应拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("损坏"));
        }
    }

    @Test
    public void verify_extraEntry_rejects() throws Exception {
        Map<String, long[]> got = actual("files/a.txt", 3, 7L);
        got.put("files/extra.txt", new long[]{1, 1L});
        try {
            BackupManifest.verify(
                    Arrays.asList(new BackupManifest.Item("files/a.txt", 3, 7L)), got);
            fail("多出条目应拒绝");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("未声明"));
        }
    }

    @Test
    public void parse_unsupportedFormat_throws() {
        try {
            BackupManifest.parse("{\"format\":99,\"files\":[]}");
            fail("版本不支持应抛");
        } catch (JSONException expected) {
            // 预期
        }
    }

    @Test
    public void parse_badCrc_throws() {
        try {
            BackupManifest.parse("{\"format\":1,\"files\":[{\"path\":\"a\",\"size\":1,\"crc\":\"zz\"}]}");
            fail("CRC 非法应抛");
        } catch (JSONException expected) {
            // 预期
        }
    }
}
