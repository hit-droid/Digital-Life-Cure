package com.digitallife.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";
    private static final String CRASH_FILE = "digitallife_crash.log";
    /** 每次启动写入的分隔标记，用来界定「某一次启动之后」的崩溃内容 */
    private static final String MARK_START = "=== App Started ===";
    private static final String MARK_JAVA = "=== Java Crash Report ===";
    private static final String MARK_NATIVE = "=== Native Crash Report ===";
    private static final String MARK_LOAD_FAIL = "=== Native 库加载失败 ===";

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
            sCrashPath = crashFile(context).getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "无法定位崩溃日志路径", t);
        }

        // 先装崩溃处理器，再尝试加载 native 库。
        // 旧实现把 loadLibrary 放在装处理器之前、失败就 return：一旦 native 加载失败，
        // 既没有崩溃处理器也没有日志，用户只看到「点开闪退」，无从排查。
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler());

        String loadError = null;
        try {
            System.loadLibrary("maidendungeon");
        } catch (Throwable t) {
            loadError = Log.getStackTraceString(t);
        }

        if (loadError != null) {
            writeToFile(MARK_LOAD_FAIL + "\n"
                    + "Time: " + stamp() + "\n"
                    + "SDK: " + Build.VERSION.SDK_INT + "\n"
                    + "ABI: " + Arrays.toString(Build.SUPPORTED_ABIS) + "\n"
                    + loadError);
            return;
        }

        try {
            nativeInit(sCrashPath);
        } catch (Throwable t) {
            Log.e(TAG, "nativeInit 失败", t);
        }
        writeToFile(MARK_START + "\nTime: " + stamp());
    }

    public static String getCrashPath() {
        return sCrashPath;
    }

    /** 崩溃日志文件（外部私有目录不可用时退回内部目录） */
    public static File crashFile(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        return new File(dir, CRASH_FILE);
    }

    /**
     * 读取「上一次启动残留的崩溃信息」，供本次启动在界面上直接展示。
     * 只取最后一个启动标记之后的内容；没有崩溃返回 null。
     */
    public static String previousCrash(Context context) {
        File f = crashFile(context);
        if (!f.isFile()) return null;
        StringBuilder sb = new StringBuilder();
        try (FileReader r = new FileReader(f)) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        } catch (Exception e) {
            return null;
        }
        String all = sb.toString();
        int idx = all.lastIndexOf(MARK_START);
        String tail = idx >= 0 ? all.substring(idx + MARK_START.length()) : all;
        if (tail.contains(MARK_JAVA) || tail.contains(MARK_NATIVE) || tail.contains(MARK_LOAD_FAIL)) {
            return tail.trim();
        }
        return null;
    }

    /** 用户已查看后调用，避免每次启动都弹 */
    public static void clearPreviousCrash(Context context) {
        try {
            File f = crashFile(context);
            if (f.isFile() && !f.delete()) {
                //noinspection ResultOfMethodCallIgnored
                new FileWriter(f, false).close();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println(MARK_JAVA);
        pw.println("Time: " + stamp());
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
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            FileWriter fw = new FileWriter(file, true);
            fw.write(content);
            fw.write("\n---\n\n");
            fw.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to write crash log", e);
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static native void nativeInit(String crashPath);
}
