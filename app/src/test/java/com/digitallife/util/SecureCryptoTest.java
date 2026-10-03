package com.digitallife.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.security.GeneralSecurityException;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * SecureCrypto 纯逻辑单测：加解密正确性、格式前缀、篡改检测与 hex 编解码。
 */
public class SecureCryptoTest {

    private static SecretKey aesKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(128);
        return kg.generateKey();
    }

    @Test
    public void encryptThenDecrypt_roundTrip() throws Exception {
        SecretKey key = aesKey();
        String plain = "sk-abcdefghijklmnopqrstuvwxyz-0123456789";
        String enc = SecureCrypto.encryptString(key, plain);

        assertTrue("加密结果应带前缀", SecureCrypto.isEncrypted(enc));
        assertNotEquals("密文不应等于明文", plain, enc);
        assertEquals("解密应还原", plain, SecureCrypto.decryptString(key, enc));
    }

    @Test
    public void encrypt_emptyString_roundTrip() throws Exception {
        SecretKey key = aesKey();
        String enc = SecureCrypto.encryptString(key, "");
        assertEquals("", SecureCrypto.decryptString(key, enc));
    }

    @Test
    public void encrypt_nullTreatedAsEmpty() throws Exception {
        SecretKey key = aesKey();
        String enc = SecureCrypto.encryptString(key, null);
        assertEquals("", SecureCrypto.decryptString(key, enc));
    }

    @Test
    public void decrypt_plaintextLegacy_returnsAsIs() throws Exception {
        // 旧数据没有前缀，应原样返回（兼容迁移前的明文）
        SecretKey key = aesKey();
        assertEquals("legacy-plain-key", SecureCrypto.decryptString(key, "legacy-plain-key"));
        assertFalse(SecureCrypto.isEncrypted("legacy-plain-key"));
    }

    @Test
    public void decrypt_emptyOrNull_returnsEmpty() throws Exception {
        SecretKey key = aesKey();
        assertEquals("", SecureCrypto.decryptString(key, ""));
        assertEquals("", SecureCrypto.decryptString(key, null));
    }

    @Test
    public void encrypt_isNonDeterministic() throws Exception {
        // 每次随机 IV，密文不应相同
        SecretKey key = aesKey();
        assertNotEquals(SecureCrypto.encryptString(key, "same"),
                SecureCrypto.encryptString(key, "same"));
    }

    @Test
    public void decrypt_withWrongKey_throws() throws Exception {
        SecretKey k1 = aesKey();
        SecretKey k2 = aesKey();
        String enc = SecureCrypto.encryptString(k1, "secret");
        try {
            SecureCrypto.decryptString(k2, enc);
            fail("换密钥应解密失败");
        } catch (GeneralSecurityException expected) {
            // GCM tag 校验失败
        }
    }

    @Test
    public void decrypt_tamperedCiphertext_throws() throws Exception {
        SecretKey key = aesKey();
        String enc = SecureCrypto.encryptString(key, "secret");
        // 翻转最后一个 hex 字符，破坏 GCM tag
        char last = enc.charAt(enc.length() - 1);
        char flipped = last == '0' ? '1' : '0';
        String tampered = enc.substring(0, enc.length() - 1) + flipped;
        try {
            SecureCrypto.decryptString(key, tampered);
            fail("篡改后应校验失败");
        } catch (GeneralSecurityException expected) {
            // 预期
        }
    }

    @Test
    public void decrypt_keyNullButEncrypted_throws() throws Exception {
        String enc = SecureCrypto.encryptString(aesKey(), "secret");
        try {
            SecureCrypto.decryptString(null, enc);
            fail("key 为 null 时应抛异常");
        } catch (GeneralSecurityException expected) {
            // 预期
        }
    }

    @Test
    public void hex_roundTrip() {
        byte[] data = {0x00, 0x0f, (byte) 0xa5, (byte) 0xff, 0x10};
        String hex = SecureCrypto.toHex(data);
        assertEquals("000fa5ff10", hex);
        assertEquals(5, SecureCrypto.fromHex(hex).length);
        assertTrue(java.util.Arrays.equals(data, SecureCrypto.fromHex(hex)));
    }

    @Test
    public void fromHex_invalid_throws() {
        try {
            SecureCrypto.fromHex("0g");
            fail("非法 hex 应抛异常");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            SecureCrypto.fromHex("abc"); // 奇数长度
            fail("奇数长度应抛异常");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
