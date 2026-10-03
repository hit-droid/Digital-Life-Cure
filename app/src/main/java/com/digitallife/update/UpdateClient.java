package com.digitallife.update;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 更新检查的网络与线程封装；解析与比较逻辑全在 {@link UpdateChecker}（纯逻辑、可单测）。
 *
 * <p>查的是本仓库公开的 GitHub Releases API，不需要 token。仅在「检查更新」被点击时调用，
 * 不做后台轮询——避免打扰与触发匿名 API 的速率限制。</p>
 */
public final class UpdateClient {

    private static final String TAG = "UpdateClient";
    private static final String API =
            "https://api.github.com/repos/hit-droid/Digital-Life-Cure/releases?per_page=15";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        /**
         * @param latest  最新版本；失败时为 null
         * @param isNewer 是否比本机版本新（latest 为 null 时无意义）
         * @param error   非空表示检查失败，可直接展示给用户
         */
        void onResult(UpdateChecker.Release latest, boolean isNewer, String error);
    }

    private UpdateClient() {
    }

    /** 后台线程拉取并比较，结果回调到主线程。必须在主线程调用。 */
    public static void check(final Context ctx, final Callback cb) {
        final Context app = ctx.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UpdateChecker.Release latest;
                try {
                    latest = UpdateChecker.pickLatest(httpGet(API), false);
                } catch (Exception e) {
                    Log.w(TAG, "check failed", e);
                    post(cb, null, false, friendly(e));
                    return;
                }
                if (latest == null) {
                    post(cb, null, false, "没有获取到可用版本");
                    return;
                }
                post(cb, latest, UpdateChecker.isNewer(currentVersion(app), latest.tag), null);
            }
        }, "update-check").start();
    }

    /** 本机版本名；取不到返回空串 */
    public static String currentVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static void post(final Callback cb, final UpdateChecker.Release r,
                             final boolean newer, final String error) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(r, newer, error);
            }
        });
    }

    private static String friendly(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? "网络不可用" : m;
    }

    /** 15s 超时、跟随重定向、512KB 上限 */
    private static String httpGet(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "DigitalLife-Android");
            int code = conn.getResponseCode();
            if (code == 403 || code == 429) {
                // GitHub 未认证请求按 IP 限流（60 次/小时）：别把 "HTTP 403" 甩给用户
                throw new java.io.IOException("请求过于频繁，请稍后再试");
            }
            if (code >= 400) throw new java.io.IOException("HTTP " + code);
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(chunk)) != -1 && total < 512 * 1024) {
                buf.write(chunk, 0, n);
                total += n;
            }
            in.close();
            return new String(buf.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
