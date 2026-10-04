package com.digitallife.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * PassphraseCrypto 纯逻辑单测：口令加解密往返、错误口令/篡改/格式非法均须拒绝。
 */
public class PassphraseCryptoTest {

    private static final char[] PASS = "correct horse battery staple".toCharArray();

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void encryptThenDecrypt_roundTrip() throws Exception {
        byte[] plain = bytes("对话记录 / 记忆 / 设置 —— 含中文与 emoji 🙂");
        byte[] blob = PassphraseCrypto.encrypt(PASS, plain);

        assertTrue("应识别为口令容器", PassphraseCrypto.isEncrypted(blob));
        assertNotEquals("密文不应等于明文", plain.length, blob.length);
        assertArrayEquals(plain, PassphraseCrypto.decrypt(PASS, blob));
    }

    @Test
    public void emptyPayload_roundTrip() throws Exception {
        assertArrayEquals(new byte[0], PassphraseCrypto.decrypt(PASS, PassphraseCrypto.encrypt(PASS, new byte[0])));
        // null 明文按空串处理
        assertArrayEquals(new byte[0], PassphraseCrypto.decrypt(PASS, PassphraseCrypto.encrypt(PASS, null)));
    }

    @Test
    public void largePayload_roundTrip() throws Exception {
        byte[] plain = new byte[256 * 1024];
        new java.util.Random(42).nextBytes(plain);
        assertArrayEquals(plain, PassphraseCrypto.decrypt(PASS, PassphraseCrypto.encrypt(PASS, plain)));
    }

    @Test
    public void encrypt_isNonDeterministic() throws Exception {
        // 每次随机 salt + iv，密文不应相同
        byte[] a = PassphraseCrypto.encrypt(PASS, bytes("same"));
        byte[] b = PassphraseCrypto.encrypt(PASS, bytes("same"));
        assertFalse(java.util.Arrays.equals(a, b));
    }

    @Test
    public void decrypt_withWrongPassphrase_throws() throws Exception {
        byte[] blob = PassphraseCrypto.encrypt(PASS, bytes("secret"));
        try {
            PassphraseCrypto.decrypt("wrong pass".toCharArray(), blob);
            fail("错误口令应解密失败");
        } catch (GeneralSecurityException expected) {
            // GCM tag 校验失败
        }
    }

    @Test
    public void decrypt_tamperedCiphertext_throws() throws Exception {
        byte[] blob = PassphraseCrypto.encrypt(PASS, bytes("secret"));
        blob[blob.length - 1] ^= 0x01; // 破坏 GCM tag
        try {
            PassphraseCrypto.decrypt(PASS, blob);
            fail("篡改后应校验失败");
        } catch (GeneralSecurityException expected) {
            // 预期
        }
    }

    @Test
    public void decrypt_tamperedSalt_throws() throws Exception {
        byte[] blob = PassphraseCrypto.encrypt(PASS, bytes("secret"));
        blob[PassphraseCrypto.MAGIC.length] ^= 0x01; // 改 salt 首字节 → 派生出的密钥不同
        try {
            PassphraseCrypto.decrypt(PASS, blob);
            fail("salt 被篡改应解密失败");
        } catch (GeneralSecurityException expected) {
            // 预期
        }
    }

    @Test
    public void decrypt_nonContainer_throws() {
        try {
            PassphraseCrypto.decrypt(PASS, bytes("just a plain zip"));
            fail("非容器应拒绝");
        } catch (GeneralSecurityException expected) {
            // 预期
        }
    }

    @Test
    public void decrypt_truncatedContainer_throws() {
        byte[] blob = new byte[PassphraseCrypto.MAGIC.length + 4]; // 只有 MAGIC + 少量字节
        System.arraycopy(PassphraseCrypto.MAGIC, 0, blob, 0, PassphraseCrypto.MAGIC.length);
        try {
            PassphraseCrypto.decrypt(PASS, blob);
            fail("长度不足应拒绝");
        } catch (GeneralSecurityException expected) {
            // 预期
        }
    }

    @Test
    public void emptyPassphrase_isRejected() {
        try {
            PassphraseCrypto.encrypt(new char[0], bytes("x"));
            fail("空口令应拒绝加密");
        } catch (GeneralSecurityException expected) {
            // 预期
        }
        try {
            PassphraseCrypto.decrypt(null, bytes("x"));
            fail("null 口令应拒绝解密");
        } catch (GeneralSecurityException expected) {
            // 预期
        }
    }

    @Test
    public void isEncrypted_nullAndShort_areFalse() {
        assertFalse(PassphraseCrypto.isEncrypted(null));
        assertFalse(PassphraseCrypto.isEncrypted(new byte[]{'D'}));
        assertFalse(PassphraseCrypto.isEncrypted(bytes("plain")));
        assertEquals(4, PassphraseCrypto.MAGIC.length);
    }
}
