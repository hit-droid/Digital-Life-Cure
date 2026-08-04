package com.digitallife.util;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

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
        ArrayList<Message> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT role, content, emotion_tag, timestamp FROM raw_messages ORDER BY timestamp DESC, id DESC LIMIT ?",
                new String[]{String.valueOf(CONTEXT_LIMIT)});
        try {
            while (c.moveToNext()) {
                out.add(0, new Message(
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
        if (json != null && !json.isEmpty()) {
            try {
                JSONObject o = new JSONObject(json);
                JSONArray c = o.optJSONArray("context");
                long now = System.currentTimeMillis();
                if (c != null) {
                    for (int i = 0; i < c.length(); i++) {
                        JSONObject mo = c.getJSONObject(i);
                        insertRawMessage(
                                mo.optString("role"),
                                mo.optString("content"),
                                mo.optString("emotion_tag", null),
                                now + i
                        );
                    }
                }
                JSONArray events = o.optJSONArray("events");
                if (events != null) {
                    for (int i = 0; i < events.length(); i++) {
                        upsertFact("event", events.getString(i), 1.0, now + i);
                    }
                }
                JSONArray profile = o.optJSONArray("profile");
                if (profile != null) {
                    for (int i = 0; i < profile.length(); i++) {
                        upsertFact("profile", profile.getString(i), 1.0, now + i);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        sp.edit().remove(KEY_MEMORY).putBoolean(KEY_MIGRATED, true).apply();
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
