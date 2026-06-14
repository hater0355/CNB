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

    Path exportChatHistoryHtml(CurrentUser user, long conversationId, Path target) throws Exception {
        if (!canReadConversation(user, conversationId)) {
            throw new IllegalArgumentException("Bạn không có quyền export hội thoại này.");
        }
        ensureParent(target);
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT m.created_at, m.sender_username, COALESCE(e.name, u.full_name, m.sender_username) sender_name, m.body, m.message_type " +
                             "FROM chat_messages m LEFT JOIN users u ON u.username = m.sender_username " +
                             "LEFT JOIN employees e ON e.login_username = m.sender_username WHERE m.conversation_id = ? ORDER BY m.created_at ASC");
             BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            ps.setLong(1, conversationId);
            writeHtmlStart(out, "Lịch sử chat");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.write("<section class='message'><div class='meta'>");
                    out.write(html(rs.getString("sender_name")) + " - " + html(rs.getString("created_at")) + " - " + html(rs.getString("message_type")));
                    out.write("</div><p>");
                    out.write(html(rs.getString("body")).replace("\n", "<br>"));
                    out.write("</p></section>");
                }
            }
            writeHtmlEnd(out);
        }
        return target;
    }

    Path exportTaskReportHtml(CurrentUser user, Path target) throws Exception {
        ensureParent(target);
        String scopeSql = user.isAdmin() ? "c.company_owner = ?" : "(t.assignee_username = ? OR t.created_by = ?)";
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT c.title conversation_title, t.title, t.assignee_username, t.created_by, t.status, t.priority, t.deadline, t.kpi_points " +
                             "FROM chat_tasks t JOIN chat_conversations c ON c.id = t.conversation_id WHERE " + scopeSql +
                             " ORDER BY t.deadline IS NULL, t.deadline ASC, t.updated_at DESC");
             BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            if (user.isAdmin()) {
                ps.setString(1, user.companyOwner);
            } else {
                ps.setString(1, user.username);
                ps.setString(2, user.username);
            }
            writeHtmlStart(out, "Báo cáo công việc");
            out.write("<table><thead><tr><th>Hội thoại</th><th>Việc</th><th>Người làm</th><th>Trạng thái</th><th>Ưu tiên</th><th>Hạn</th><th>KPI</th></tr></thead><tbody>");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.write("<tr><td>" + html(rs.getString("conversation_title")) + "</td><td>" + html(rs.getString("title")) + "</td><td>" +
                            html(rs.getString("assignee_username")) + "</td><td>" + html(rs.getString("status")) + "</td><td>" +
                            html(rs.getString("priority")) + "</td><td>" + html(rs.getString("deadline")) + "</td><td>" +
                            html(rs.getString("kpi_points")) + "</td></tr>");
                }
            }
            out.write("</tbody></table>");
            writeHtmlEnd(out);
        }
        return target;
    }

    Path exportEngagementReportHtml(CurrentUser user, Path target) throws Exception {
        if (!user.isAdmin()) {
            throw new IllegalArgumentException("Chỉ admin được export báo cáo tương tác.");
        }
        ensureParent(target);
        try (Connection c = db.getConnection();
             BufferedWriter out = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writeHtmlStart(out, "Báo cáo tương tác nhân viên");
            out.write("<h2>Tin nhắn theo nhân viên trong 30 ngày</h2>");
            out.write("<table><thead><tr><th>Nhân viên</th><th>Tài khoản</th><th>Số tin</th><th>Hội thoại tham gia</th></tr></thead><tbody>");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(e.name, u.full_name, m.sender_username) display_name, m.sender_username, COUNT(*) messages, COUNT(DISTINCT m.conversation_id) conversations " +
                            "FROM chat_messages m JOIN chat_conversations cv ON cv.id = m.conversation_id " +
                            "LEFT JOIN users u ON u.username = m.sender_username " +
                            "LEFT JOIN employees e ON e.login_username = m.sender_username " +
                            "WHERE cv.company_owner = ? AND m.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY) " +
                            "GROUP BY display_name, m.sender_username ORDER BY messages DESC")) {
                ps.setString(1, user.companyOwner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.write("<tr><td>" + html(rs.getString("display_name")) + "</td><td>" + html(rs.getString("sender_username")) + "</td><td>" +
                                html(rs.getString("messages")) + "</td><td>" + html(rs.getString("conversations")) + "</td></tr>");
                    }
                }
            }
            out.write("</tbody></table>");
            out.write("<h2>Task/KPI theo nhân viên</h2>");
            out.write("<table><thead><tr><th>Nhân viên</th><th>Tổng task</th><th>Đã xong</th><th>Quá hạn</th><th>KPI đã xong</th></tr></thead><tbody>");
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT t.assignee_username, COUNT(*) total_tasks, " +
                            "SUM(CASE WHEN t.status = 'DONE' THEN 1 ELSE 0 END) done_tasks, " +
                            "SUM(CASE WHEN t.status <> 'DONE' AND t.deadline IS NOT NULL AND t.deadline < NOW() THEN 1 ELSE 0 END) overdue_tasks, " +
                            "SUM(CASE WHEN t.status = 'DONE' THEN t.kpi_points ELSE 0 END) done_kpi " +
                            "FROM chat_tasks t JOIN chat_conversations c ON c.id = t.conversation_id " +
                            "WHERE c.company_owner = ? GROUP BY t.assignee_username ORDER BY done_kpi DESC, done_tasks DESC")) {
                ps.setString(1, user.companyOwner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.write("<tr><td>" + html(rs.getString("assignee_username")) + "</td><td>" + html(rs.getString("total_tasks")) +
                                "</td><td>" + html(rs.getString("done_tasks")) + "</td><td>" + html(rs.getString("overdue_tasks")) +
                                "</td><td>" + html(rs.getString("done_kpi")) + "</td></tr>");
                    }
                }
            }
            out.write("</tbody></table>");
            writeHtmlEnd(out);
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

    private static void writeHtmlStart(BufferedWriter out, String title) throws Exception {
        out.write("<!doctype html><html><head><meta charset='utf-8'><title>" + html(title) + "</title>");
        out.write("<style>body{font-family:Segoe UI,Arial,sans-serif;background:#0d1117;color:#f8fafc;padding:28px}h1{color:#93c5fd}.message{background:#161b26;border-radius:14px;padding:14px 16px;margin:12px 0}.meta{color:#94a3b8;font-size:12px}table{width:100%;border-collapse:collapse;background:#161b26;border-radius:14px;overflow:hidden}th,td{padding:10px;border-bottom:1px solid rgba(255,255,255,.08);text-align:left}th{color:#93c5fd}</style></head><body><h1>" + html(title) + "</h1>");
    }

    private static void writeHtmlEnd(BufferedWriter out) throws Exception {
        out.write("</body></html>");
    }

    private static String html(String value) {
        String safe = value == null ? "" : value;
        return safe.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void ensureParent(Path target) throws Exception {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
