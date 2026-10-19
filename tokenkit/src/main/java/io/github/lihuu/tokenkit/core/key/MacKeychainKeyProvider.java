package io.github.lihuu.tokenkit.core.key;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import org.jetbrains.annotations.NotNull;

public final class MacKeychainKeyProvider implements MasterKeyProvider {
    public static final String DEFAULT_SERVICE = "li-tools.tokenkit";
    public static final String DEFAULT_ACCOUNT = "master-key";

    private static final Map<String, byte[]> KEY_CACHE = new ConcurrentHashMap<>();

    private final String service;
    private final String account;

    public MacKeychainKeyProvider() {
        this(DEFAULT_SERVICE, DEFAULT_ACCOUNT);
    }

    public MacKeychainKeyProvider(@NotNull String service, @NotNull String account) {
        this.service = Objects.requireNonNull(service, "service must not be null");
        this.account = Objects.requireNonNull(account, "account must not be null");
    }

    public static void clearCache() {
        KEY_CACHE.clear();
    }

    @Override
    public @NotNull byte[] getOrCreateMasterKey() throws GeneralSecurityException {
        String cacheKey = service + "::" + account;
        byte[] cached = KEY_CACHE.get(cacheKey);
        if (cached != null) {
            return cached.clone();
        }

        synchronized (KEY_CACHE) {
            cached = KEY_CACHE.get(cacheKey);
            if (cached != null) {
                return cached.clone();
            }

            try {
                Process findProcess = new ProcessBuilder(
                        "/usr/bin/security", "find-generic-password",
                        "-s", service,
                        "-a", account,
                        "-w"
                ).redirectErrorStream(true).start();

                String output;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(findProcess.getInputStream()))) {
                    output = reader.readLine();
                }
                int exitCode = findProcess.waitFor();

                if (exitCode == 0 && output != null && !output.trim().isEmpty()) {
                    byte[] key = HexFormat.of().parseHex(output.trim());
                    if (key.length == 32) {
                        KEY_CACHE.put(cacheKey, key.clone());
                        return key;
                    }
                }

                // Not found, generate and save
                byte[] newKey = CryptoEngine.generateRandomBytes(32);
                String hexKey = HexFormat.of().formatHex(newKey);

                Process addProcess = new ProcessBuilder(
                        "/usr/bin/security", "add-generic-password",
                        "-s", service,
                        "-a", account,
                        "-w", hexKey,
                        "-U"
                ).redirectErrorStream(true).start();

                int addExit = addProcess.waitFor();
                if (addExit != 0) {
                    throw new GeneralSecurityException("Failed to save master key to macOS Keychain (security exit code: " + addExit + ")");
                }

                KEY_CACHE.put(cacheKey, newKey.clone());
                return newKey;
            } catch (Exception e) {
                throw new GeneralSecurityException("macOS Keychain access failed: " + e.getMessage(), e);
            }
        }
    }
}
