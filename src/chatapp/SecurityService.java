package chatapp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class SecurityService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final Database db;
    private final AppConfig config;

    public SecurityService(Database db, AppConfig config) {
        this.db = db;
        this.config = config;
    }

    public String createSession(CurrentUser user) throws Exception {
        long expires = System.currentTimeMillis() + config.authSessionHours * 60L * 60L * 1000L;
        byte[] nonceBytes = new byte[18];
        RANDOM.nextBytes(nonceBytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String payload = user.username + "|" + expires + "|" + nonce;
        String signature = hmac(payload);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + signature;
        String hash = sha256(token);
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_sessions (username, token_hash, issued_at, expires_at, last_seen_at) VALUES (?, ?, NOW(), ?, NOW())")) {
            ps.setString(1, user.username);
            ps.setString(2, hash);
            ps.setTimestamp(3, java.sql.Timestamp.valueOf(LocalDateTime.now().plusHours(config.authSessionHours)));
            ps.executeUpdate();
        }
        return token;
    }

    public String validateToken(String token) throws Exception {
        if (token == null || token.isBlank() || !token.contains(".")) {
            return "";
        }
        String[] parts = token.split("\\.", 2);
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(hmac(payload).getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) {
            return "";
        }
        String[] fields = payload.split("\\|", 3);
        if (fields.length != 3 || Long.parseLong(fields[1]) < System.currentTimeMillis()) {
            return "";
        }
        String hash = sha256(token);
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT username FROM chat_sessions WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > NOW()")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return "";
                }
                touchSession(c, hash);
                return rs.getString("username");
            }
        }
    }

    public void revokeToken(String token) throws Exception {
        if (token == null || token.isBlank()) return;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE chat_sessions SET revoked_at = NOW() WHERE token_hash = ?")) {
            ps.setString(1, sha256(token));
            ps.executeUpdate();
        }
    }

    public void touchUser(String username) throws Exception {
        if (username == null || username.isBlank()) return;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE chat_sessions SET last_seen_at = NOW() WHERE username = ? AND revoked_at IS NULL AND expires_at > NOW()")) {
            ps.setString(1, username);
            ps.executeUpdate();
        }
    }

    public String generateTotpSecret() {
        byte[] data = new byte[20];
        RANDOM.nextBytes(data);
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                out.append(BASE32.charAt((buffer >> (bitsLeft - 5)) & 31));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            out.append(BASE32.charAt((buffer << (5 - bitsLeft)) & 31));
        }
        return out.toString();
    }

    public boolean verifyTotp(String secret, String code) {
        String normalized = code == null ? "" : code.trim();
        if (!normalized.matches("\\d{6}")) return false;
        long step = System.currentTimeMillis() / 30000L;
        for (long i = -1; i <= 1; i++) {
            if (totp(secret, step + i).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public String currentTotp(String secret) {
        return totp(secret, System.currentTimeMillis() / 30000L);
    }

    private void touchSession(Connection c, String hash) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE chat_sessions SET last_seen_at = NOW() WHERE token_hash = ?")) {
            ps.setString(1, hash);
            ps.executeUpdate();
        }
    }

    private String hmac(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(config.authTokenSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    private static String totp(String secret, long step) {
        try {
            byte[] key = base32Decode(secret);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0xf;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] base32Decode(String input) {
        String value = input == null ? "" : input.replace("=", "").replace(" ", "").toUpperCase();
        ByteBuffer buffer = ByteBuffer.allocate(value.length() * 5 / 8 + 5);
        int bits = 0;
        int bitCount = 0;
        for (char ch : value.toCharArray()) {
            int idx = BASE32.indexOf(ch);
            if (idx < 0) continue;
            bits = (bits << 5) | idx;
            bitCount += 5;
            if (bitCount >= 8) {
                buffer.put((byte) ((bits >> (bitCount - 8)) & 0xff));
                bitCount -= 8;
            }
        }
        byte[] out = new byte[buffer.position()];
        buffer.flip();
        buffer.get(out);
        return out;
    }
}
