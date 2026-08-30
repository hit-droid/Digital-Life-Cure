package com.digitallife.util;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 三层记忆存储：
 * 1) raw_messages：短期工作记忆
 * 2) daily_summaries：中期日常摘要
 * 3) facts：长期事实记忆
 */
public class MemoryStore {
    public static final int CONTEXT_LIMIT = 20;
    public static final int FACT_LIMIT = 50;
    private static final String DB_NAME = "pet_memory.db";
    private static final int DB_VERSION = 1;

    private static final String PREF_MEMORY = "pet_memory";
    private static final String KEY_MEMORY = "memory_json";
    private static final String KEY_MIGRATED = "sqlite_migrated_v1";

    private final MemoryDbHelper dbHelper;

    public static class Message {
        public final String role;
        public final String content;
        public final String emotionTag;
        public final long timestamp;

        public Message(String role, String content) {
            this(role, content, null, System.currentTimeMillis());
        }

        public Message(String role, String content, String emotionTag, long timestamp) {
            this.role = role;
            this.content = content;
            this.emotionTag = emotionTag;
            this.timestamp = timestamp;
        }
    }

    public static class DailySummary {
        public final long id;
        public final String date;
        public final String summary;
        public final String moodSummary;
        public final long createdAt;

        public DailySummary(long id, String date, String summary, String moodSummary, long createdAt) {
            this.id = id;
            this.date = date;
            this.summary = summary;
            this.moodSummary = moodSummary;
            this.createdAt = createdAt;
        }
    }

    public static class Fact {
        public final long id;
        public final String category;
        public final String content;
        public final double confidence;
        public final long lastConfirmed;

        public Fact(long id, String category, String content, double confidence, long lastConfirmed) {
            this.id = id;
            this.category = category;
            this.content = content;
            this.confidence = confidence;
            this.lastConfirmed = lastConfirmed;
        }
    }

    public MemoryStore(Context context) {
        Context appContext = context.getApplicationContext();
        this.dbHelper = new MemoryDbHelper(appContext);
        migrateFromLegacyPrefsIfNeeded(appContext);
    }

    public synchronized void addUserMessage(String text) {
        insertRawMessage("user", text, null, System.currentTimeMillis());
    }

    public synchronized void addAssistantMessage(String text) {
        insertRawMessage("assistant", text, null, System.currentTimeMillis());
    }

    public synchronized void addEvent(String event) {
        upsertFact("event", event, 1.0, System.currentTimeMillis());
    }

    public synchronized void addProfileFact(String fact) {
        upsertFact("profile", fact, 1.0, System.currentTimeMillis());
    }

    public synchronized List<Message> getContext() {
        return getRecentMessages(CONTEXT_LIMIT);
    }

