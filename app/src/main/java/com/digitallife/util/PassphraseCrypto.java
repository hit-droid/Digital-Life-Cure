package com.digitallife.util;

import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 口令加解密（PBKDF2 + AES/GCM），纯逻辑、不依赖 Android API，可在 JVM 单测。
 *
 * <p>与 {@link SecureCrypto} 的分工：{@code SecureCrypto} 的密钥放在 Android Keystore，
 * 是**设备绑定**的，只适合本机配置落盘（换了设备就解不开）；本类用**用户口令**现场派生
 * 密钥，产出的密文可拷贝到任意设备，用同一口令解开，用于「加密备份 / 跨机恢复」。</p>
 *
 * <p>容器格式：{@code MAGIC(4) | salt(16) | iv(12) | ciphertext+tag}。MAGIC 兼作格式版本号；
 * 口令错误或密文被篡改时 GCM tag 校验失败，统一抛 {@link GeneralSecurityException}。</p>
 */
public final class PassphraseCrypto {

    /** 容器魔数，同时充当格式版本号（改格式时递增末位） */
    public static final byte[] MAGIC = {'D', 'L', 'P', '1'};

    private static final int SALT_LEN = 16;
    private static final int IV_LEN = 12;   // GCM 推荐 96 bit
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;
    /** PBKDF2 迭代次数：兼顾低端机耗时与离线暴力破解成本 */
    private static final int ITERATIONS = 120_000;
    /** PBKDF2WithHmacSHA256 需 API 26+，为兼容 minSdk 21 用 HmacSHA1 */
    private static final String PBKDF2 = "PBKDF2WithHmacSHA1";
    private static final String TRANSFORM = "AES/GCM/NoPadding";

    private PassphraseCrypto() {
    }

    /** 该字节串是否为本类的口令加密容器（有 MAGIC 前缀即认为是） */
    public static boolean isEncrypted(byte[] blob) {
        if (blob == null || blob.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (blob[i] != MAGIC[i]) return false;
        }
        return true;
    }

    /**
     * 用口令加密。入参 {@code plain} 为 null 时按空串处理。
     *
     * @throws GeneralSecurityException 口令为空或底层算法不可用
     */
    public static byte[] encrypt(char[] passphrase, byte[] plain) throws GeneralSecurityException {
        requirePassphrase(passphrase);
        byte[] salt = new byte[SALT_LEN];
        byte[] iv = new byte[IV_LEN];
        SecureRandom rng = new SecureRandom();
        rng.nextBytes(salt);
        rng.nextBytes(iv);

        byte[] ct = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, iv)
                .doFinal(plain == null ? new byte[0] : plain);

        ByteArrayOutputStream out = new ByteArrayOutputStream(
                MAGIC.length + SALT_LEN + IV_LEN + ct.length);
        out.write(MAGIC, 0, MAGIC.length);
        out.write(salt, 0, salt.length);
        out.write(iv, 0, iv.length);
        out.write(ct, 0, ct.length);
        return out.toByteArray();
    }

    /**
     * 用口令解密。
     *
     * @throws GeneralSecurityException 口令为空 / 格式非法 / 口令错误 / 密文被篡改
     */
    public static byte[] decrypt(char[] passphrase, byte[] blob) throws GeneralSecurityException {
        requirePassphrase(passphrase);
        if (!isEncrypted(blob)) {
            throw new GeneralSecurityException("not a passphrase container");
        }
        int off = MAGIC.length;
        if (blob.length < off + SALT_LEN + IV_LEN + 1) {
            throw new GeneralSecurityException("blob too short");
        }
        byte[] salt = Arrays.copyOfRange(blob, off, off + SALT_LEN);
        off += SALT_LEN;
        byte[] iv = Arrays.copyOfRange(blob, off, off + IV_LEN);
        off += IV_LEN;
        byte[] ct = Arrays.copyOfRange(blob, off, blob.length);
        return cipher(Cipher.DECRYPT_MODE, passphrase, salt, iv).doFinal(ct);
    }

    private static void requirePassphrase(char[] passphrase) throws GeneralSecurityException {
        if (passphrase == null || passphrase.length == 0) {
            throw new GeneralSecurityException("passphrase required");
        }
    }

    private static Cipher cipher(int mode, char[] passphrase, byte[] salt, byte[] iv)
            throws GeneralSecurityException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2);
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS);
        SecretKey derived;
        try {
            derived = factory.generateSecret(spec);
        } finally {
            spec.clearPassword();
        }
        SecretKey key = new SecretKeySpec(derived.getEncoded(), "AES");
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
        return c;
    }
}
