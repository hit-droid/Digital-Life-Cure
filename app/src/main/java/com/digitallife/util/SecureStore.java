package com.digitallife.util;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * 设备级密钥存储：密钥放 Android Keystore（不外泄、不随备份导出），
 * 加解密委托给纯逻辑的 {@link SecureCrypto}。
 *
 * <p>API &lt; 23 没有 Keystore AES，{@link #isSupported()} 返回 false，
 * 此时降级为明文（设置页会明确提示），不影响老机型可用性。
 * Keystore 的取密钥开销略大，密钥解析一次后静态缓存。
 */
public final class SecureStore {

    private static final String TAG = "SecureStore";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "dlc_secure_prefs_v1";

    private static volatile boolean resolved = false;
    private static volatile SecretKey cachedKey = null;

    /** 当前设备是否支持加密存储 */
    public boolean isSupported() {
        return key() != null;
    }

    private static SecretKey key() {
        if (resolved) return cachedKey;
        synchronized (SecureStore.class) {
            if (!resolved) {
                cachedKey = resolveKey();
                resolved = true;
            }
        }
        return cachedKey;
    }

    private static SecretKey resolveKey() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return kg.generateKey();
        } catch (Exception e) {
            Log.w(TAG, "Keystore 不可用，降级明文存储", e);
            return null;
        }
    }

    /** 加密；设备不支持时原样返回明文（调用方已在设置页明示） */
    public String encrypt(String plain) {
        if (plain == null) return "";
        SecretKey k = key();
        if (k == null) return plain;
        try {
            return SecureCrypto.encryptString(k, plain);
        } catch (Exception e) {
            Log.w(TAG, "加密失败，降级明文", e);
            return plain;
        }
    }

    /**
     * 解密；无前缀的旧明文原样返回；
     * 有前缀但解不开（如换机后 Keystore 密钥丢失）返回空串，让用户重新填写。
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (!SecureCrypto.isEncrypted(stored)) return stored;
        SecretKey k = key();
        if (k == null) return "";
        try {
            return SecureCrypto.decryptString(k, stored);
        } catch (Exception e) {
            Log.w(TAG, "解密失败，按空值处理（需重新填写密钥）", e);
            return "";
        }
    }
}
