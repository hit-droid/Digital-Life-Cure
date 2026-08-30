package com.digitallife.tools;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 工具调用日志：持久化所有工具调用记录，供"开发者"页面查看。
 */
public class ToolUsageLog {

    private static final String PREF = "tool_usage_log";
    private static final String KEY_ENTRIES = "entries";
    private static final int MAX_ENTRIES = 500;

    public static class Entry {
        public long timestamp;
        public String toolName;
        public String args;
        public String result;
        public String error;
        public long durationMs;

        public JSONObject toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("ts", timestamp);
                o.put("tool", toolName);
                o.put("args", args != null ? args : "");
                o.put("result", result != null ? result : "");
                o.put("error", error != null ? error : "");
                o.put("duration", durationMs);
                return o;
            } catch (JSONException e) {
                return new JSONObject();
            }
        }

        public String formatTime() {
            return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date(timestamp));
        }
    }

    private final Context ctx;

    public ToolUsageLog(Context ctx) {
        this.ctx = ctx;
    }

    public void add(Entry e) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_ENTRIES, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            arr.put(e.toJson());
            // 限制大小
            while (arr.length() > MAX_ENTRIES) arr.remove(0);
            sp.edit().putString(KEY_ENTRIES, arr.toString()).apply();
        } catch (JSONException ex) {
            JSONArray arr = new JSONArray();
            arr.put(e.toJson());
            sp.edit().putString(KEY_ENTRIES, arr.toString()).apply();
        }
    }

    public List<Entry> all() {
        List<Entry> list = new ArrayList<>();
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String json = sp.getString(KEY_ENTRIES, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            // 倒序：最新在前
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Entry e = new Entry();
                e.timestamp = o.optLong("ts");
                e.toolName = o.optString("tool");
                e.args = o.optString("args");
                e.result = o.optString("result");
                e.error = o.optString("error");
                e.durationMs = o.optLong("duration");
                list.add(e);
            }
        } catch (JSONException ex) {
            // ignore
        }
        return list;
    }

    public void clear() {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        sp.edit().remove(KEY_ENTRIES).apply();
    }

    /**
     * 统计：今日调用次数。
     */
    public int countToday() {
        long startOfDay = startOfToday();
        int c = 0;
        for (Entry e : all()) {
            if (e.timestamp >= startOfDay) c++;
        }
        return c;
    }

    private long startOfToday() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
}
