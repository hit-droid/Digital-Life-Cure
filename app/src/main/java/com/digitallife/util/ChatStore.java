package com.digitallife.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史持久化（豆包式会话存储）。
 *
 * 支撑护理大脑等 AI 界面的消息保存与恢复。按会话 key 隔离（如 "care"），
 * 每条消息含 role / content / tool_calls / tool_call_id / timestamp，
 * 可直接还原为 LLMClient.ChatMessage 参与多轮 function calling 上下文。
 */
public class ChatStore {

    /** 护理大脑会话键 */
    public static final String SESSION_CARE = "care";

    private static final String DB_NAME = "pet_chat.db";
    private static final int DB_VERSION = 2;
    private static final int MAX_MSG_PER_SESSION = 120;

    /** 会话类型 */
    public static final String TYPE_CARE = "care";     // 护理大脑（工具型）
    public static final String TYPE_CHAT = "chat";     // 对话大脑
    public static final String TYPE_MODEL = "model";   // 绑定指定模型

    public static class StoredMsg {
        public final long id;            // 数据库 rowid，用于按 id 删任意条
        public final String role;
        public final String content;
        public final String toolCalls;   // JSON 数组字符串或 null
        public final String toolCallId;  // 或 null
        public final long timestamp;

        public StoredMsg(long id, String role, String content, String toolCalls, String toolCallId, long timestamp) {
            this.id = id;
            this.role = role;
            this.content = content;
            this.toolCalls = toolCalls;
            this.toolCallId = toolCallId;
            this.timestamp = timestamp;
        }

        /** 旧构造（兼容历史调用与单测 helper）：id 默认 -1 */
        public StoredMsg(String role, String content, String toolCalls, String toolCallId, long timestamp) {
            this(-1L, role, content, toolCalls, toolCallId, timestamp);
        }
    }

    /** 会话元信息（对话列表展示用） */
    public static class SessionInfo {
        public final String id;
        public final String title;
        public final String type;        // TYPE_CARE / TYPE_CHAT / TYPE_MODEL
        public final String brainType;   // chat / care / model
        public final String modelName;   // 绑定模型名（type=model 时）
        public final long createdAt;
        public final long updatedAt;

        public SessionInfo(String id, String title, String type, String brainType,
                           String modelName, long createdAt, long updatedAt) {
            this.id = id;
            this.title = title;
            this.type = type;
            this.brainType = brainType;
            this.modelName = modelName;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }
    }

    private final ChatDbHelper dbHelper;

    public ChatStore(Context context) {
        this.dbHelper = new ChatDbHelper(context.getApplicationContext());
    }

    /** 添加一条消息到指定会话并裁剪上限 */
    public synchronized long addMessage(String sessionKey, String role, String content,
                                        JSONArray toolCalls, String toolCallId, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("session_key", sessionKey);
        v.put("role", role);
        v.put("content", content == null ? "" : content);
        v.put("tool_calls", toolCalls == null ? null : toolCalls.toString());
        v.put("tool_call_id", toolCallId);
        v.put("timestamp", timestamp);
        long rowId = db.insert("chat_messages", null, v);
        touchSession(db, sessionKey, timestamp);
        trimSession(db, sessionKey);
        return rowId;
    }

    // ==================== 会话元数据 ====================

    /** 确保会话存在（不存在则创建），用于内置会话注册 */
    public synchronized void ensureSession(String id, String title, String type,
                                           String brainType, String modelName) {
        if (getSession(id) != null) return;
        long now = System.currentTimeMillis();
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("id", id);
        v.put("title", title == null ? id : title);
        v.put("type", type);
        v.put("brain_type", brainType);
        v.put("model_name", modelName);
        v.put("created_at", now);
        v.put("updated_at", now);
        db.insert("sessions", null, v);
    }

