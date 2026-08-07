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
    private static final int DB_VERSION = 1;
    private static final int MAX_MSG_PER_SESSION = 120;

    public static class StoredMsg {
        public final String role;
        public final String content;
        public final String toolCalls;   // JSON 数组字符串或 null
        public final String toolCallId;  // 或 null
        public final long timestamp;

        public StoredMsg(String role, String content, String toolCalls, String toolCallId, long timestamp) {
            this.role = role;
            this.content = content;
            this.toolCalls = toolCalls;
            this.toolCallId = toolCallId;
            this.timestamp = timestamp;
        }
    }

    private final ChatDbHelper dbHelper;

    public ChatStore(Context context) {
        this.dbHelper = new ChatDbHelper(context.getApplicationContext());
    }

    /** 添加一条消息到指定会话并裁剪上限 */
    public synchronized void addMessage(String sessionKey, String role, String content,
                                        JSONArray toolCalls, String toolCallId, long timestamp) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("session_key", sessionKey);
        v.put("role", role);
        v.put("content", content == null ? "" : content);
        v.put("tool_calls", toolCalls == null ? null : toolCalls.toString());
        v.put("tool_call_id", toolCallId);
        v.put("timestamp", timestamp);
        db.insert("chat_messages", null, v);
        trimSession(db, sessionKey);
    }

    /** 读取指定会话最近 N 条消息（按时间正序返回） */
    public synchronized List<StoredMsg> getMessages(String sessionKey, int limit) {
        ArrayList<StoredMsg> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT role, content, tool_calls, tool_call_id, timestamp FROM chat_messages " +
                        "WHERE session_key = ? ORDER BY timestamp DESC, id DESC LIMIT ?",
                new String[]{sessionKey, String.valueOf(Math.max(1, limit))});
        try {
            while (c.moveToNext()) {
                out.add(0, new StoredMsg(
                        c.getString(0),
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
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
