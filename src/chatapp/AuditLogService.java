package chatapp;

import java.sql.Connection;
import java.sql.PreparedStatement;

final class AuditLogService {
    private final Database db;

    AuditLogService(Database db) {
        this.db = db;
    }

    void log(String actor, String action, String targetType, Object targetId, String detail) {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_audit_logs (actor_username, action, target_type, target_id, detail, created_at) VALUES (?, ?, ?, ?, ?, NOW())")) {
            ps.setString(1, actor);
            ps.setString(2, action);
            ps.setString(3, targetType);
            ps.setString(4, targetId == null ? null : String.valueOf(targetId));
            ps.setString(5, detail);
            ps.executeUpdate();
        } catch (Exception e) {
            AppLog.warn("Ghi audit log thất bại.", e);
        }
    }
}
