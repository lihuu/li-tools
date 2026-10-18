package io.github.lihuu.tokenkit.core.migration;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import io.github.lihuu.tokenkit.core.crypto.CryptoEngine;
import io.github.lihuu.tokenkit.core.crypto.EncryptedPayload;
import org.jetbrains.annotations.NotNull;

public final class MigrationEngine {
    private static final byte[] MAGIC = "TKBUNDLE1".getBytes(StandardCharsets.US_ASCII);
    private static final int CURRENT_VERSION = 1;

    private MigrationEngine() {}

    public static byte[] packBundle(@NotNull MigrationBundle bundle, char @NotNull [] passphrase) throws Exception {
        Objects.requireNonNull(bundle, "bundle must not be null");
        Objects.requireNonNull(passphrase, "passphrase must not be null");

        String json = toJson(bundle);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        byte[] salt = CryptoEngine.generateRandomBytes(16);
        byte[] derivedKey = CryptoEngine.deriveKey(passphrase, salt);
        try {
            EncryptedPayload payload = CryptoEngine.encrypt(jsonBytes, derivedKey);
            byte[] ciphertext = payload.ciphertext();
            byte[] nonce = payload.nonce();

            ByteBuffer buffer = ByteBuffer.allocate(MAGIC.length + 16 + 12 + 4 + ciphertext.length);
            buffer.put(MAGIC);
            buffer.put(salt);
            buffer.put(nonce);
            buffer.putInt(ciphertext.length);
            buffer.put(ciphertext);
            return buffer.array();
        } finally {
            Arrays.fill(derivedKey, (byte) 0);
        }
    }

