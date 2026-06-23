package chatapp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class TaskService {
    private final Database db;

    TaskService(Database db) {
        this.db = db;
    }

    long createTask(CurrentUser user, long conversationId, String title, String description, String assignee, String priority, LocalDateTime deadline, int kpi) throws Exception {
        if (!isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Tên công việc không được để trống.");
        }
        if (assignee == null || assignee.isBlank() || !isMember(conversationId, assignee)) {
            throw new IllegalArgumentException("Người thực hiện không thuộc hội thoại.");
        }
        String safePriority = normalizePriority(priority);
        if (kpi < 0) {
            throw new IllegalArgumentException("Điểm KPI không được âm.");
        }
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_tasks (conversation_id, title, description, assignee_username, created_by, status, priority, deadline, kpi_points, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'TODO', ?, ?, ?, NOW(), NOW())",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, conversationId);
            ps.setString(2, title.trim());
            ps.setString(3, description == null ? "" : description.trim());
            ps.setString(4, assignee);
            ps.setString(5, user.username);
            ps.setString(6, safePriority);
            if (deadline == null) {
                ps.setNull(7, java.sql.Types.TIMESTAMP);
            } else {
                ps.setTimestamp(7, Timestamp.valueOf(deadline));
            }
            ps.setInt(8, kpi);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    List<ChatTask> listTasksByConversation(CurrentUser user, long conversationId) throws Exception {
        if (!isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
        }
        List<ChatTask> tasks = new ArrayList<>();
        String sql = "SELECT t.*, COALESCE(e.name, u.full_name, t.assignee_username) assignee_name " +
                "FROM chat_tasks t " +
                "LEFT JOIN users u ON u.username = t.assignee_username " +
                "LEFT JOIN employees e ON e.login_username = t.assignee_username " +
                "WHERE t.conversation_id = ? " +
                "ORDER BY FIELD(t.status, 'TODO', 'IN_PROGRESS', 'REVIEW', 'DONE'), t.deadline IS NULL, t.deadline ASC, t.id DESC";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(mapTask(rs));
                }
            }
        }
        return tasks;
    }

    List<ChatTask> listTasksForUser(CurrentUser user) throws Exception {
        List<ChatTask> tasks = new ArrayList<>();
        String sql = "SELECT t.*, COALESCE(e.name, u.full_name, t.assignee_username) assignee_name " +
                "FROM chat_tasks t " +
                "JOIN chat_members m ON m.conversation_id = t.conversation_id AND m.username = ? " +
                "LEFT JOIN users u ON u.username = t.assignee_username " +
                "LEFT JOIN employees e ON e.login_username = t.assignee_username " +
                "WHERE t.assignee_username = ? OR t.created_by = ? " +
                "ORDER BY t.deadline IS NULL, t.deadline ASC, FIELD(t.status, 'TODO', 'IN_PROGRESS', 'REVIEW', 'DONE'), t.id DESC";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.username);
            ps.setString(2, user.username);
            ps.setString(3, user.username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(mapTask(rs));
                }
            }
        }
        return tasks;
    }

    int countOverdueTasks(CurrentUser user) throws Exception {
        String sql = "SELECT COUNT(*) FROM chat_tasks t JOIN chat_members m ON m.conversation_id = t.conversation_id AND m.username = ? " +
                "WHERE (t.assignee_username = ? OR t.created_by = ?) AND t.status <> 'DONE' AND t.deadline IS NOT NULL AND t.deadline < NOW()";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.username);
            ps.setString(2, user.username);
            ps.setString(3, user.username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    Set<Long> conversationIdsWithTasks(CurrentUser user) throws Exception {
        Set<Long> ids = new HashSet<>();
        String sql = "SELECT DISTINCT t.conversation_id FROM chat_tasks t JOIN chat_members m ON m.conversation_id = t.conversation_id AND m.username = ?";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    List<ChatTask> searchTasks(CurrentUser user, String query) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) {
            return listTasksForUser(user);
        }
        List<ChatTask> tasks = new ArrayList<>();
        String like = "%" + q + "%";
        String sql = "SELECT t.*, COALESCE(e.name, u.full_name, t.assignee_username) assignee_name " +
                "FROM chat_tasks t " +
                "JOIN chat_members m ON m.conversation_id = t.conversation_id AND m.username = ? " +
                "LEFT JOIN users u ON u.username = t.assignee_username " +
                "LEFT JOIN employees e ON e.login_username = t.assignee_username " +
                "WHERE (t.title LIKE ? OR t.description LIKE ? OR t.assignee_username LIKE ? OR COALESCE(e.name, u.full_name, '') LIKE ?) " +
                "ORDER BY t.deadline IS NULL, t.deadline ASC, t.id DESC LIMIT 100";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.username);
            ps.setString(2, like);
            ps.setString(3, like);
            ps.setString(4, like);
            ps.setString(5, like);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(mapTask(rs));
                }
            }
        }
        return tasks;
    }

    ChatTask updateTaskStatus(CurrentUser user, long taskId, String newStatus) throws Exception {
        String safeStatus = normalizeStatus(newStatus);
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                ChatTask task;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT t.*, COALESCE(e.name, u.full_name, t.assignee_username) assignee_name " +
                                "FROM chat_tasks t " +
                                "LEFT JOIN users u ON u.username = t.assignee_username " +
                                "LEFT JOIN employees e ON e.login_username = t.assignee_username " +
                                "WHERE t.id = ? FOR UPDATE")) {
                    ps.setLong(1, taskId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("Không tìm thấy công việc.");
                        }
                        task = mapTask(rs);
                    }
                }
                if (!isMember(task.conversationId, user.username)) {
                    throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
                }
                String oldStatus = task.status;
                try (PreparedStatement ps = c.prepareStatement("UPDATE chat_tasks SET status = ?, updated_at = NOW() WHERE id = ?")) {
                    ps.setString(1, safeStatus);
                    ps.setLong(2, taskId);
                    ps.executeUpdate();
                }
                task.status = safeStatus;
                task.updatedAt = LocalDateTime.now();
                if ("DONE".equals(safeStatus) && !"DONE".equals(oldStatus)) {
                    insertTaskDoneSystemMessage(c, task);
                }
                c.commit();
                return task;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private boolean isMember(long conversationId, String username) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM chat_members WHERE conversation_id = ? AND username = ?")) {
            ps.setLong(1, conversationId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static ChatTask mapTask(ResultSet rs) throws Exception {
        ChatTask task = new ChatTask();
        task.id = rs.getLong("id");
        task.conversationId = rs.getLong("conversation_id");
        task.title = rs.getString("title");
        task.description = rs.getString("description");
        task.assigneeUsername = rs.getString("assignee_username");
        task.assigneeName = rs.getString("assignee_name");
        task.createdBy = rs.getString("created_by");
        task.status = rs.getString("status");
        task.priority = rs.getString("priority");
        Timestamp deadline = rs.getTimestamp("deadline");
        task.deadline = deadline == null ? null : deadline.toLocalDateTime();
        task.kpiPoints = rs.getInt("kpi_points");
        Timestamp created = rs.getTimestamp("created_at");
        task.createdAt = created == null ? null : created.toLocalDateTime();
        Timestamp updated = rs.getTimestamp("updated_at");
        task.updatedAt = updated == null ? null : updated.toLocalDateTime();
        return task;
    }

    private void insertTaskDoneSystemMessage(Connection c, ChatTask task) throws Exception {
        String assignee = task.assigneeName == null || task.assigneeName.isBlank() ? task.assigneeUsername : task.assigneeName;
        String body = "🤖 [Hệ thống] @" + assignee + " đã hoàn thành công việc: '" + task.title + "' (+" + task.kpiPoints + " điểm KPI)";
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_messages (conversation_id, sender_username, body, message_type, created_at, updated_at) VALUES (?, 'system_bot', ?, 'TEXT', NOW(), NOW())")) {
            ps.setLong(1, task.conversationId);
            ps.setString(2, body);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE chat_conversations SET updated_at = NOW() WHERE id = ?")) {
            ps.setLong(1, task.conversationId);
            ps.executeUpdate();
        }
    }

    private static String normalizeStatus(String value) {
        String status = value == null ? "" : value.trim().toUpperCase();
        if (Set.of("TODO", "IN_PROGRESS", "REVIEW", "DONE").contains(status)) {
            return status;
        }
        throw new IllegalArgumentException("Trạng thái công việc không hợp lệ.");
    }

    private static String normalizePriority(String value) {
        String priority = value == null || value.isBlank() ? "MEDIUM" : value.trim().toUpperCase();
        if (Set.of("LOW", "MEDIUM", "HIGH").contains(priority)) {
            return priority;
        }
        throw new IllegalArgumentException("Độ ưu tiên không hợp lệ.");
    }

    private static long generatedId(PreparedStatement ps) throws Exception {
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new IllegalStateException("Missing generated id.");
            }
            return keys.getLong(1);
        }
    }
}
