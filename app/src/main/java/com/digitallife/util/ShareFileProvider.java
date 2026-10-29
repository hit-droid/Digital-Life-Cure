package com.digitallife.util;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * v1.190.0：分享消息长图用的最小文件提供器。
 *
 * <p>项目约定不引第三方依赖，因此没有用 {@code androidx.core.content.FileProvider}，
 * 而是自己实现 {@code openFile}——分享一张图只需要「按 URI 读出一个只读文件描述符」。</p>
 *
 * <p>安全约束：只暴露 {@code cacheDir/share} 下的**纯文件名**，拒绝任何路径分隔符，
 * 并二次校验解析后的真实路径仍在该目录内，避免路径穿越把别的文件读出去。
 * 写操作一律不支持。</p>
 */
public class ShareFileProvider extends ContentProvider {

    /** 缓存子目录名 */
    public static final String DIR = "share";

    /** provider authority 后缀，与 AndroidManifest 里的 ${applicationId}.share 对应 */
    public static final String AUTHORITY_SUFFIX = ".share";

    /** 拼出分享用的 content URI */
    public static Uri uriFor(Context ctx, String fileName) {
        return new Uri.Builder()
                .scheme("content")
                .authority(ctx.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(fileName)
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("provider 没有 Context");
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0 || name.contains("..")) {
            throw new FileNotFoundException("非法文件名: " + uri);
        }
        File dir = new File(ctx.getCacheDir(), DIR);
        File file = new File(dir, name);
        try {
            if (!file.exists() || !file.getCanonicalPath().startsWith(dir.getCanonicalPath())) {
                throw new FileNotFoundException("文件不存在或越界: " + name);
            }
        } catch (java.io.IOException e) {
            throw new FileNotFoundException("解析路径失败: " + name);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("只读提供器");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读提供器");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("只读提供器");
    }
}
