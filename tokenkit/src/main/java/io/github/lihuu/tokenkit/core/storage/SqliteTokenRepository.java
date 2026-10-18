package io.github.lihuu.tokenkit.core.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.crypto.EncryptedPayload;
import io.github.lihuu.tokenkit.core.key.MasterKeyProvider;
import io.github.lihuu.tokenkit.core.model.Profile;
import io.github.lihuu.tokenkit.core.model.Token;
import io.github.lihuu.tokenkit.core.model.VaultMetadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class SqliteTokenRepository {
    private static final String CANARY_PLAINTEXT = "tokenkit-vault-canary-v1";
    private static final String SCHEMA_VERSION = "1";

    private final Path dbPath;
    private final MasterKeyProvider keyProvider;
    private final String jdbcUrl;

    public SqliteTokenRepository(@NotNull Path dbPath, @NotNull MasterKeyProvider keyProvider) {
        this.dbPath = Objects.requireNonNull(dbPath, "dbPath must not be null");
        this.keyProvider = Objects.requireNonNull(keyProvider, "keyProvider must not be null");
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
    }

    private Connection getConnection() throws SQLException {
        Path parent = dbPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new SQLException("Failed to create database directory: " + parent, e);
            }
        }
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA foreign_keys = ON;");
        }
        return connection;
    }

    public synchronized void initializeSchema() throws SQLException, GeneralSecurityException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS vault_metadata (
                            key         TEXT PRIMARY KEY,
                            value       TEXT NOT NULL
                        );
                        """);

                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS profiles (
                            name        TEXT PRIMARY KEY,
                            description TEXT,
                            is_active   INTEGER NOT NULL DEFAULT 0,
                            created_at  INTEGER NOT NULL
                        );
                        """);

                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS tokens (
                            id          TEXT PRIMARY KEY,
                            profile     TEXT NOT NULL DEFAULT 'default',
                            name        TEXT NOT NULL,
                            kind        TEXT NOT NULL DEFAULT 'generic',
                            nonce       BLOB NOT NULL,
                            ciphertext  BLOB NOT NULL,
                            description TEXT,
                            tags        TEXT,
                            expires_at  INTEGER,
                            created_at  INTEGER NOT NULL,
                            updated_at  INTEGER NOT NULL,
                            CONSTRAINT uq_profile_name UNIQUE (profile, name),
                            CONSTRAINT fk_tokens_profile FOREIGN KEY (profile) REFERENCES profiles(name) ON DELETE CASCADE
                        );
                        """);

                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tokens_profile_lookup ON tokens(profile, name);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tokens_tags ON tokens(tags);");

                // Ensure default profile exists
                stmt.execute("""
                        INSERT OR IGNORE INTO profiles (name, description, is_active, created_at)
                        VALUES ('default', 'Default profile', 1, unixepoch('subsec') * 1000);
                        """);

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }

        // Verify or create canary
        verifyOrCreateCanary();
    }

    private void verifyOrCreateCanary() throws SQLException, GeneralSecurityException {
        byte[] masterKey = keyProvider.getOrCreateMasterKey();
        Optional<String> canaryNonceHex = getMetadata("canary_nonce");
        Optional<String> canaryCiphertextHex = getMetadata("canary_ciphertext");

        if (canaryNonceHex.isPresent() && canaryCiphertextHex.isPresent()) {
            byte[] nonce = HexFormat.of().parseHex(canaryNonceHex.get());
            byte[] ciphertext = HexFormat.of().parseHex(canaryCiphertextHex.get());
            byte[] decrypted = CryptoEngine.decrypt(nonce, ciphertext, masterKey);
            String canaryString = new String(decrypted, StandardCharsets.UTF_8);
            if (!CANARY_PLAINTEXT.equals(canaryString)) {
                throw new GeneralSecurityException("Vault canary mismatch; master key is invalid or corrupted");
            }
        } else {
            // First initialization of canary
            EncryptedPayload payload = CryptoEngine.encrypt(
                    CANARY_PLAINTEXT.getBytes(StandardCharsets.UTF_8),
                    masterKey
            );
            saveMetadata("schema_version", SCHEMA_VERSION);
            saveMetadata("canary_nonce", HexFormat.of().formatHex(payload.nonce()));
            saveMetadata("canary_ciphertext", HexFormat.of().formatHex(payload.ciphertext()));
        }
    }

    public synchronized void saveMetadata(@NotNull String key, @NotNull String value) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO vault_metadata (key, value) VALUES (?, ?) " +
                             "ON CONFLICT(key) DO UPDATE SET value = excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    public synchronized Optional<String> getMetadata(@NotNull String key) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT value FROM vault_metadata WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getString("value"));
                }
                return Optional.empty();
            }
        }
    }

    public synchronized void saveToken(@NotNull Token token) throws SQLException {
        Objects.requireNonNull(token, "token must not be null");
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO tokens (id, profile, name, kind, nonce, ciphertext, description, tags, expires_at, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     ON CONFLICT(profile, name) DO UPDATE SET
                         kind = excluded.kind,
                         nonce = excluded.nonce,
                         ciphertext = excluded.ciphertext,
                         description = excluded.description,
                         tags = excluded.tags,
                         expires_at = excluded.expires_at,
                         updated_at = excluded.updated_at
                     """)) {
            ps.setString(1, token.id());
            ps.setString(2, token.profile());
            ps.setString(3, token.name());
            ps.setString(4, token.kind());
            ps.setBytes(5, token.nonce());
            ps.setBytes(6, token.ciphertext());
            ps.setString(7, token.description());
            ps.setString(8, token.tags());
            if (token.expiresAt() != null) {
                ps.setLong(9, token.expiresAt());
            } else {
                ps.setNull(9, java.sql.Types.INTEGER);
            }
            ps.setLong(10, token.createdAt());
            ps.setLong(11, token.updatedAt());
            ps.executeUpdate();
        }
    }

    public synchronized Optional<Token> findToken(@NotNull String profile, @NotNull String name) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, profile, name, kind, nonce, ciphertext, description, tags, expires_at, created_at, updated_at " +
                             "FROM tokens WHERE profile = ? AND name = ?")) {
            ps.setString(1, profile);
            ps.setString(2, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapToken(rs));
                }
                return Optional.empty();
            }
        }
    }

    public synchronized List<Token> findTokensByProfile(@NotNull String profile) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, profile, name, kind, nonce, ciphertext, description, tags, expires_at, created_at, updated_at " +
                             "FROM tokens WHERE profile = ? ORDER BY name ASC")) {
            ps.setString(1, profile);
            try (ResultSet rs = ps.executeQuery()) {
                List<Token> result = new ArrayList<>();
                while (rs.next()) {
                    result.add(mapToken(rs));
                }
                return result;
            }
        }
    }

    public synchronized List<Token> findAllTokens() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT id, profile, name, kind, nonce, ciphertext, description, tags, expires_at, created_at, updated_at " +
                             "FROM tokens ORDER BY profile ASC, name ASC")) {
            List<Token> result = new ArrayList<>();
            while (rs.next()) {
                result.add(mapToken(rs));
            }
            return result;
        }
    }

    public synchronized boolean deleteToken(@NotNull String profile, @NotNull String name) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM tokens WHERE profile = ? AND name = ?")) {
            ps.setString(1, profile);
            ps.setString(2, name);
            return ps.executeUpdate() > 0;
        }
    }

    public synchronized List<Profile> listProfiles() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name, description, is_active, created_at FROM profiles ORDER BY name ASC")) {
            List<Profile> result = new ArrayList<>();
            while (rs.next()) {
                result.add(new Profile(
                        rs.getString("name"),
                        rs.getString("description"),
                        rs.getInt("is_active") == 1,
                        rs.getLong("created_at")
                ));
            }
            return result;
        }
    }

    public synchronized Optional<Profile> findProfile(@NotNull String name) throws SQLException {
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT name, description, is_active, created_at FROM profiles WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(new Profile(
                            rs.getString("name"),
                            rs.getString("description"),
                            rs.getInt("is_active") == 1,
                            rs.getLong("created_at")
                    ));
                }
                return Optional.empty();
            }
        }
    }

    public synchronized void saveProfile(@NotNull Profile profile) throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (profile.active()) {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("UPDATE profiles SET is_active = 0;");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement("""
                        INSERT INTO profiles (name, description, is_active, created_at)
                        VALUES (?, ?, ?, ?)
                        ON CONFLICT(name) DO UPDATE SET
                            description = excluded.description,
                            is_active = excluded.is_active
                        """)) {
                    ps.setString(1, profile.name());
                    ps.setString(2, profile.description());
                    ps.setInt(3, profile.active() ? 1 : 0);
                    ps.setLong(4, profile.createdAt());
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public synchronized boolean setActiveProfile(@NotNull String name) throws SQLException {
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                // Check if profile exists
                try (PreparedStatement check = conn.prepareStatement("SELECT 1 FROM profiles WHERE name = ?")) {
                    check.setString(1, name);
                    try (ResultSet rs = check.executeQuery()) {
                        if (!rs.next()) {
                            conn.rollback();
                            return false;
                        }
                    }
                }
                // Reset active profile
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("UPDATE profiles SET is_active = 0;");
                }
                // Activate chosen profile
                try (PreparedStatement update = conn.prepareStatement("UPDATE profiles SET is_active = 1 WHERE name = ?")) {
                    update.setString(1, name);
                    update.executeUpdate();
                }
                conn.commit();
                return true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public synchronized @NotNull String getActiveProfileName() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM profiles WHERE is_active = 1 LIMIT 1")) {
            if (rs.next()) {
                return rs.getString("name");
            }
            return "default";
        }
    }

    public synchronized boolean deleteProfile(@NotNull String name) throws SQLException {
        if ("default".equals(name)) {
            throw new IllegalArgumentException("The 'default' profile cannot be deleted");
        }
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try {
                int affected;
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM profiles WHERE name = ?")) {
                    ps.setString(1, name);
                    affected = ps.executeUpdate();
                }
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT 1 FROM profiles WHERE is_active = 1")) {
                    if (!rs.next()) {
                        stmt.execute("UPDATE profiles SET is_active = 1 WHERE name = 'default'");
                    }
                }
                conn.commit();
                return affected > 0;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    public synchronized void backupTo(@NotNull Path destinationPath) throws SQLException {
        Objects.requireNonNull(destinationPath, "destinationPath must not be null");
        Path parent = destinationPath.getParent();
        if (parent != null && !Files.exists(parent)) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new SQLException("Failed to create backup target directory: " + parent, e);
            }
        }
        if (Files.exists(destinationPath)) {
            try {
                Files.delete(destinationPath);
            } catch (IOException e) {
                throw new SQLException("Failed to delete existing backup file at: " + destinationPath, e);
            }
        }

        String escapedPath = destinationPath.toAbsolutePath().toString().replace("'", "''");
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("VACUUM INTO '" + escapedPath + "';");
        }
    }

    private static Token mapToken(ResultSet rs) throws SQLException {
        long expiresAtVal = rs.getLong("expires_at");
        Long expiresAt = rs.wasNull() ? null : expiresAtVal;

        return new Token(
                rs.getString("id"),
                rs.getString("profile"),
                rs.getString("name"),
                rs.getString("kind"),
                rs.getBytes("nonce"),
                rs.getBytes("ciphertext"),
                rs.getString("description"),
                rs.getString("tags"),
                expiresAt,
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }
}