    /**
     * v1.26.0：根据用户当前消息召回 topK 相关事实，返回拼接好的提示文本。
     * 用于 AICore/AgentBrain 注入 system prompt 的"相关记忆"段。
     * 无命中返回空字符串。
     */
    public synchronized String getRelatedFactsPrompt(String userQuery, int topK) {
        if (userQuery == null || userQuery.trim().isEmpty()) return "";
        List<Fact> related = retrieveRelatedFacts(userQuery, topK);
        if (related.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## 你记得可能跟当前对话相关的事（按相关度排序）\n");
        for (Fact f : related) {
            sb.append("- [").append(f.category).append("] ").append(f.content).append("\n");
        }
        return sb.toString();
    }

    /** 取最近 N 条对话消息（升序），供聊天界面渲染 */
    public synchronized List<Message> getRecentMessages(int limit) {
        ArrayList<Message> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT role, content, emotion_tag, timestamp FROM raw_messages ORDER BY timestamp DESC, id DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                out.add(0, new Message(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getLong(3)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized void clearContext() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete("raw_messages", null, null);
    }

    public synchronized List<String> getEvents() {
        return getFactContentsByCategory("event", 30);
    }

    public synchronized List<String> getProfile() {
        ArrayList<String> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT content FROM facts WHERE category IN ('profile', 'preference', 'habit', 'important_date', 'relationship') ORDER BY last_confirmed DESC, id DESC LIMIT 50",
                null);
        try {
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized List<DailySummary> getRecentSummaries(int limit) {
        ArrayList<DailySummary> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id, date, summary, mood_summary, created_at FROM daily_summaries ORDER BY created_at DESC, id DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                out.add(new DailySummary(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getLong(4)
                ));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized List<Fact> getRecentFacts(int limit) {
        ArrayList<Fact> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id, category, content, confidence, last_confirmed FROM facts ORDER BY last_confirmed DESC, id DESC LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                out.add(new Fact(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getDouble(3),
                        c.getLong(4)
                ));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized List<Fact> getAllFacts() {
        ArrayList<Fact> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id, category, content, confidence, last_confirmed FROM facts ORDER BY last_confirmed DESC, id DESC",
                null);
        try {
            while (c.moveToNext()) {
                out.add(new Fact(c.getLong(0), c.getString(1), c.getString(2), c.getDouble(3), c.getLong(4)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** v1.26.0：返回 facts 总数（用于触发摘要压缩） */
    public synchronized int countFacts() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM facts", null);
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            c.close();
        }
        return 0;
    }

    /** v1.26.0：取最老的 N 条事实（按 last_confirmed 升序） */
    public synchronized List<Fact> getOldestFacts(int n) {
        ArrayList<Fact> out = new ArrayList<>();
        if (n <= 0) return out;
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id, category, content, confidence, last_confirmed FROM facts ORDER BY last_confirmed ASC, id ASC LIMIT ?",
                new String[]{String.valueOf(n)});
        try {
            while (c.moveToNext()) {
                out.add(new Fact(c.getLong(0), c.getString(1), c.getString(2), c.getDouble(3), c.getLong(4)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /**
     * v1.26.0：基于关键词的轻量语义检索。
     * 流程：query → 提取中文/英文关键词（去停用词） → 扫 facts 表，
     *      按"命中关键词数 × confidence × 时间衰减"打分，取 topK。
     * 无关键词命中时返回空（调用方回退到 getAllFacts）。
     */
    public synchronized List<Fact> retrieveRelatedFacts(String query, int topK) {
        if (query == null || query.trim().isEmpty()) return new ArrayList<>();
        List<String> keywords = extractKeywords(query);
        if (keywords.isEmpty()) return new ArrayList<>();
        List<Fact> all = getAllFacts();
        if (all.isEmpty()) return new ArrayList<>();

        long now = System.currentTimeMillis();
        // 30 天前的 fact 衰减系数 0.3
        final long DAY_MS = 24L * 3600 * 1000;
        java.util.PriorityQueue<ScoredFact> heap = new java.util.PriorityQueue<>();
        for (Fact f : all) {
            int hits = 0;
            String lower = f.content == null ? "" : f.content.toLowerCase();
            for (String k : keywords) {
                if (k.isEmpty()) continue;
                if (lower.contains(k)) hits++;
            }
            if (hits == 0) continue;
            long ageDays = (now - f.lastConfirmed) / DAY_MS;
            double decay = ageDays > 30 ? 0.3 : Math.max(0.3, 1.0 - ageDays * 0.02);
            double score = hits * f.confidence * decay;
            heap.offer(new ScoredFact(f, score));
            if (heap.size() > topK) heap.poll();
        }
        ArrayList<ScoredFact> sorted = new ArrayList<>(heap);
        sorted.sort((a, b) -> Double.compare(b.score, a.score));
        ArrayList<Fact> out = new ArrayList<>();
        for (ScoredFact sf : sorted) out.add(sf.fact);
        return out;
    }

    /**
     * 轻量关键词提取：保留中文 2~6 字片段 + 英文单词（≥3 字符），去停用词。
     * 避免引入 HanLP/Jieba 等分词库，保持零依赖。
     */
    private static List<String> extractKeywords(String text) {
        ArrayList<String> kws = new ArrayList<>();
        String lower = text.toLowerCase();
        // 英文单词：连续 [a-z0-9] ≥3
        java.util.regex.Matcher en = java.util.regex.Pattern.compile("[a-z0-9]{3,}").matcher(lower);
        while (en.find()) {
            String w = en.group();
            if (!STOP_WORDS.contains(w)) kws.add(w);
        }
        // 中文 2~6 字片段
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                buf.append(c);
            } else {
                flushChinese(buf, kws);
            }
        }
        flushChinese(buf, kws);
        return kws;
    }

    private static void flushChinese(StringBuilder buf, List<String> out) {
        if (buf.length() == 0) return;
        String s = buf.toString();
        // 滑窗生成 2~6 字片段
        for (int len = 2; len <= Math.min(6, s.length()); len++) {
            for (int i = 0; i + len <= s.length(); i++) {
                String sub = s.substring(i, i + len);
                if (!STOP_WORDS.contains(sub)) out.add(sub);
            }
        }
        buf.setLength(0);
    }

    private static final java.util.Set<String> STOP_WORDS = new java.util.HashSet<>(java.util.Arrays.asList(
            // 英文
            "the", "and", "for", "are", "but", "not", "you", "all", "can", "had", "her", "was", "one", "our", "out",
            "this", "that", "with", "have", "from", "they", "been", "said", "what", "when", "make", "like", "him",
            "into", "time", "very", "than", "only", "know", "just", "also", "your", "over", "such", "more",
            // 中文常见停用词
            "的", "了", "是", "在", "我", "你", "他", "她", "它", "们", "和", "与", "或", "也", "都", "就",
            "把", "被", "从", "到", "给", "和", "很", "还", "可", "能", "让", "上", "下", "不", "没",
            "啊", "吗", "呢", "吧", "哦", "呀", "嗯", "啊", "啦", "哈"
    ));

    private static class ScoredFact {
        final Fact fact;
        final double score;
        ScoredFact(Fact fact, double score) { this.fact = fact; this.score = score; }
    }

    public synchronized List<DailySummary> getAllSummaries() {
        return getRecentSummaries(Integer.MAX_VALUE);
    }

    public synchronized void deleteFact(long id) {
        dbHelper.getWritableDatabase().delete("facts", "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized void updateFact(long id, String newContent) {
        if (newContent == null || newContent.trim().isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("content", newContent.trim());
        v.put("last_confirmed", System.currentTimeMillis());
        dbHelper.getWritableDatabase().update("facts", v, "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized void deleteSummary(long id) {
        dbHelper.getWritableDatabase().delete("daily_summaries", "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized void updateSummary(long id, String summary, String moodSummary) {
        if (summary == null || summary.trim().isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put("summary", summary.trim());
        if (moodSummary != null) v.put("mood_summary", moodSummary);
        dbHelper.getWritableDatabase().update("daily_summaries", v, "id = ?", new String[]{String.valueOf(id)});
    }

    public synchronized String exportJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("version", 1);
            JSONArray facts = new JSONArray();
            for (Fact f : getAllFacts()) {
                facts.put(new JSONObject()
                        .put("id", f.id)
                        .put("category", f.category)
                        .put("content", f.content)
                        .put("confidence", f.confidence)
                        .put("lastConfirmed", f.lastConfirmed));
            }
            JSONArray summaries = new JSONArray();
            for (DailySummary s : getAllSummaries()) {
                summaries.put(new JSONObject()
                        .put("id", s.id)
                        .put("date", s.date)
                        .put("summary", s.summary)
                        .put("moodSummary", s.moodSummary)
                        .put("createdAt", s.createdAt));
            }
            o.put("facts", facts);
            o.put("dailySummaries", summaries);
            return o.toString(2);
        } catch (Exception ex) {
            return "{}";
        }
    }

    public synchronized void importJson(String json) throws org.json.JSONException {
        JSONObject o = new JSONObject(json);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.beginTransaction();
        try {
            if (o.has("facts")) {
                JSONArray facts = o.getJSONArray("facts");
                for (int i = 0; i < facts.length(); i++) {
                    JSONObject f = facts.getJSONObject(i);
                    ContentValues v = new ContentValues();
                    v.put("category", f.optString("category", "profile"));
                    v.put("content", f.getString("content"));
                    v.put("confidence", f.optDouble("confidence", 1.0));
                    v.put("last_confirmed", f.optLong("lastConfirmed", System.currentTimeMillis()));
                    if (f.has("id")) {
                        v.put("id", f.getLong("id"));
                        db.insertWithOnConflict("facts", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                    } else {
                        db.insertWithOnConflict("facts", null, v, SQLiteDatabase.CONFLICT_IGNORE);
                    }
                }
            }
            if (o.has("dailySummaries")) {
                JSONArray summaries = o.getJSONArray("dailySummaries");
                for (int i = 0; i < summaries.length(); i++) {
                    JSONObject s = summaries.getJSONObject(i);
                    ContentValues v = new ContentValues();
                    v.put("date", s.optString("date", todayKey()));
                    v.put("summary", s.getString("summary"));
                    v.put("mood_summary", s.optString("moodSummary", ""));
                    v.put("created_at", s.optLong("createdAt", System.currentTimeMillis()));
                    if (s.has("id")) {
                        v.put("id", s.getLong("id"));
                        db.insertWithOnConflict("daily_summaries", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                    } else {
                        db.insert("daily_summaries", null, v);
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public synchronized String buildDebugSnapshot() {
        StringBuilder sb = new StringBuilder();
        List<Message> messages = getContext();
        List<DailySummary> summaries = getRecentSummaries(3);
        List<Fact> facts = getRecentFacts(10);
        sb.append("raw_messages=").append(messages.size()).append("\n");
        for (Message m : messages) {
            sb.append("- ").append(m.role).append(": ").append(m.content).append("\n");
        }
        sb.append("daily_summaries=").append(summaries.size()).append("\n");
        for (DailySummary s : summaries) {
            sb.append("- ").append(s.date).append(": ").append(s.summary);
            if (s.moodSummary != null && !s.moodSummary.trim().isEmpty()) {
                sb.append(" / mood=").append(s.moodSummary.trim());
            }
            sb.append("\n");
        }
        sb.append("facts=").append(facts.size()).append("\n");
        for (Fact f : facts) {
            sb.append("- [").append(f.category).append("] ").append(f.content).append("\n");
        }
        return sb.toString().trim();
    }

    public synchronized String runLocalSelfCheck() {
        long now = System.currentTimeMillis();
        insertRawMessage("user", "记忆系统自检消息", null, now);
        saveFact("self_check", "记忆系统自检事实", 1.0, now + 1);
        saveDailySummary(todayKey(), "记忆系统自检摘要", "稳定", now + 2);
        boolean messageOk = false;
        for (Message message : getContext()) {
            if ("记忆系统自检消息".equals(message.content)) {
                messageOk = true;
                break;
            }
        }
        boolean factOk = false;
        for (Fact fact : getRecentFacts(10)) {
            if ("记忆系统自检事实".equals(fact.content)) {
                factOk = true;
                break;
            }
        }
        boolean summaryOk = false;
        for (DailySummary summary : getRecentSummaries(5)) {
            if ("记忆系统自检摘要".equals(summary.summary)) {
                summaryOk = true;
                break;
            }
        }
        return "message=" + (messageOk ? "ok" : "fail")
                + ", fact=" + (factOk ? "ok" : "fail")
                + ", summary=" + (summaryOk ? "ok" : "fail");
    }

    public synchronized void saveDailySummary(String date, String summary, String moodSummary, long createdAt) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("date", date);
        values.put("summary", summary);
        values.put("mood_summary", moodSummary);
        values.put("created_at", createdAt);
        db.insert("daily_summaries", null, values);
    }

    public synchronized long getLastSummaryCreatedAt() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT created_at FROM daily_summaries ORDER BY created_at DESC, id DESC LIMIT 1", null);
        try {
            if (c.moveToFirst()) return c.getLong(0);
            return 0L;
        } finally {
            c.close();
        }
    }

    public synchronized boolean hasMessagesSince(long timestamp) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(1) FROM raw_messages WHERE timestamp > ?", new String[]{String.valueOf(timestamp)});
        try {
            return c.moveToFirst() && c.getInt(0) > 0;
        } finally {
            c.close();
        }
    }

    public synchronized List<Message> getMessagesSince(long timestamp) {
        ArrayList<Message> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT role, content, emotion_tag, timestamp FROM raw_messages WHERE timestamp > ? ORDER BY timestamp ASC, id ASC",
                new String[]{String.valueOf(timestamp)});
        try {
            while (c.moveToNext()) {
                out.add(new Message(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        c.getLong(3)
                ));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public String todayKey() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    public synchronized void saveFact(String category, String content, double confidence, long lastConfirmed) {
        upsertFact(category, content, confidence, lastConfirmed);
    }

    @Deprecated
    public synchronized String toJson() {
        try {
            JSONObject o = new JSONObject();
            JSONArray c = new JSONArray();
            for (Message m : getContext()) {
                c.put(new JSONObject()
                        .put("role", m.role)
                        .put("content", m.content)
                        .put("emotion_tag", m.emotionTag)
                        .put("timestamp", m.timestamp));
            }
            JSONArray e = new JSONArray();
            for (String s : getEvents()) e.put(s);
            JSONArray pf = new JSONArray();
            for (String s : getProfile()) pf.put(s);
            o.put("context", c);
            o.put("events", e);
            o.put("profile", pf);
            return o.toString();
        } catch (Exception ex) {
            return "{}";
        }
    }

    @Deprecated
    public static MemoryStore fromJson(String json) {
        throw new UnsupportedOperationException("MemoryStore.fromJson 已废弃，当前请使用 SQLite 构造函数并通过迁移逻辑导入旧数据");
    }

    private void insertRawMessage(String role, String content, String emotionTag, long timestamp) {
        if (content == null || content.trim().isEmpty()) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("role", role);
        values.put("content", content.trim());
        values.put("emotion_tag", emotionTag);
        values.put("timestamp", timestamp);
        db.insert("raw_messages", null, values);
        trimRawMessages(db);
    }

    private void upsertFact(String category, String content, double confidence, long lastConfirmed) {
        if (content == null || content.trim().isEmpty()) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("category", category);
        values.put("content", content.trim());
        values.put("confidence", confidence);
        values.put("last_confirmed", lastConfirmed);
        long rowId = db.insertWithOnConflict("facts", null, values, SQLiteDatabase.CONFLICT_IGNORE);
        if (rowId == -1) {
            ContentValues update = new ContentValues();
            update.put("category", category);
            update.put("confidence", confidence);
            update.put("last_confirmed", lastConfirmed);
            db.update("facts", update, "content = ?", new String[]{content.trim()});
        }
        trimFacts(db);
    }

    private List<String> getFactContentsByCategory(String category, int limit) {
        ArrayList<String> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT content FROM facts WHERE category = ? ORDER BY last_confirmed DESC, id DESC LIMIT ?",
                new String[]{category, String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
        } finally {
            c.close();
        }
        return out;
    }

    private void trimRawMessages(SQLiteDatabase db) {
        db.execSQL(
                "DELETE FROM raw_messages WHERE id NOT IN (SELECT id FROM raw_messages ORDER BY timestamp DESC, id DESC LIMIT " + CONTEXT_LIMIT + ")"
        );
    }

    private void trimFacts(SQLiteDatabase db) {
        db.execSQL(
                "DELETE FROM facts WHERE id NOT IN (SELECT id FROM facts ORDER BY last_confirmed DESC, id DESC LIMIT " + FACT_LIMIT + ")"
        );
    }

    private void migrateFromLegacyPrefsIfNeeded(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_MEMORY, Context.MODE_PRIVATE);
        if (sp.getBoolean(KEY_MIGRATED, false)) return;

        String json = sp.getString(KEY_MEMORY, "");
        boolean migratedOk = false;
        if (json != null && !json.isEmpty()) {
            try {
                JSONObject o = new JSONObject(json);
                long now = System.currentTimeMillis();
                // 用事务批量插入：避免每条 insertRawMessage 触发 trim 把早期消息丢掉。
                // 迁移完成后再统一 trim 一次到 CONTEXT_LIMIT。
                SQLiteDatabase db = dbHelper.getWritableDatabase();
                db.beginTransaction();
                try {
                    JSONArray c = o.optJSONArray("context");
                    if (c != null) {
                        for (int i = 0; i < c.length(); i++) {
                            JSONObject mo = c.getJSONObject(i);
                            String role = mo.optString("role");
                            String content = mo.optString("content");
                            String emotionTag = mo.optString("emotion_tag", null);
                            if (content == null || content.trim().isEmpty()) continue;
                            ContentValues cv = new ContentValues();
                            cv.put("role", role);
                            cv.put("content", content.trim());
                            cv.put("emotion_tag", emotionTag);
                            cv.put("timestamp", now + i);
                            db.insert("raw_messages", null, cv);
                        }
                    }
                    JSONArray events = o.optJSONArray("events");
                    if (events != null) {
                        for (int i = 0; i < events.length(); i++) {
                            String ev = events.optString(i, "");
                            if (ev == null || ev.trim().isEmpty()) continue;
                            ContentValues cv = new ContentValues();
                            cv.put("category", "event");
                            cv.put("content", ev.trim());
                            cv.put("confidence", 1.0);
                            cv.put("last_confirmed", now + i);
                            db.insertWithOnConflict("facts", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                        }
                    }
                    JSONArray profile = o.optJSONArray("profile");
                    if (profile != null) {
                        for (int i = 0; i < profile.length(); i++) {
                            String pf = profile.optString(i, "");
                            if (pf == null || pf.trim().isEmpty()) continue;
                            ContentValues cv = new ContentValues();
                            cv.put("category", "profile");
                            cv.put("content", pf.trim());
                            cv.put("confidence", 1.0);
                            cv.put("last_confirmed", now + i);
                            db.insertWithOnConflict("facts", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
                        }
                    }
                    db.setTransactionSuccessful();
                    migratedOk = true;
                } finally {
                    db.endTransaction();
                }
                // 迁移结束后统一 trim 一次（复用上面事务里的 db 句柄）
                trimRawMessages(db);
                trimFacts(db);
            } catch (Exception e) {
                // 迁移失败必须保留旧数据：不标记已迁移、不删除，下次启动重试
                Log.w("MemoryStore", "旧记忆迁移失败，保留旧数据等待重试", e);
            }
        } else {
            // 无旧数据，无需迁移
            migratedOk = true;
        }

        if (migratedOk) {
            sp.edit().remove(KEY_MEMORY).putBoolean(KEY_MIGRATED, true).apply();
        }
    }

    private static class MemoryDbHelper extends SQLiteOpenHelper {
        MemoryDbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE raw_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "role TEXT NOT NULL," +
                    "content TEXT NOT NULL," +
                    "emotion_tag TEXT," +
                    "timestamp INTEGER NOT NULL" +
                    ")");
            db.execSQL("CREATE TABLE daily_summaries (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "date TEXT NOT NULL," +
                    "summary TEXT NOT NULL," +
                    "mood_summary TEXT," +
                    "created_at INTEGER NOT NULL" +
                    ")");
            db.execSQL("CREATE TABLE facts (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "category TEXT NOT NULL," +
                    "content TEXT NOT NULL," +
                    "confidence REAL DEFAULT 1.0," +
                    "last_confirmed INTEGER NOT NULL," +
                    "UNIQUE(content)" +
                    ")");
            db.execSQL("CREATE INDEX idx_raw_messages_timestamp ON raw_messages(timestamp)");
            db.execSQL("CREATE INDEX idx_daily_summaries_created_at ON daily_summaries(created_at)");
            db.execSQL("CREATE INDEX idx_facts_last_confirmed ON facts(last_confirmed)");
            db.execSQL("CREATE INDEX idx_facts_category ON facts(category)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
