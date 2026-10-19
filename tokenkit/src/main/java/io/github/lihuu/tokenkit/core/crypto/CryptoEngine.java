package io.github.lihuu.tokenkit.core.crypto;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.jetbrains.annotations.NotNull;

public final class CryptoEngine {
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String KDF_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_NONCE_LENGTH_BYTES = 12;
    private static final int AES_256_KEY_LENGTH_BYTES = 32;
    public static final int DEFAULT_PBKDF2_ITERATIONS = 100_000;
    public static final int DEFAULT_PBKDF2_KEY_LENGTH_BITS = 256;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private CryptoEngine() {
    }

    public static @NotNull byte[] generateRandomBytes(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be positive, got: " + length);
        }
        byte[] bytes = new byte[length];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static @NotNull EncryptedPayload encrypt(@NotNull byte[] plaintext, @NotNull byte[] masterKey)
            throws GeneralSecurityException {
        if (masterKey.length != AES_256_KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("Master key must be exactly 32 bytes for AES-256");
        }

        byte[] nonce = generateRandomBytes(GCM_NONCE_LENGTH_BYTES);
        SecretKey keySpec = new SecretKeySpec(masterKey, KEY_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
        byte[] ciphertext = cipher.doFinal(plaintext);

        return new EncryptedPayload(nonce, ciphertext);
    }

    public static @NotNull byte[] decrypt(@NotNull byte[] nonce, @NotNull byte[] ciphertext, @NotNull byte[] masterKey)
            throws GeneralSecurityException {
        if (masterKey.length != AES_256_KEY_LENGTH_BYTES) {
            throw new IllegalArgumentException("Master key must be exactly 32 bytes for AES-256");
        }
        if (nonce.length != GCM_NONCE_LENGTH_BYTES) {
            throw new IllegalArgumentException("GCM nonce must be exactly 12 bytes");
        }

        SecretKey keySpec = new SecretKeySpec(masterKey, KEY_ALGORITHM);
        GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce);

        Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
        return cipher.doFinal(ciphertext);
    }

    public static @NotNull byte[] deriveKey(@NotNull char[] passphrase, @NotNull byte[] salt)
            throws GeneralSecurityException {
        return deriveKey(passphrase, salt, DEFAULT_PBKDF2_ITERATIONS, DEFAULT_PBKDF2_KEY_LENGTH_BITS);
    }

    public static @NotNull byte[] deriveKey(@NotNull char[] passphrase, @NotNull byte[] salt, int iterations, int keyLengthBits)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, keyLengthBits);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGORITHM);
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
