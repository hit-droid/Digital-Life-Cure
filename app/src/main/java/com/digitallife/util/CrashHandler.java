package com.digitallife.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String CRASH_FILE = "digitallife_crash.log";
    private static String sCrashPath;
    private static boolean sInited = false;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    private CrashHandler() {
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    public static void init(Context context) {
        if (sInited) return;
        sInited = true;

        try {
            System.loadLibrary("maidendungeon");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library", e);
            return;
        }

        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        sCrashPath = new File(dir, CRASH_FILE).getAbsolutePath();

        CrashHandler handler = new CrashHandler();
        Thread.setDefaultUncaughtExceptionHandler(handler);

        nativeInit(sCrashPath);

        writeToFile("=== App Started ===\nTime: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()));
    }

    public static String getCrashPath() {
        return sCrashPath;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("=== Java Crash Report ===");
        pw.println("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()));
        pw.println("Thread: " + thread.getName());
        pw.println();
        throwable.printStackTrace(pw);
        pw.flush();

        String crashLog = sw.toString();
        Log.e(TAG, crashLog);
        writeToFile(crashLog);

        if (defaultHandler != null) {
            defaultHandler.uncaughtException(thread, throwable);
        } else {
            System.exit(1);
        }
    }

    public static void writeToFile(String content) {
        if (sCrashPath == null) return;
        try {
            File file = new File(sCrashPath);
            file.getParentFile().mkdirs();
            FileWriter fw = new FileWriter(file, true);
            fw.write(content);
            fw.write("\n---\n\n");
            fw.close();
            Log.i(TAG, "Crash log written to " + sCrashPath);
        } catch (Exception e) {
            Log.e(TAG, "Failed to write crash log", e);
        }
    }

    private static native void nativeInit(String crashPath);
}