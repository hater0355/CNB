package chatapp;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;

final class ReportService {
    private final Database db;

    ReportService(Database db) {
        this.db = db;
    }

    Path exportChatHistoryCsv(CurrentUser user, long conversationId, Path target) throws Exception {
        if (!canReadConversation(user, conversationId)) {
            throw new IllegalArgumentException("Bạn không có quyền export hội thoại này.");
        }
        ensureParent(target);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT m.id, m.created_at, m.sender_username, COALESCE(e.name, u.full_name, m.sender_username) sender_name, " +
                             "m.message_type, m.body, m.edited, m.recalled, m.pinned " +
                             "FROM chat_messages m " +
                             "LEFT JOIN users u ON u.username = m.sender_username " +
                             "LEFT JOIN employees e ON e.login_username = m.sender_username " +
                             "WHERE m.conversation_id = ? ORDER BY m.created_at ASC");
             BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            ps.setLong(1, conversationId);
            out.write("id,created_at,sender_username,sender_name,message_type,body,edited,recalled,pinned");
            out.newLine();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.write(csv(rs.getString("id")));
                    out.write(',');
                    out.write(csv(rs.getString("created_at")));
                    out.write(',');
                    out.write(csv(rs.getString("sender_username")));
                    out.write(',');
                    out.write(csv(rs.getString("sender_name")));
                    out.write(',');
                    out.write(csv(rs.getString("message_type")));
                    out.write(',');
                    out.write(csv(rs.getString("body")));
                    out.write(',');
                    out.write(csv(rs.getString("edited")));
                    out.write(',');
                    out.write(csv(rs.getString("recalled")));
                    out.write(',');
                    out.write(csv(rs.getString("pinned")));
                    out.newLine();
                }
            }
        }
        return target;
    }

    Path exportTaskReportCsv(CurrentUser user, Path target) throws Exception {
        ensureParent(target);
        String scopeSql = user.isAdmin()
                ? "c.company_owner = ?"
                : "(t.assignee_username = ? OR t.created_by = ?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT t.id, c.title conversation_title, t.title, t.assignee_username, t.created_by, " +
                             "t.status, t.priority, t.deadline, t.kpi_points, t.created_at, t.updated_at " +
                             "FROM chat_tasks t JOIN chat_conversations c ON c.id = t.conversation_id " +
                             "WHERE " + scopeSql + " ORDER BY t.deadline IS NULL, t.deadline ASC, t.updated_at DESC");
             BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            if (user.isAdmin()) {
                ps.setString(1, user.companyOwner);
            } else {
                ps.setString(1, user.username);
                ps.setString(2, user.username);
            }
            out.write("id,conversation,title,assignee,created_by,status,priority,deadline,kpi_points,created_at,updated_at");
            out.newLine();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.write(csv(rs.getString("id")));
                    out.write(',');
                    out.write(csv(rs.getString("conversation_title")));
                    out.write(',');
                    out.write(csv(rs.getString("title")));
                    out.write(',');
                    out.write(csv(rs.getString("assignee_username")));
                    out.write(',');
                    out.write(csv(rs.getString("created_by")));
                    out.write(',');
                    out.write(csv(rs.getString("status")));
                    out.write(',');
                    out.write(csv(rs.getString("priority")));
                    out.write(',');
                    out.write(csv(rs.getString("deadline")));
                    out.write(',');
                    out.write(csv(rs.getString("kpi_points")));
                    out.write(',');
                    out.write(csv(rs.getString("created_at")));
                    out.write(',');
                    out.write(csv(rs.getString("updated_at")));
                    out.newLine();
                }
            }
        }
        return target;
    }

    void rebuildDailyStats(LocalDate date) throws Exception {
        LocalDate targetDate = date == null ? LocalDate.now() : date;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_daily_stats (stat_date, company_owner, conversation_id, message_count, active_users) " +
                        "SELECT ?, cv.company_owner, m.conversation_id, COUNT(*), COUNT(DISTINCT m.sender_username) " +
                        "FROM chat_messages m JOIN chat_conversations cv ON cv.id = m.conversation_id " +
                        "WHERE DATE(m.created_at) = ? GROUP BY cv.company_owner, m.conversation_id " +
                        "ON DUPLICATE KEY UPDATE message_count = VALUES(message_count), active_users = VALUES(active_users)")) {
            ps.setDate(1, java.sql.Date.valueOf(targetDate));
            ps.setDate(2, java.sql.Date.valueOf(targetDate));
            ps.executeUpdate();
        }
    }

    private boolean canReadConversation(CurrentUser user, long conversationId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM chat_conversations c LEFT JOIN chat_members m ON m.conversation_id = c.id AND m.username = ? " +
                        "WHERE c.id = ? AND (m.username IS NOT NULL OR c.company_owner = ?) LIMIT 1")) {
            ps.setString(1, user.username);
            ps.setLong(2, conversationId);
            ps.setString(3, user.companyOwner);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    private static void ensureParent(Path target) throws Exception {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
