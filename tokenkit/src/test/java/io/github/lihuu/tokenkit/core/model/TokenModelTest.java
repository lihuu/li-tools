package io.github.lihuu.tokenkit.core.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenModelTest {

    @Test
    void tokenModelShouldEnforceNonNullFieldsAndStoreProperties() {
        byte[] nonce = new byte[12];
        byte[] ciphertext = new byte[16];
        Token token = new Token(
                "token-uuid-1",
                "default",
                "OPENAI_API_KEY",
                "generic",
                nonce,
                ciphertext,
                "OpenAI LLM Key",
                "llm,prod",
                1893456000000L,
                1700000000000L,
                1700000000000L
        );

        assertEquals("token-uuid-1", token.id());
        assertEquals("default", token.profile());
        assertEquals("OPENAI_API_KEY", token.name());
        assertEquals("generic", token.kind());
        assertEquals(12, token.nonce().length);
        assertEquals(16, token.ciphertext().length);
        assertEquals("OpenAI LLM Key", token.description());
        assertEquals("llm,prod", token.tags());
        assertEquals(1893456000000L, token.expiresAt());
        assertEquals(1700000000000L, token.createdAt());
        assertEquals(1700000000000L, token.updatedAt());
    }

    @Test
    void tokenModelShouldThrowWhenMandatoryFieldsAreNull() {
        assertThrows(IllegalArgumentException.class, () -> new Token(
                null, "default", "KEY", "generic", new byte[12], new byte[16], null, null, null, 0L, 0L
        ));
        assertThrows(IllegalArgumentException.class, () -> new Token(
                "id", null, "KEY", "generic", new byte[12], new byte[16], null, null, null, 0L, 0L
        ));
        assertThrows(IllegalArgumentException.class, () -> new Token(
                "id", "default", null, "generic", new byte[12], new byte[16], null, null, null, 0L, 0L
        ));
        assertThrows(IllegalArgumentException.class, () -> new Token(
                "id", "default", "KEY", "generic", null, new byte[16], null, null, null, 0L, 0L
        ));
        assertThrows(IllegalArgumentException.class, () -> new Token(
                "id", "default", "KEY", "generic", new byte[12], null, null, null, null, 0L, 0L
        ));
    }

    @Test
    void tokenMaskingShouldMaskAppropriately() {
        assertEquals("", Token.mask(""));
        assertEquals("****", Token.mask("abcd"));
        assertEquals("1••••6", Token.mask("123456"));
        assertEquals("sk-pr••••a8B3", Token.mask("sk-proj-1234567890a8B3"));
    }

    @Test
    void profileModelShouldStoreProperties() {
        Profile profile = new Profile("work", "Work credentials", true, 1700000000000L);
        assertEquals("work", profile.name());
        assertEquals("Work credentials", profile.description());
        assertTrue(profile.active());
        assertEquals(1700000000000L, profile.createdAt());

        assertThrows(IllegalArgumentException.class, () -> new Profile(null, null, false, 0L));
    }

    @Test
    void vaultMetadataShouldStoreKeyValue() {
        VaultMetadata meta = new VaultMetadata("schema_version", "1");
        assertEquals("schema_version", meta.key());
        assertEquals("1", meta.value());

        assertThrows(IllegalArgumentException.class, () -> new VaultMetadata(null, "1"));
        assertThrows(IllegalArgumentException.class, () -> new VaultMetadata("key", null));
    }
}