    /** 新建会话，返回 session id */
    public synchronized String createSession(String title, String type, String brainType, String modelName) {
        String id = "s" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);
        ensureSession(id, title, type, brainType, modelName);
        return id;
    }

    /** 重命名会话（护理大脑等系统内置会话不允许改名） */
    public synchronized boolean renameSession(String sessionKey, String title) {
        if (SESSION_CARE.equals(sessionKey)) return false;
        if (title == null || title.trim().isEmpty()) return false;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("title", title.trim());
        return db.update("sessions", v, "id = ?", new String[]{sessionKey}) > 0;
    }

    public synchronized SessionInfo getSession(String sessionKey) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, title, type, brain_type, model_name, created_at, updated_at " +
                        "FROM sessions WHERE id = ?",
                new String[]{sessionKey});
        try {
            if (c.moveToFirst()) {
                return new SessionInfo(
                        c.getString(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getLong(5), c.getLong(6));
            }
        } finally {
            c.close();
        }
        return null;
    }

    /** 列出全部会话（护理大脑固定置顶，其余按最近更新时间降序） */
    public synchronized List<SessionInfo> getSessions() {
        ArrayList<SessionInfo> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, title, type, brain_type, model_name, created_at, updated_at " +
                        "FROM sessions ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, updated_at DESC",
                new String[]{SESSION_CARE});
        try {
            while (c.moveToNext()) {
                out.add(new SessionInfo(
                        c.getString(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getLong(5), c.getLong(6)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 删除会话及其全部消息 */
    public synchronized void deleteSession(String sessionKey) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete("chat_messages", "session_key = ?", new String[]{sessionKey});
        db.delete("sessions", "id = ?", new String[]{sessionKey});
    }

    /** 取某会话最后一条非工具消息（对话列表摘要用） */
    public synchronized StoredMsg getLastMessage(String sessionKey) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id, role, content, tool_calls, tool_call_id, timestamp FROM chat_messages " +
                "WHERE session_key = ? AND role IN ('user','assistant') AND content != '' " +
                "ORDER BY timestamp DESC, id DESC LIMIT 1", new String[]{sessionKey});
        try {
            if (c.moveToFirst()) {
                return new StoredMsg(c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getLong(5));
            }
        } finally {
            c.close();
        }
        return null;
    }

    /**
     * v1.29.0：删除并返回最后一条 assistant 消息（用于「重新生成」）。
     * 若最后一条不是 assistant 则返回 null 且不删除。
     */
    public synchronized StoredMsg deleteLastAssistantMessage(String sessionKey) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        Cursor c = db.rawQuery("SELECT id, role, content, tool_calls, tool_call_id, timestamp FROM chat_messages " +
                "WHERE session_key = ? ORDER BY timestamp DESC, id DESC LIMIT 1", new String[]{sessionKey});
        StoredMsg last = null;
        long id = -1;
        try {
            if (c.moveToFirst()) {
                id = c.getLong(0);
                last = new StoredMsg(c.getLong(0), c.getString(1), c.getString(2),
                        c.getString(3), c.getString(4), c.getLong(5));
            }
        } finally {
            c.close();
        }
        if (last == null || !"assistant".equals(last.role)) return null;
        db.delete("chat_messages", "id = ?", new String[]{String.valueOf(id)});
        return last;
    }

    private void touchSession(SQLiteDatabase db, String sessionKey, long ts) {
        ContentValues v = new ContentValues();
        v.put("updated_at", ts);
        db.update("sessions", v, "id = ?", new String[]{sessionKey});
    }

    /**
     * 按数据库 rowid 删除指定会话中的任意一条消息（「删除任意条」用）。
     * 加 session_key 约束，避免跨会话误删相同的 rowid。
     *
     * @return 实际删除的行数（0 表示未找到）
     */
    public synchronized int deleteMessage(String sessionKey, long msgId) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        return db.delete("chat_messages", "id = ? AND session_key = ?",
                new String[]{String.valueOf(msgId), sessionKey});
    }

    /** 读取指定会话最近 N 条消息（按时间正序返回） */
    public synchronized List<StoredMsg> getMessages(String sessionKey, int limit) {
        ArrayList<StoredMsg> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id, role, content, tool_calls, tool_call_id, timestamp FROM chat_messages " +
                        "WHERE session_key = ? ORDER BY timestamp DESC, id DESC LIMIT ?",
                new String[]{sessionKey, String.valueOf(Math.max(1, limit))});
        try {
            while (c.moveToNext()) {
                out.add(0, new StoredMsg(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getLong(5)
                ));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 清空指定会话历史 */
    public synchronized void clearSession(String sessionKey) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete("chat_messages", "session_key = ?", new String[]{sessionKey});
    }

    /** 清空全部会话（仅保留开场白由各界面重新写入） */
    public synchronized void clearAll() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete("chat_messages", null, null);
    }

    private void trimSession(SQLiteDatabase db, String sessionKey) {
        db.execSQL("DELETE FROM chat_messages WHERE session_key = ? AND id NOT IN (" +
                "SELECT id FROM chat_messages WHERE session_key = ? ORDER BY timestamp DESC, id DESC LIMIT "
                + MAX_MSG_PER_SESSION + ")", new String[]{sessionKey, sessionKey});
    }

    private static class ChatDbHelper extends SQLiteOpenHelper {
        ChatDbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE chat_messages (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "session_key TEXT NOT NULL," +
                    "role TEXT NOT NULL," +
                    "content TEXT," +
                    "tool_calls TEXT," +
                    "tool_call_id TEXT," +
                    "timestamp INTEGER NOT NULL" +
                    ")");
            db.execSQL("CREATE INDEX idx_chat_session_ts ON chat_messages(session_key, timestamp)");
            db.execSQL("CREATE TABLE sessions (" +
                    "id TEXT PRIMARY KEY," +
                    "title TEXT NOT NULL," +
                    "type TEXT NOT NULL," +
                    "brain_type TEXT NOT NULL," +
                    "model_name TEXT," +
                    "created_at INTEGER NOT NULL," +
                    "updated_at INTEGER NOT NULL" +
                    ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("CREATE TABLE IF NOT EXISTS sessions (" +
                        "id TEXT PRIMARY KEY," +
                        "title TEXT NOT NULL," +
                        "type TEXT NOT NULL," +
                        "brain_type TEXT NOT NULL," +
                        "model_name TEXT," +
                        "created_at INTEGER NOT NULL," +
                        "updated_at INTEGER NOT NULL" +
                        ")");
            }
        }
    }
}
