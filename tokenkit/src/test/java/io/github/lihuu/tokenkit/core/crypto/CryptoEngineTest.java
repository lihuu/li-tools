package io.github.lihuu.tokenkit.core.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.AEADBadTagException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CryptoEngineTest {

    @Test
    void shouldEncryptAndDecryptSuccessfully() throws GeneralSecurityException {
        byte[] key = CryptoEngine.generateRandomBytes(32);
        byte[] secret = "dummy-secret-payload-test-data".getBytes(StandardCharsets.UTF_8);

        EncryptedPayload payload = CryptoEngine.encrypt(secret, key);
        assertNotNull(payload);
        assertEquals(12, payload.nonce().length);
        assertTrue(payload.ciphertext().length > secret.length); // includes 16-byte auth tag

        byte[] decrypted = CryptoEngine.decrypt(payload.nonce(), payload.ciphertext(), key);
        assertArrayEquals(secret, decrypted);
    }

    @Test
    void shouldThrowAEADBadTagExceptionOnTampering() throws GeneralSecurityException {
        byte[] key = CryptoEngine.generateRandomBytes(32);
        byte[] secret = "critical-token-to-protect".getBytes(StandardCharsets.UTF_8);

        EncryptedPayload payload = CryptoEngine.encrypt(secret, key);
        byte[] corruptedCiphertext = payload.ciphertext().clone();
        corruptedCiphertext[0] ^= 0xFF; // flip bits

        assertThrows(AEADBadTagException.class, () ->
                CryptoEngine.decrypt(payload.nonce(), corruptedCiphertext, key)
        );
    }

    @Test
    void shouldThrowAEADBadTagExceptionOnWrongKey() throws GeneralSecurityException {
        byte[] key1 = CryptoEngine.generateRandomBytes(32);
        byte[] key2 = CryptoEngine.generateRandomBytes(32);
        byte[] secret = "secret-value".getBytes(StandardCharsets.UTF_8);

        EncryptedPayload payload = CryptoEngine.encrypt(secret, key1);

        assertThrows(AEADBadTagException.class, () ->
                CryptoEngine.decrypt(payload.nonce(), payload.ciphertext(), key2)
        );
    }

    @Test
    void shouldDeriveConsistentKeyWithPBKDF2() throws GeneralSecurityException {
        char[] password = "transfer-password-123".toCharArray();
        byte[] salt = CryptoEngine.generateRandomBytes(16);

        byte[] key1 = CryptoEngine.deriveKey(password, salt, 100_000, 256);
        byte[] key2 = CryptoEngine.deriveKey(password, salt, 100_000, 256);

        assertEquals(32, key1.length);
        assertArrayEquals(key1, key2);
    }

    @Test
    void shouldRejectInvalidKeyOrNonceLengths() {
        byte[] badKey = new byte[16]; // not 32 bytes
        byte[] secret = "test".getBytes();

        assertThrows(IllegalArgumentException.class, () -> CryptoEngine.encrypt(secret, badKey));

        byte[] validKey = new byte[32];
        byte[] badNonce = new byte[8]; // not 12 bytes
        assertThrows(IllegalArgumentException.class, () -> CryptoEngine.decrypt(badNonce, new byte[16], validKey));
    }
}
