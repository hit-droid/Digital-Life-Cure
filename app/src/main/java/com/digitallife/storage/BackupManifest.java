package com.digitallife.storage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 备份包清单（纯逻辑，JVM 可测）：记录格式版本、创建时间，以及每个数据条目的
 * 路径 / 原始字节数 / CRC32。
 *
 * <p>为什么需要它：恢复是「边解压边写盘」的，若备份包被截断或损坏，可能写进去一半才失败，
 * 把 {@code shared_prefs/} 与 {@code databases/} 覆盖成半截数据；而截断的 zip 往往不报错，
 * 只是少了末尾几条。清单让恢复能**先校验、后写入**，不通过就整体拒绝、一个字节都不写。</p>
 *
 * <p>清单本身作为 zip 的最后一个条目 {@link #ENTRY_NAME} 写入（导出时写数据顺便算好
 * size/CRC，无需二次读盘）。读取不到清单即视为 v1.146.0 之前的旧包，跳过强校验以向后兼容。</p>
 */
public final class BackupManifest {

    /** 清单在 zip 内的条目名；不在数据目录前缀白名单内，恢复写入时会自然跳过 */
    public static final String ENTRY_NAME = "dlc-manifest.json";

    /** 清单格式版本；字段不兼容变更时递增 */
    public static final int FORMAT = 1;

    private BackupManifest() {
    }

    /** 单个数据条目：zip 内路径 + 原始（未压缩）字节数 + CRC32 */
    public static final class Item {
        public final String path;
        public final long size;
        public final long crc;

        public Item(String path, long size, long crc) {
            this.path = path;
            this.size = size;
            this.crc = crc;
        }
    }

    /** 生成清单 JSON 文本 */
    public static String build(long createdAt, List<Item> items) {
        try {
            JSONObject o = new JSONObject();
            o.put("format", FORMAT);
            o.put("createdAt", createdAt);
            o.put("count", items == null ? 0 : items.size());
            JSONArray arr = new JSONArray();
            if (items != null) {
                for (Item it : items) {
                    arr.put(new JSONObject()
                            .put("path", it.path)
                            .put("size", it.size)
                            // CRC32 用 8 位十六进制，避免 JSON 数字在 double 下丢精度
                            .put("crc", String.format(java.util.Locale.US, "%08x", it.crc)));
                }
            }
            o.put("files", arr);
            return o.toString();
        } catch (JSONException e) {
            // 键全为常量，理论不可达
            throw new IllegalStateException(e);
        }
    }

    /**
     * 解析清单 JSON。
     *
     * @throws JSONException 格式非法（版本不支持 / 缺字段 / CRC 非十六进制）
     */
    public static List<Item> parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        int format = o.optInt("format", 0);
        if (format != FORMAT) throw new JSONException("不支持的清单版本: " + format);
        JSONArray arr = o.getJSONArray("files");
        List<Item> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject f = arr.getJSONObject(i);
            out.add(new Item(
                    f.getString("path"),
                    f.optLong("size", -1),
                    parseCrc(f.optString("crc", ""))));
        }
        return out;
    }

    private static long parseCrc(String hex) throws JSONException {
        if (hex == null || hex.isEmpty()) throw new JSONException("清单缺少 crc");
        try {
            return Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            throw new JSONException("crc 非法: " + hex);
        }
    }

    /**
     * 校验实际条目与清单**完全一致**：不缺失、不多出、size 与 CRC 全对。
     *
     * @param expected 清单声明的条目
     * @param actual   实际读出的条目，值为 {@code {size, crc}}（不含清单条目本身）
     * @throws IOException 任一不符
     */
    public static void verify(List<Item> expected, Map<String, long[]> actual) throws IOException {
        Map<String, Item> exp = new LinkedHashMap<>();
        for (Item it : expected) exp.put(it.path, it);

        for (Item it : expected) {
            long[] got = actual.get(it.path);
            if (got == null) {
                throw new IOException("备份缺少条目：" + it.path);
            }
            if (got[0] != it.size) {
                throw new IOException("备份条目大小不符：" + it.path);
            }
            if (got[1] != it.crc) {
                throw new IOException("备份条目已损坏：" + it.path);
            }
        }
        // 多出清单未声明的条目：会恢复出预期外的文件，同样拒绝
        for (String path : actual.keySet()) {
            if (!exp.containsKey(path)) {
                throw new IOException("备份含未声明条目：" + path);
            }
        }
    }
}
