package com.digitallife.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.ContentProvider;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;

/**
 * v1.189.0 {@link ShareFileProvider} 的回归。
 *
 * <p>这个提供器是对外暴露面（能被别的 App 通过 URI 读文件），所以重点测两件事：
 * 正常文件能读出来、**路径穿越必须被挡住**。</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ShareFileProviderTest {

    private Context ctx;
    private ShareFileProvider provider;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        provider = new ShareFileProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = ctx.getPackageName() + ShareFileProvider.AUTHORITY_SUFFIX;
        info.grantUriPermissions = true;
        info.exported = false;
        provider.attachInfo(ctx, info);
    }

    private File writeShareFile(String name, byte[] content) throws Exception {
        File dir = new File(ctx.getCacheDir(), ShareFileProvider.DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File f = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content);
        }
        return f;
    }

    @Test
    public void uriFor_buildsContentUri() {
        Uri uri = ShareFileProvider.uriFor(ctx, "msg_1.png");
        assertEquals("content", uri.getScheme());
        assertEquals(ctx.getPackageName() + ".share", uri.getAuthority());
        assertEquals("msg_1.png", uri.getLastPathSegment());
    }

    @Test
    public void openFile_readsSharedImage() throws Exception {
        byte[] data = new byte[]{1, 2, 3, 4, 5};
        writeShareFile("msg_ok.png", data);
        Uri uri = ShareFileProvider.uriFor(ctx, "msg_ok.png");
        android.os.ParcelFileDescriptor fd = provider.openFile(uri, "r");
        assertNotNull(fd);
        // 只读不关：Robolectric 的 ParcelFileDescriptor.close() 在 JDK17 下会触发
        // 「module java.base does not opens java.io」的反射限制，单测进程结束即回收
        assertEquals(data.length, fd.getStatSize());
    }

    @Test
    public void openFile_missingFile_throws() {
        Uri uri = ShareFileProvider.uriFor(ctx, "not_exist.png");
        try {
            provider.openFile(uri, "r");
            fail("不存在的文件应当抛 FileNotFoundException");
        } catch (java.io.FileNotFoundException expected) {
            // 正常
        }
    }

    @Test
    public void openFile_pathTraversal_isRejected() throws Exception {
        // 在 share 目录外放一个文件，尝试用 ../ 越界读它
        File secret = new File(ctx.getCacheDir(), "secret.txt");
        try (FileOutputStream fos = new FileOutputStream(secret)) {
            fos.write("secret".getBytes());
        }
        Uri evil = new Uri.Builder()
                .scheme("content")
                .authority(ctx.getPackageName() + ShareFileProvider.AUTHORITY_SUFFIX)
                .appendPath("../secret.txt")
                .build();
        try {
            provider.openFile(evil, "r");
            fail("路径穿越应当被拒绝");
        } catch (java.io.FileNotFoundException expected) {
            // 正常
        }
    }

    @Test
    public void getType_isPng() {
        assertEquals("image/png", provider.getType(ShareFileProvider.uriFor(ctx, "x.png")));
    }

    @Test
    public void writeOperations_areUnsupported() {
        Uri uri = ShareFileProvider.uriFor(ctx, "x.png");
        assertTrue(isUnsupported(() -> provider.insert(uri, null)));
        assertTrue(isUnsupported(() -> provider.delete(uri, null, null)));
        assertTrue(isUnsupported(() -> provider.update(uri, null, null, null)));
    }

    private interface Block {
        void run();
    }

    private static boolean isUnsupported(Block b) {
        try {
            b.run();
            return false;
        } catch (UnsupportedOperationException e) {
            return true;
        }
    }

    /** 只是确认 provider 能被系统正常创建（attachInfo 不抛异常） */
    @Test
    public void provider_onCreate_succeeds() {
        ContentProvider p = provider;
        assertNotNull(p);
        assertTrue(p.onCreate());
    }
}
