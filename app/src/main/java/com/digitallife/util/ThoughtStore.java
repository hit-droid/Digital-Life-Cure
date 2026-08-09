package com.digitallife.util;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 内心独白存储（发现页「内心想法」数据源）。
 *
 * 桌宠 L3 心理独白引擎（ThoughtLoopManager）产出的内心独白在此落库，
 * 供用户随时翻看模型"心里在想什么"。
 */
public class ThoughtStore {

    private static final String DB_NAME = "pet_thoughts.db";
    private static final int DB_VERSION = 1;
    private static final int MAX_THOUGHTS = 300;

    public static class Thought {
        public final long id;
        public final long timestamp;
        public final String text;

        public Thought(long id, long timestamp, String text) {
            this.id = id;
            this.timestamp = timestamp;
            this.text = text;
        }
    }

    private final ThoughtDbHelper dbHelper;

    public ThoughtStore(Context context) {
        this.dbHelper = new ThoughtDbHelper(context.getApplicationContext());
    }

    public synchronized void addThought(String text) {
        if (text == null || text.trim().isEmpty()) return;
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("timestamp", System.currentTimeMillis());
        v.put("text", text.trim());
        db.insert("thoughts", null, v);
        db.execSQL("DELETE FROM thoughts WHERE id NOT IN (" +
                "SELECT id FROM thoughts ORDER BY timestamp DESC, id DESC LIMIT " + MAX_THOUGHTS + ")");
    }

    /** 取最近 N 条独白（时间正序返回，最新的在末尾） */
    public synchronized List<Thought> getRecentThoughts(int limit) {
        ArrayList<Thought> out = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id, timestamp, text FROM thoughts ORDER BY timestamp DESC, id DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, limit))});
        try {
            while (c.moveToNext()) {
                out.add(0, new Thought(c.getLong(0), c.getLong(1), c.getString(2)));
            }
        } finally {
            c.close();
        }
        return out;
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.delete("thoughts", null, null);
    }

    private static class ThoughtDbHelper extends SQLiteOpenHelper {
        ThoughtDbHelper(Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE thoughts (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "timestamp INTEGER NOT NULL," +
                    "text TEXT NOT NULL" +
                    ")");
            db.execSQL("CREATE INDEX idx_thought_ts ON thoughts(timestamp)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        }
    }
}
