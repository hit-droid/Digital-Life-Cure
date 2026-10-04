package com.digitallife.util;

import java.util.List;

/**
 * v1.147.0（#87）：运行环境自检的纯逻辑。
 *
 * <p>用户报障（如 v1.141.0 修过的「Android 16 装上打不开」，根因是 16 KB 内存页与
 * 4 KB 对齐 native 库不兼容）时，我们拿不到对方机型信息。本类把「原始输入」拼成
 * 展示文案与兼容性判定，Android 侧只负责取值（SDK_INT / Build.RELEASE / SUPPORTED_ABIS /
 * {@code Os.sysconf(_SC_PAGESIZE)}）后传参进来。</p>
 *
 * <p><b>不依赖任何 Android API</b>，可在普通 JVM 单测中运行。</p>
 */
public final class EnvFacts {

    /** 16 KB 内存页阈值：页大小 >= 16 KB 视为 16 KB 页设备 */
    public static final long PAGE_16K_BYTES = 16 * 1024L;

    private EnvFacts() {
    }

    /** 「Android 16 (API 36)」形式的单行描述 */
    public static String androidLine(int sdkInt, String release) {
        String rel = release == null || release.trim().isEmpty() ? "未知" : release.trim();
        return "Android " + rel + " (API " + sdkInt + ")";
    }

    /** ABI 列表拼接，如 {@code "arm64-v8a, armeabi-v7a"}；空列表返回「未知」 */
    public static String abiLine(List<String> abis) {
        if (abis == null || abis.isEmpty()) return "未知";
        StringBuilder sb = new StringBuilder();
        for (String a : abis) {
            if (a == null || a.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(a.trim());
        }
        return sb.length() == 0 ? "未知" : sb.toString();
    }

    /** 「16384 B（16 KB）」形式的页大小描述 */
    public static String pageLine(long pageSizeBytes) {
        if (pageSizeBytes <= 0) return "未知";
        if (pageSizeBytes % 1024L == 0) {
            return pageSizeBytes + " B（" + (pageSizeBytes / 1024L) + " KB）";
        }
        return pageSizeBytes + " B";
    }

    /** 是否 16 KB（及以上）内存页设备 */
    public static boolean compatible16k(long pageSizeBytes) {
        return pageSizeBytes >= PAGE_16K_BYTES;
    }

    /**
     * 供「复制环境信息」用的多行纯文本报告。
     *
     * @param appVersion 应用版本（可空；空则用「未知」占位）
     */
    public static String report(String appVersion, int sdkInt, String release,
                                List<String> abis, long pageSizeBytes) {
        String ver = appVersion == null || appVersion.trim().isEmpty() ? "未知" : appVersion.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("数字生命 · 运行环境自检\n");
        sb.append("应用版本：").append(ver).append('\n');
        sb.append("系统：").append(androidLine(sdkInt, release)).append('\n');
        sb.append("ABI：").append(abiLine(abis)).append('\n');
        sb.append("内存页大小：").append(pageLine(pageSizeBytes)).append('\n');
        sb.append("16 KB 页面设备：").append(compatible16k(pageSizeBytes) ? "是" : "否");
        return sb.toString();
    }
}