    public static MigrationBundle unpackBundle(byte @NotNull [] bundleBytes, char @NotNull [] passphrase) throws Exception {
        Objects.requireNonNull(bundleBytes, "bundleBytes must not be null");
        Objects.requireNonNull(passphrase, "passphrase must not be null");

        int minHeaderLen = MAGIC.length + 16 + 12 + 4;
        if (bundleBytes.length < minHeaderLen) {
            throw new IllegalArgumentException("Corrupted or incomplete bundle: file too small");
        }

        ByteBuffer buffer = ByteBuffer.wrap(bundleBytes);
        byte[] magicCheck = new byte[MAGIC.length];
        buffer.get(magicCheck);
        if (!Arrays.equals(magicCheck, MAGIC)) {
            throw new IllegalArgumentException("Invalid bundle file: missing TKBUNDLE1 magic header");
        }

        byte[] salt = new byte[16];
        buffer.get(salt);

        byte[] nonce = new byte[12];
        buffer.get(nonce);

        int cipherLen = buffer.getInt();
        if (cipherLen <= 0 || buffer.remaining() < cipherLen) {
            throw new IllegalArgumentException("Corrupted bundle: invalid payload length");
        }

        byte[] ciphertext = new byte[cipherLen];
        buffer.get(ciphertext);

        byte[] derivedKey = CryptoEngine.deriveKey(passphrase, salt);
        try {
            byte[] decryptedJson = CryptoEngine.decrypt(nonce, ciphertext, derivedKey);
            String json = new String(decryptedJson, StandardCharsets.UTF_8);
            return fromJson(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to decrypt bundle: invalid passphrase or corrupted file", e);
        } finally {
            Arrays.fill(derivedKey, (byte) 0);
        }
    }

    public static boolean isEncryptedBundle(byte @NotNull [] data) {
        if (data.length < MAGIC.length) {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    public static String toJson(@NotNull MigrationBundle bundle) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(bundle.version()).append(",\n");
        sb.append("  \"exportedAt\": ").append(bundle.exportedAt()).append(",\n");
        sb.append("  \"tokens\": [\n");

        List<MigrationBundle.ExportedToken> tokens = bundle.tokens();
        for (int i = 0; i < tokens.size(); i++) {
            MigrationBundle.ExportedToken t = tokens.get(i);
            sb.append("    {\n");
            sb.append("      \"profile\": \"").append(escapeJson(t.profile())).append("\",\n");
            sb.append("      \"name\": \"").append(escapeJson(t.name())).append("\",\n");
            sb.append("      \"kind\": \"").append(escapeJson(t.kind())).append("\",\n");
            sb.append("      \"value\": \"").append(escapeJson(t.value())).append("\",\n");
            sb.append("      \"description\": ").append(t.description() == null ? "null" : "\"" + escapeJson(t.description()) + "\"").append(",\n");
            sb.append("      \"tags\": ").append(t.tags() == null ? "null" : "\"" + escapeJson(t.tags()) + "\"").append(",\n");
            sb.append("      \"expiresAt\": ").append(t.expiresAt() == null ? "null" : t.expiresAt().toString()).append(",\n");
            sb.append("      \"createdAt\": ").append(t.createdAt()).append(",\n");
            sb.append("      \"updatedAt\": ").append(t.updatedAt()).append("\n");
            sb.append("    }");
            if (i < tokens.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    public static MigrationBundle fromJson(@NotNull String json) {
        Object parsed = new MiniJsonParser(json).parse();
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected JSON object at root");
        }

        int version = map.get("version") instanceof Number num ? num.intValue() : CURRENT_VERSION;
        long exportedAt = map.get("exportedAt") instanceof Number num ? num.longValue() : System.currentTimeMillis();

        List<MigrationBundle.ExportedToken> tokenList = new ArrayList<>();
        if (map.get("tokens") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> tokenMap) {
                    String profile = Objects.toString(tokenMap.get("profile"), "default");
                    String name = Objects.toString(tokenMap.get("name"), "");
                    String kind = Objects.toString(tokenMap.get("kind"), "generic");
                    String value = Objects.toString(tokenMap.get("value"), "");
                    String description = tokenMap.get("description") == null ? null : tokenMap.get("description").toString();
                    String tags = tokenMap.get("tags") == null ? null : tokenMap.get("tags").toString();
                    Long expiresAt = tokenMap.get("expiresAt") instanceof Number num ? num.longValue() : null;
                    long createdAt = tokenMap.get("createdAt") instanceof Number num ? num.longValue() : System.currentTimeMillis();
                    long updatedAt = tokenMap.get("updatedAt") instanceof Number num ? num.longValue() : System.currentTimeMillis();

                    tokenList.add(new MigrationBundle.ExportedToken(
                            profile, name, kind, value, description, tags, expiresAt, createdAt, updatedAt
                    ));
                }
            }
        }

        return new MigrationBundle(version, exportedAt, tokenList);
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static final class MiniJsonParser {
        private final String src;
        private int pos;

        MiniJsonParser(String src) {
            this.src = src;
            this.pos = 0;
        }

        Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            return value;
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            if (c == '-' || Character.isDigit(c)) return parseNumber();
            throw new IllegalArgumentException("Unexpected character at pos " + pos + ": " + c);
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                char next = peek();
                if (next == '}') {
                    pos++;
                    break;
                }
                expect(',');
            }
            return map;
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object val = parseValue();
                list.add(val);
                skipWhitespace();
                char next = peek();
                if (next == ']') {
                    pos++;
                    break;
                }
                expect(',');
            }
            return list;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new IllegalArgumentException("Unterminated escape sequence in string");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new IllegalArgumentException("Invalid unicode escape");
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean literal at pos " + pos);
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null literal at pos " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            boolean isFloating = false;
            if (pos < src.length() && src.charAt(pos) == '.') {
                isFloating = true;
                pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloating = true;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
            }
            String numStr = src.substring(start, pos);
            if (isFloating) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        }

        private char peek() {
            if (pos >= src.length()) return '\0';
            return src.charAt(pos);
        }

        private void expect(char expected) {
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + pos + " but found '" + peek() + "'");
            }
            pos++;
        }
    }
}
