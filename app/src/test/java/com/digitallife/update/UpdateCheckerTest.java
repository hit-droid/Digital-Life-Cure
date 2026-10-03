package com.digitallife.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 应用内更新检查纯逻辑回归（Issue #41）。
 *
 * <p>版本比较写错会直接导致「有新版不提示」或「没新版天天提示」；Releases 解析写错会在
 * 上游字段变动时崩或静默失效。网络没法在单测里跑，所以把这两块算术与解析钉死。</p>
 */
public class UpdateCheckerTest {

    // ---------- 版本比较 ----------

    @Test
    public void isNewer_basicAndVPrefix() {
        assertTrue(UpdateChecker.isNewer("1.137.0", "v1.138.0"));
        assertFalse(UpdateChecker.isNewer("1.137.0", "v1.137.0"));
        assertFalse(UpdateChecker.isNewer("1.138.0", "v1.137.0"));
        assertTrue(UpdateChecker.isNewer("1.137.0", "1.138.0")); // 无前缀也认
    }

    @Test
    public void compare_isNumericNotLexicographic() {
        // 字符串比较会得出 "1.10" < "1.9"，数值比较才是对的
        assertTrue(UpdateChecker.compare("1.10.0", "1.9.0") > 0);
        assertTrue(UpdateChecker.compare("1.9.0", "1.10.0") < 0);
        assertEquals(0, UpdateChecker.compare("1.137.0", "1.137.0"));
    }

    @Test
    public void compare_handlesDifferentSegmentCounts() {
        assertEquals(0, UpdateChecker.compare("1.137", "1.137.0"));  // 缺段补 0
        assertTrue(UpdateChecker.compare("1.137.1", "1.137") > 0);
        assertTrue(UpdateChecker.compare("2.0", "1.999.999") > 0);
    }

    @Test
    public void normalize_stripsPrereleaseAndBuildSuffix() {
        assertEquals("1.138.0", UpdateChecker.normalize("v1.138.0-rc1"));
        assertEquals("1.138.0", UpdateChecker.normalize("1.138.0+build.7"));
        assertEquals("1.138.0", UpdateChecker.normalize("V1.138.0"));
    }

    @Test
    public void malformed_normallyNotNewer_andNoCrash() {
        assertFalse(UpdateChecker.isNewer("1.137.0", "nightly"));
        assertFalse(UpdateChecker.isNewer("1.137.0", ""));
        assertFalse(UpdateChecker.isNewer("1.137.0", null));
        assertFalse(UpdateChecker.isNewer(null, "v1.138.0"));
        assertNull(UpdateChecker.normalize("release-latest"));
        assertNull(UpdateChecker.normalize(null));
    }

    @Test
    public void hugeNumericSegment_isRejectedNotOverflowed() {
        assertNull(UpdateChecker.normalize("1.999999999999.0"));
    }

    @Test
    public void prereleaseTag_sameBaseIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.138.0", "v1.138.0-rc1"));
    }

    // ---------- Releases 解析 ----------

    private static final String LIST = "["
            + "{\"tag_name\":\"v1.135.0\",\"html_url\":\"u135\",\"draft\":false,\"prerelease\":false},"
            + "{\"tag_name\":\"v1.137.0\",\"html_url\":\"u137\",\"draft\":false,\"prerelease\":false},"
            + "{\"tag_name\":\"v1.138.0-rc1\",\"html_url\":\"uRC\",\"draft\":false,\"prerelease\":true},"
            + "{\"tag_name\":\"v9.0.0\",\"html_url\":\"uDraft\",\"draft\":true,\"prerelease\":false},"
            + "{\"tag_name\":\"v1.136.0\",\"html_url\":\"u136\",\"draft\":false,\"prerelease\":false}"
            + "]";

    @Test
    public void pickLatest_skipsDraftAndPrerelease_picksHighestByVersion() {
        UpdateChecker.Release r = UpdateChecker.pickLatest(LIST, false);
        assertNotNull(r);
        assertEquals("v1.137.0", r.tag);   // 9.0.0 是 draft、1.138.0-rc1 是 prerelease，都跳过
        assertEquals("1.137.0", r.version);
        assertEquals("u137", r.url);
    }

    @Test
    public void pickLatest_withPrereleaseAllowed_canPickPrerelease() {
        UpdateChecker.Release r = UpdateChecker.pickLatest(LIST, true);
        assertNotNull(r);
        assertEquals("v1.138.0-rc1", r.tag);
        assertTrue(r.prerelease);
    }

    @Test
    public void pickLatest_ignoresArrayOrder() {
        // 最高版本排在最后，也应被选中
        String shuffled = "[{\"tag_name\":\"v1.100.0\"},{\"tag_name\":\"v1.99.0\"},"
                + "{\"tag_name\":\"v1.120.0\"}]";
        UpdateChecker.Release r = UpdateChecker.pickLatest(shuffled, false);
        assertNotNull(r);
        assertEquals("v1.120.0", r.tag);
    }

    @Test
    public void pickLatest_malformedOrEmpty_returnsNull() {
        assertNull(UpdateChecker.pickLatest(null, false));
        assertNull(UpdateChecker.pickLatest("", false));
        assertNull(UpdateChecker.pickLatest("not json", false));
        assertNull(UpdateChecker.pickLatest("[]", false));
        // 全是坏 tag → 没有可用项
        assertNull(UpdateChecker.pickLatest("[{\"tag_name\":\"nightly\"}]", false));
    }

    @Test
    public void parseRelease_missingUrl_fallsBackToReleasesPage() {
        UpdateChecker.Release r = UpdateChecker.parseRelease(
                "{\"tag_name\":\"v1.138.0\",\"body\":\"更新说明\"}");
        assertNotNull(r);
        assertEquals("1.138.0", r.version);
        assertEquals("更新说明", r.notes);
        assertTrue(r.url.contains("/releases"));
    }

    @Test
    public void parseRelease_invalid_returnsNull() {
        assertNull(UpdateChecker.parseRelease(null));
        assertNull(UpdateChecker.parseRelease("{}"));
        assertNull(UpdateChecker.parseRelease("not json"));
        assertNull(UpdateChecker.parseRelease("{\"tag_name\":\"nightly\"}"));
    }
}
