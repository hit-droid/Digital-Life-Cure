package com.digitallife.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * v1.147.0（#87）：{@link EnvFacts} 运行环境自检纯逻辑回归。
 */
public class EnvFactsTest {

    // ==================== androidLine ====================

    @Test
    public void androidLine_normal() {
        assertEquals("Android 16 (API 36)", EnvFacts.androidLine(36, "16"));
    }

    @Test
    public void androidLine_nullReleaseFallsBack() {
        assertTrue(EnvFacts.androidLine(33, null).contains("未知"));
        assertTrue(EnvFacts.androidLine(33, "  ").contains("未知"));
    }

    // ==================== abiLine ====================

    @Test
    public void abiLine_multipleJoinedByComma() {
        assertEquals("arm64-v8a, armeabi-v7a",
                EnvFacts.abiLine(Arrays.asList("arm64-v8a", "armeabi-v7a")));
    }

    @Test
    public void abiLine_emptyListAndNullDoNotCrash() {
        assertEquals("未知", EnvFacts.abiLine(null));
        assertEquals("未知", EnvFacts.abiLine(new ArrayList<>()));
    }

    @Test
    public void abiLine_skipsBlankEntries() {
        assertEquals("arm64-v8a",
                EnvFacts.abiLine(Arrays.asList("", "arm64-v8a", "  ")));
    }

    // ==================== pageLine / compatible16k ====================

    @Test
    public void pageLine_4k() {
        assertEquals("4096 B（4 KB）", EnvFacts.pageLine(4096L));
    }

    @Test
    public void pageLine_16k() {
        assertEquals("16384 B（16 KB）", EnvFacts.pageLine(16384L));
    }

    @Test
    public void pageLine_unknownWhenNonPositive() {
        assertEquals("未知", EnvFacts.pageLine(0L));
        assertEquals("未知", EnvFacts.pageLine(-1L));
    }

    @Test
    public void compatible16k_trueAtAndAbove16k() {
        assertTrue(EnvFacts.compatible16k(16384L));
        assertTrue(EnvFacts.compatible16k(65536L));
    }

    @Test
    public void compatible16k_falseFor4k() {
        assertFalse(EnvFacts.compatible16k(4096L));
        assertFalse(EnvFacts.compatible16k(0L));
    }

    // ==================== report ====================

    @Test
    public void report_containsAllFields() {
        String r = EnvFacts.report("1.147.0", 36, "16",
                Arrays.asList("arm64-v8a"), 16384L);
        assertTrue(r.contains("应用版本：1.147.0"));
        assertTrue(r.contains("Android 16 (API 36)"));
        assertTrue(r.contains("ABI：arm64-v8a"));
        assertTrue(r.contains("16384 B（16 KB）"));
        assertTrue(r.contains("16 KB 页面设备：是"));
    }

    @Test
    public void report_blankVersionBecomesUnknown() {
        String r = EnvFacts.report(null, 33, "13", null, 4096L);
        assertTrue(r.contains("应用版本：未知"));
        assertTrue(r.contains("ABI：未知"));
        assertTrue(r.contains("16 KB 页面设备：否"));
    }
}
