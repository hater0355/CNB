package chatapp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

final class AdminDashboardService {
    private final Database db;

    AdminDashboardService(Database db) {
        this.db = db;
    }

    List<String> auditLines(CurrentUser user, String actor, String action, LocalDate from, LocalDate to) throws Exception {
        if (!user.isAdmin()) {
            throw new IllegalArgumentException("Chỉ admin được xem audit log.");
        }
        List<String> lines = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
                "SELECT a.actor_username, a.action, a.target_type, a.target_id, a.created_at, COALESCE(a.detail, '') detail " +
                        "FROM chat_audit_logs a LEFT JOIN employees e ON e.login_username = a.actor_username " +
                        "WHERE (a.actor_username = ? OR e.account_username = ?)");
        List<String> params = new ArrayList<>();
        params.add(user.companyOwner);
        params.add(user.companyOwner);
        if (actor != null && !actor.isBlank()) {
            sql.append(" AND a.actor_username LIKE ?");
            params.add("%" + actor.trim() + "%");
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND a.action LIKE ?");
            params.add("%" + action.trim() + "%");
        }
        if (from != null) {
            sql.append(" AND DATE(a.created_at) >= ?");
            params.add(from.toString());
        }
        if (to != null) {
            sql.append(" AND DATE(a.created_at) <= ?");
            params.add(to.toString());
        }
        sql.append(" ORDER BY a.created_at DESC LIMIT 500");
        try (Connection c = db.getConnection()) {
            queryLines(c, lines, sql.toString(), params.toArray(String[]::new));
        }
        return lines;
    }

    AdminDashboard dashboard(CurrentUser user) throws Exception {
        if (!user.isAdmin()) {
            throw new IllegalArgumentException("Chỉ admin được xem dashboard quản trị.");
        }
        AdminDashboard d = new AdminDashboard();
        try (Connection c = db.getConnection()) {
            d.approvedUsers = scalar(c, "SELECT COUNT(*) FROM employees WHERE account_username = ? AND status = 'APPROVED'", user.companyOwner);
            d.pendingUsers = scalar(c, "SELECT COUNT(*) FROM employees WHERE account_username = ? AND status <> 'APPROVED'", user.companyOwner);
            d.conversations = scalar(c, "SELECT COUNT(*) FROM chat_conversations WHERE company_owner = ?", user.companyOwner);
            d.messages7d = scalar(c,
                    "SELECT COUNT(*) FROM chat_messages m JOIN chat_conversations cv ON cv.id = m.conversation_id " +
                            "WHERE cv.company_owner = ? AND m.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)",
                    user.companyOwner);
            d.overdueTasks = scalar(c,
                    "SELECT COUNT(*) FROM chat_tasks t JOIN chat_conversations cv ON cv.id = t.conversation_id " +
                            "WHERE cv.company_owner = ? AND t.status <> 'DONE' AND t.deadline IS NOT NULL AND t.deadline < NOW()",
                    user.companyOwner);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT cv.title, COUNT(m.id) total FROM chat_conversations cv " +
                            "LEFT JOIN chat_messages m ON m.conversation_id = cv.id AND m.created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) " +
                            "WHERE cv.company_owner = ? GROUP BY cv.id, cv.title ORDER BY total DESC LIMIT 5")) {
                ps.setString(1, user.companyOwner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        d.topConversations.add(rs.getString("title") + ": " + rs.getInt("total") + " tin/7 ngày");
                    }
                }
            }
            queryLines(c, d.users, "SELECT COALESCE(e.name, u.full_name, u.username) name, u.username, u.role, COALESCE(e.status, '') status " +
                    "FROM users u LEFT JOIN employees e ON e.login_username = u.username " +
                    "WHERE u.username = ? OR e.account_username = ? ORDER BY name LIMIT 200", user.companyOwner, user.companyOwner);
            queryLines(c, d.conversationLines, "SELECT c.title, c.type, COUNT(m.username) members FROM chat_conversations c " +
                    "LEFT JOIN chat_members m ON m.conversation_id = c.id WHERE c.company_owner = ? GROUP BY c.id, c.title, c.type ORDER BY c.updated_at DESC LIMIT 200", user.companyOwner);
            queryLines(c, d.auditLines, "SELECT a.actor_username, a.action, a.target_type, a.target_id, a.created_at FROM chat_audit_logs a " +
                    "LEFT JOIN employees e ON e.login_username = a.actor_username " +
                    "WHERE a.actor_username = ? OR e.account_username = ? ORDER BY a.created_at DESC LIMIT 200", user.companyOwner, user.companyOwner);
            queryLines(c, d.taskLines, "SELECT t.title, t.assignee_username, t.status, t.kpi_points, t.deadline FROM chat_tasks t " +
                    "JOIN chat_conversations c ON c.id = t.conversation_id WHERE c.company_owner = ? ORDER BY t.updated_at DESC LIMIT 200", user.companyOwner);
        }
        return d;
    }

    private void queryLines(Connection c, List<String> target, String sql, String... params) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setString(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<String> parts = new ArrayList<>();
                    for (int i = 1; i <= cols; i++) {
                        parts.add(String.valueOf(rs.getObject(i)));
                    }
                    target.add(String.join(" | ", parts));
                }
            }
        }
    }

    private int scalar(Connection c, String sql, String companyOwner) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, companyOwner);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}

final class AdminDashboard {
    int approvedUsers;
    int pendingUsers;
    int conversations;
    int messages7d;
    int overdueTasks;
    final List<String> topConversations = new ArrayList<>();
    final List<String> users = new ArrayList<>();
    final List<String> conversationLines = new ArrayList<>();
    final List<String> auditLines = new ArrayList<>();
    final List<String> taskLines = new ArrayList<>();
}
