package com.validator.util;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-128-CBC encryption utility for masking sensitive field values in the
 * downloadable encrypted dataset.  A fresh random IV is generated for every
 * value so identical plaintexts produce different ciphertexts.
 *
 * The output format is:   BASE64(IV) + ":" + BASE64(ciphertext)
 *
 * The fixed demo key is intentionally short and documented here; in a
 * production system this would be stored in a secrets manager.
 *
 * Performance: the key, the SecureRandom and the Cipher instance are created
 * once (Cipher per thread) instead of once per value, which matters when a
 * large CSV has hundreds of thousands of sensitive cells.
 */
public final class EncryptionUtil {

    private EncryptionUtil() {}

    /** 16-byte (128-bit) AES key - demo only. */
    private static final byte[] AES_KEY =
            "SFV@SecureKey128".getBytes(StandardCharsets.UTF_8); // exactly 16 chars

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";

    private static final SecretKeySpec KEY = new SecretKeySpec(AES_KEY, "AES");
    private static final SecureRandom  RNG = new SecureRandom();          // thread-safe
    private static final Base64.Encoder B64 = Base64.getEncoder();        // thread-safe

    private static final ThreadLocal<Cipher> CIPHER = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException("AES/CBC not available", e);
        }
    });

    /**
     * Encrypts a single plaintext value.
     *
     * @param plaintext the value to encrypt; null/blank is returned as-is.
     * @return encrypted token or the original value if blank.
     */
    public static String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[16];
            RNG.nextBytes(iv);

            Cipher cipher = CIPHER.get();
            cipher.init(Cipher.ENCRYPT_MODE, KEY, new IvParameterSpec(iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            return B64.encodeToString(iv) + ":" + B64.encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }
}
