package com.digitallife.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 纯逻辑的对称加解密（AES/GCM/NoPadding），不依赖 Android API，可在 JVM 单测。
 *
 * <p>密文格式：{@code enc:v1:<hex(iv || ciphertext+tag)>}，明文（旧数据 / 设备不支持加密时）
 * 没有前缀，读取时原样返回，因此兼容历史数据。hex 编码是手写的，避免依赖
 * {@code java.util.Base64}（API 26+）或 {@code android.util.Base64}（JVM 不可用）。
 */
public final class SecureCrypto {

    /** 密文前缀，同时充当格式版本号 */
    public static final String PREFIX = "enc:v1:";

    private static final int IV_LEN = 12;        // GCM 推荐 96 bit
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private SecureCrypto() {
    }

    /** stored 是否为加密串（有前缀即认为是；真正能否解开由 key 决定） */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** 加密为带前缀的字符串；入参 null 视为空串 */
    public static String encryptString(SecretKey key, String plain) throws GeneralSecurityException {
        if (key == null) throw new GeneralSecurityException("key is null");
        byte[] iv = new byte[IV_LEN];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] ct = cipher.doFinal((plain == null ? "" : plain).getBytes(StandardCharsets.UTF_8));

        byte[] blob = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, blob, 0, iv.length);
        System.arraycopy(ct, 0, blob, iv.length, ct.length);
        return PREFIX + toHex(blob);
    }

    /**
     * 解密。无前缀（旧明文）原样返回；有前缀但 key 为 null 或解不开时抛异常，
     * 交给调用方决定降级策略。
     */
    public static String decryptString(SecretKey key, String stored) throws GeneralSecurityException {
        if (stored == null || stored.isEmpty()) return "";
        if (!isEncrypted(stored)) return stored;
        if (key == null) throw new GeneralSecurityException("key is null");

        byte[] blob = fromHex(stored.substring(PREFIX.length()));
        if (blob.length <= IV_LEN) throw new GeneralSecurityException("blob too short");
        byte[] iv = Arrays.copyOfRange(blob, 0, IV_LEN);
        byte[] ct = Arrays.copyOfRange(blob, IV_LEN, blob.length);

        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    }

    /** 小写 hex 编码 */
    static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        char[] out = new char[bytes.length * 2];
        final char[] HEX = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** hex 解码；非法字符抛 IllegalArgumentException */
    static byte[] fromHex(String hex) {
        if (hex == null || hex.length() % 2 != 0) throw new IllegalArgumentException("bad hex");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
