package chatapp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

final class ChatAuthService {
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(12);
    private final Database db;

    ChatAuthService(Database db) {
        this.db = db;
    }

    CurrentUser login(String username, String password) throws Exception {
        String sql = "SELECT username, password, role, full_name FROM users WHERE username = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String stored = rs.getString("password");
                if (!matches(password, stored)) {
                    return null;
                }
                if (!isBcrypt(stored)) {
                    upgradePassword(c, username, password);
                }

                String role = rs.getString("role");
                String fullName = rs.getString("full_name");
                if (isAdmin(role)) {
                    return new CurrentUser(username, fullName, "ADMIN", username, null, null, null);
                }
                return loadApprovedEmployee(c, username, fullName, role);
            }
        }
    }

    boolean twoFactorEnabled(String username) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT enabled FROM admin_2fa_secrets WHERE username = ?")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean("enabled");
            }
        }
    }

    String twoFactorSecret(String username) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT secret_base32 FROM admin_2fa_secrets WHERE username = ? AND enabled = TRUE")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("secret_base32") : "";
            }
        }
    }

    void saveTwoFactorSecret(String username, String secret) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO admin_2fa_secrets (username, secret_base32, enabled, created_at, updated_at) VALUES (?, ?, TRUE, NOW(), NOW()) " +
                        "ON DUPLICATE KEY UPDATE secret_base32 = VALUES(secret_base32), enabled = TRUE, updated_at = NOW()")) {
            ps.setString(1, username);
            ps.setString(2, secret);
            ps.executeUpdate();
        }
    }

    private CurrentUser loadApprovedEmployee(Connection c, String username, String fullName, String role) throws Exception {
        String sql = "SELECT id, name, account_username, department, position, status FROM employees WHERE login_username = ? ORDER BY ngay_vao_lam DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !"APPROVED".equalsIgnoreCase(rs.getString("status"))) {
                    return null;
                }
                return new CurrentUser(
                        username,
                        firstNonBlank(rs.getString("name"), fullName, username),
                        "EMPLOYEE",
                        rs.getString("account_username"),
                        rs.getString("id"),
                        rs.getString("department"),
                        rs.getString("position"));
            }
        }
    }

    private static boolean matches(String raw, String stored) {
        if (raw == null || stored == null || stored.isBlank()) {
            return false;
        }
        if (isBcrypt(stored)) {
            String normalized = stored.startsWith("{bcrypt}") ? stored.substring("{bcrypt}".length()) : stored;
            return ENCODER.matches(raw, normalized);
        }
        return raw.equals(stored);
    }

    private static boolean isBcrypt(String password) {
        return password != null && password.matches("^(\\{bcrypt\\})?\\$2[aby]\\$\\d{2}\\$.{53}$");
    }

    private void upgradePassword(Connection c, String username, String rawPassword) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("UPDATE users SET password = ? WHERE username = ?")) {
            ps.setString(1, ENCODER.encode(rawPassword));
            ps.setString(2, username);
            ps.executeUpdate();
        }
    }

    private static boolean isAdmin(String role) {
        return "ADMIN".equalsIgnoreCase(role) || "ROLE_ADMIN".equalsIgnoreCase(role);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
