package chatapp;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ChatService {
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([A-Za-z0-9_.-]{2,64})");
    private final Database db;
    private final FileStorageService storage;

    ChatService(Database db, FileStorageService storage) {
        this.db = db;
        this.storage = storage;
    }

    void ensureCompanyConversation(CurrentUser user) throws Exception {
        long id = findCompanyConversation(user.companyOwner);
        if (id == 0) {
            try (Connection c = db.getConnection()) {
                id = insertConversation(c, user.companyOwner, "COMPANY", "Toan cong ty", "COMPANY", user.companyOwner, false);
            }
        }
        syncCompanyMembers(id, user.companyOwner);
    }

    long ensureDepartmentConversation(String companyOwner, String department) throws Exception {
        if (companyOwner == null || companyOwner.isBlank() || department == null || department.isBlank()) {
            return 0;
        }
        String directKey = "DEPT|" + companyOwner + "|" + department.trim();
        long id = findConversationByDirectKey(companyOwner, directKey);
        if (id == 0) {
            try (Connection c = db.getConnection()) {
                id = insertConversation(c, companyOwner, "DEPARTMENT", "Phòng " + department.trim(), directKey, companyOwner, false);
            }
        }
        syncDepartmentMembers(id, companyOwner, department.trim());
        return id;
    }

    List<Conversation> listConversations(CurrentUser user) throws Exception {
        ensureCompanyConversation(user);
        List<Conversation> list = new ArrayList<>();
        String sql = "SELECT c.* FROM chat_conversations c JOIN chat_members m ON c.id = m.conversation_id " +
                "WHERE m.username = ? AND c.company_owner = ? ORDER BY c.pinned DESC, c.updated_at DESC";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.username);
            ps.setString(2, user.companyOwner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Conversation conv = mapConversation(rs);
                    if ("DIRECT".equals(conv.type)) {
                        conv.title = directTitle(c, conv.id, user.username);
                    }
                    conv.lastMessage = lastMessage(c, conv.id);
                    conv.unreadCount = unreadCount(c, conv.id, user.username);
                    list.add(conv);
                }
            }
        }
        return list;
    }

    List<ChatUser> listCompanyUsers(CurrentUser user) throws Exception {
        Map<String, ChatUser> users = new LinkedHashMap<>();
        String adminSql = "SELECT u.username, u.full_name, u.role, (SELECT MAX(s.last_seen_at) FROM chat_sessions s WHERE s.username = u.username AND s.revoked_at IS NULL) last_seen_at FROM users u WHERE u.username = ?";
        String empSql = "SELECT u.username, COALESCE(e.name, u.full_name, u.username) display_name, u.role, e.position, " +
                "(SELECT MAX(s.last_seen_at) FROM chat_sessions s WHERE s.username = u.username AND s.revoked_at IS NULL) last_seen_at " +
                "FROM employees e JOIN users u ON u.username = e.login_username " +
                "WHERE e.account_username = ? AND e.status = 'APPROVED' ORDER BY display_name";
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(adminSql)) {
                ps.setString(1, user.companyOwner);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        users.put(rs.getString("username"), new ChatUser(rs.getString("username"), rs.getString("full_name"), rs.getString("role"), "Admin", timestampToLocalDateTime(rs.getTimestamp("last_seen_at"))));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(empSql)) {
                ps.setString(1, user.companyOwner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        users.put(rs.getString("username"), new ChatUser(rs.getString("username"), rs.getString("display_name"), rs.getString("role"), rs.getString("position"), timestampToLocalDateTime(rs.getTimestamp("last_seen_at"))));
                    }
                }
            }
        }
        return new ArrayList<>(users.values());
    }

    long openDirectConversation(CurrentUser user, String otherUsername) throws Exception {
        if (user.username.equals(otherUsername)) {
            throw new IllegalArgumentException("Không thể chat với chính mình.");
        }
        if (!companyUsernames(user).contains(otherUsername)) {
            throw new IllegalArgumentException("Người dùng không thuộc công ty.");
        }
        String key = directKey(user.username, otherUsername);
        long existing = findConversationByDirectKey(user.companyOwner, key);
        if (existing != 0) {
            ensureMember(existing, user.username, "MEMBER");
            ensureMember(existing, otherUsername, "MEMBER");
            return existing;
        }
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                long id = insertConversation(c, user.companyOwner, "DIRECT", "Chat", key, user.username, false);
                insertMember(c, id, user.username, "MEMBER");
                insertMember(c, id, otherUsername, "MEMBER");
                c.commit();
                return id;
            } catch (Exception e) {
                c.rollback();
                long duplicate = findConversationByDirectKey(user.companyOwner, key);
                if (duplicate != 0) return duplicate;
                throw e;
            }
        }
    }

    long createGroup(CurrentUser user, String title, List<String> members) throws Exception {
        if (!user.canManageGroups()) {
            throw new IllegalArgumentException("Chỉ Admin hoặc Trưởng phòng được tạo nhóm.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Tên nhóm không được để trống.");
        }
        Set<String> allowed = companyUsernames(user);
        Set<String> selected = new HashSet<>(members);
        selected.add(user.username);
        selected.retainAll(allowed);
        if (selected.size() < 2) {
            throw new IllegalArgumentException("Nhom can it nhat 2 thanh vien.");
        }
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                long id = insertConversation(c, user.companyOwner, "GROUP", title.trim(), null, user.username, false);
                for (String member : selected) {
                    insertMember(c, id, member, member.equals(user.username) ? "OWNER" : "MEMBER");
                }
                c.commit();
                return id;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    void updateGroup(CurrentUser user, long conversationId, String title, List<String> members) throws Exception {
        if (!user.canManageGroups()) {
            throw new IllegalArgumentException("Chỉ Admin hoặc Trưởng phòng được quản lý nhóm.");
        }
        Conversation conv = getConversation(conversationId);
        if (conv == null || !"GROUP".equals(conv.type) || !user.companyOwner.equals(conv.companyOwner)) {
            throw new IllegalArgumentException("Nhóm không hợp lệ.");
        }
        Set<String> allowed = companyUsernames(user);
        Set<String> selected = new HashSet<>(members);
        selected.add(user.username);
        selected.retainAll(allowed);
        if (selected.size() < 2) {
            throw new IllegalArgumentException("Nhom can it nhat 2 thanh vien.");
        }
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("UPDATE chat_conversations SET title = ?, updated_at = NOW() WHERE id = ?")) {
                    ps.setString(1, title == null || title.isBlank() ? conv.title : title.trim());
                    ps.setLong(2, conversationId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM chat_members WHERE conversation_id = ?")) {
                    ps.setLong(1, conversationId);
                    ps.executeUpdate();
                }
                for (String member : selected) {
                    insertMember(c, conversationId, member, member.equals(user.username) ? "OWNER" : "MEMBER");
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    List<String> listMemberUsernames(long conversationId) throws Exception {
        List<String> result = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT username FROM chat_members WHERE conversation_id = ?")) {
            ps.setLong(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(rs.getString(1));
            }
        }
        return result;
    }

    List<TaskTarget> listTaskTargets(CurrentUser user) throws Exception {
        List<TaskTarget> targets = new ArrayList<>();
        String sql = "SELECT id, name, login_username FROM employees WHERE account_username = ? AND status = 'APPROVED' ORDER BY name";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.companyOwner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    targets.add(new TaskTarget(rs.getString("id"), rs.getString("name"), rs.getString("login_username")));
                }
            }
        }
        return targets;
    }

    long createTask(CurrentUser user, long messageId, String employeeId, String description, LocalDate deadline) throws Exception {
        if (employeeId == null || employeeId.isBlank()) {
            throw new IllegalArgumentException("Chưa chọn nhân viên.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Nội dung công việc không được để trống.");
        }
        String allowedSql = "SELECT 1 FROM employees WHERE id = ? AND account_username = ? AND status = 'APPROVED'";
        try (Connection c = db.getConnection()) {
            try (PreparedStatement allowed = c.prepareStatement(allowedSql)) {
                allowed.setString(1, employeeId);
                allowed.setString(2, user.companyOwner);
                try (ResultSet rs = allowed.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Nhân viên không thuộc công ty hoặc chưa được duyệt.");
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tasks (message_id, employee_id, description, deadline, status, created_at) VALUES (?, ?, ?, ?, 'OPEN', NOW())",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, messageId);
                ps.setString(2, employeeId);
                ps.setString(3, description.trim());
                if (deadline == null) {
                    ps.setNull(4, java.sql.Types.DATE);
                } else {
                    ps.setDate(4, java.sql.Date.valueOf(deadline));
                }
                ps.executeUpdate();
                return generatedId(ps);
            }
        }
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
                while (rs.next()) tasks.add(mapTask(rs));
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

    int countPendingWorkflows(CurrentUser user) throws Exception {
        if (!user.canManageGroups()) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM chat_workflows w " +
                "JOIN chat_messages m ON m.id = w.message_id " +
                "JOIN chat_conversations c ON c.id = m.conversation_id " +
                "WHERE c.company_owner = ? AND w.status = 'PENDING'";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.companyOwner);
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
                while (rs.next()) ids.add(rs.getLong(1));
            }
        }
        return ids;
    }

    List<ChatTask> searchTasks(CurrentUser user, String query) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.isBlank()) return listTasksForUser(user);
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
                while (rs.next()) tasks.add(mapTask(rs));
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

    long createWorkflow(CurrentUser user, long conversationId, String workflowType, LocalDate workDate, String shift) throws Exception {
        if (user.employeeId == null || user.employeeId.isBlank()) {
            throw new IllegalArgumentException("Chỉ nhân viên mới tạo được yêu cầu workflow.");
        }
        if (workDate == null) {
            throw new IllegalArgumentException("Chưa chọn ngày.");
        }
        String safeType = "SHIFT_CHANGE".equals(workflowType) ? "SHIFT_CHANGE" : "OT";
        String safeShift = shift == null || shift.isBlank() ? ("OT".equals(safeType) ? "Tăng ca" : "Đổi ca") : shift.trim();
        String body = ("OT".equals(safeType) ? "Xin tăng ca" : "Xin đổi ca") + " ngày " + workDate + " - " + safeShift;
        String metadata = "{\"employeeId\":\"" + user.employeeId + "\",\"date\":\"" + workDate + "\",\"shift\":\"" + safeShift + "\"}";
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                long messageId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO chat_messages (conversation_id, sender_username, body, message_type, metadata_json, workflow_status, created_at, updated_at) " +
                                "VALUES (?, ?, ?, 'WORKFLOW_CARD', ?, 'PENDING', NOW(), NOW())",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, conversationId);
                    ps.setString(2, user.username);
                    ps.setString(3, body);
                    ps.setString(4, metadata);
                    ps.executeUpdate();
                    messageId = generatedId(ps);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO chat_workflows (message_id, workflow_type, employee_id, work_date, payload_json, status, created_at) VALUES (?, ?, ?, ?, ?, 'PENDING', NOW())")) {
                    ps.setLong(1, messageId);
                    ps.setString(2, safeType);
                    ps.setString(3, user.employeeId);
                    ps.setDate(4, java.sql.Date.valueOf(workDate));
                    ps.setString(5, metadata);
                    ps.executeUpdate();
                }
                c.commit();
                return messageId;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    long createBusinessWorkflow(CurrentUser user, long conversationId, String workflowType, LocalDate workDate, String content) throws Exception {
        if (user.employeeId == null || user.employeeId.isBlank()) {
            throw new IllegalArgumentException("Chỉ nhân viên mới tạo được yêu cầu workflow.");
        }
        if (workDate == null) {
            throw new IllegalArgumentException("Chưa chọn ngày.");
        }
        String safeType = switch (workflowType == null ? "" : workflowType) {
            case "SHIFT_CHANGE" -> "SHIFT_CHANGE";
            case "LEAVE" -> "LEAVE";
            default -> "OT";
        };
        String defaultContent = switch (safeType) {
            case "SHIFT_CHANGE" -> "Đổi ca";
            case "LEAVE" -> "Nghỉ phép";
            default -> "Tăng ca";
        };
        String safeContent = content == null || content.isBlank() ? defaultContent : content.trim();
        String workflowName = switch (safeType) {
            case "SHIFT_CHANGE" -> "Xin đổi ca";
            case "LEAVE" -> "Xin nghỉ phép";
            default -> "Xin tăng ca";
        };
        String body = workflowName + " ngày " + workDate + " - " + safeContent;
        String metadata = "{\"employeeId\":\"" + user.employeeId + "\",\"date\":\"" + workDate + "\",\"shift\":\"" + safeContent + "\"}";
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                long messageId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO chat_messages (conversation_id, sender_username, body, message_type, metadata_json, workflow_status, created_at, updated_at) " +
                                "VALUES (?, ?, ?, 'WORKFLOW_CARD', ?, 'PENDING', NOW(), NOW())",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, conversationId);
                    ps.setString(2, user.username);
                    ps.setString(3, body);
                    ps.setString(4, metadata);
                    ps.executeUpdate();
                    messageId = generatedId(ps);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO chat_workflows (message_id, workflow_type, employee_id, work_date, payload_json, status, created_at) VALUES (?, ?, ?, ?, ?, 'PENDING', NOW())")) {
                    ps.setLong(1, messageId);
                    ps.setString(2, safeType);
                    ps.setString(3, user.employeeId);
                    ps.setDate(4, java.sql.Date.valueOf(workDate));
                    ps.setString(5, metadata);
                    ps.executeUpdate();
                }
                c.commit();
                return messageId;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    void decideWorkflow(CurrentUser user, long messageId, boolean approved) throws Exception {
        if (!user.canManageGroups()) {
            throw new IllegalArgumentException("Chỉ Admin hoặc Trưởng phòng được duyệt workflow.");
        }
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                String employeeId;
                LocalDate workDate;
                String payload;
                try (PreparedStatement ps = c.prepareStatement("SELECT employee_id, work_date, payload_json FROM chat_workflows WHERE message_id = ? FOR UPDATE")) {
                    ps.setLong(1, messageId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("Không tìm thấy workflow.");
                        }
                        employeeId = rs.getString("employee_id");
                        workDate = rs.getDate("work_date").toLocalDate();
                        payload = rs.getString("payload_json");
                    }
                }
                String status = approved ? "APPROVED" : "REJECTED";
                try (PreparedStatement ps = c.prepareStatement("UPDATE chat_workflows SET status = ?, decided_by = ?, decided_at = NOW() WHERE message_id = ?")) {
                    ps.setString(1, status);
                    ps.setString(2, user.username);
                    ps.setLong(3, messageId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE chat_messages SET workflow_status = ?, updated_at = NOW() WHERE id = ?")) {
                    ps.setString(1, status);
                    ps.setLong(2, messageId);
                    ps.executeUpdate();
                }
                if (approved) {
                    Map<String, String> meta = chatshared.JsonUtil.parseObject(payload);
                    String shift = meta.getOrDefault("shift", "Đã duyệt qua chat");
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO schedules (employee_id, work_date, shift) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE shift = VALUES(shift)")) {
                        ps.setString(1, employeeId);
                        ps.setDate(2, java.sql.Date.valueOf(workDate));
                        ps.setString(3, shift);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    boolean decideBusinessWorkflow(CurrentUser user, long messageId, boolean approved) throws Exception {
        if (!user.canManageGroups()) {
            throw new IllegalArgumentException("Chỉ Admin hoặc Trưởng phòng được duyệt workflow.");
        }
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                boolean synced = false;
                String employeeId;
                LocalDate workDate;
                String payload;
                String workflowType;
                try (PreparedStatement ps = c.prepareStatement("SELECT workflow_type, employee_id, work_date, payload_json FROM chat_workflows WHERE message_id = ? FOR UPDATE")) {
                    ps.setLong(1, messageId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalArgumentException("Không tìm thấy workflow.");
                        }
                        workflowType = rs.getString("workflow_type");
                        employeeId = rs.getString("employee_id");
                        workDate = rs.getDate("work_date").toLocalDate();
                        payload = rs.getString("payload_json");
                    }
                }
                String status = approved ? "APPROVED" : "REJECTED";
                try (PreparedStatement ps = c.prepareStatement("UPDATE chat_workflows SET status = ?, decided_by = ?, decided_at = NOW() WHERE message_id = ?")) {
                    ps.setString(1, status);
                    ps.setString(2, user.username);
                    ps.setLong(3, messageId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE chat_messages SET workflow_status = ?, updated_at = NOW() WHERE id = ?")) {
                    ps.setString(1, status);
                    ps.setLong(2, messageId);
                    ps.executeUpdate();
                }
                if (approved) {
                    Map<String, String> meta = chatshared.JsonUtil.parseObject(payload);
                    synced = applyApprovedWorkflow(c, workflowType, employeeId, workDate, meta.getOrDefault("shift", "Đã duyệt qua chat"));
                }
                c.commit();
                return synced;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    private boolean applyApprovedWorkflow(Connection c, String workflowType, String employeeId, LocalDate workDate, String value) throws Exception {
        if (tableExists(c, "schedules")) {
            String shift = "LEAVE".equals(workflowType) ? "Nghỉ phép: " + value : value;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO schedules (employee_id, work_date, shift) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE shift = VALUES(shift)")) {
                ps.setString(1, employeeId);
                ps.setDate(2, java.sql.Date.valueOf(workDate));
                ps.setString(3, shift);
                ps.executeUpdate();
                return true;
            }
        }
        if (tableExists(c, "timekeeping")) {
            String note = "LEAVE".equals(workflowType) ? "LEAVE_APPROVED: " + value : workflowType + "_APPROVED: " + value;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO timekeeping (employee_id, work_date, note) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE note = VALUES(note)")) {
                ps.setString(1, employeeId);
                ps.setDate(2, java.sql.Date.valueOf(workDate));
                ps.setString(3, note);
                ps.executeUpdate();
                return true;
            }
        }
        System.err.println("Workflow approved in chat only: schedules/timekeeping table was not found for payroll/timekeeping sync.");
        return false;
    }

    private boolean tableExists(Connection c, String tableName) throws Exception {
        try (ResultSet rs = c.getMetaData().getTables(null, null, tableName, null)) {
            if (rs.next()) {
                return true;
            }
        }
        try (ResultSet rs = c.getMetaData().getTables(null, null, tableName.toUpperCase(), null)) {
            return rs.next();
        }
    }

    String salaryDetail(CurrentUser user, String metadataJson) throws Exception {
        Map<String, String> meta = chatshared.JsonUtil.parseObject(metadataJson);
        String employeeId = meta.getOrDefault("employeeId", "");
        int month = Integer.parseInt(meta.getOrDefault("month", "0"));
        int year = Integer.parseInt(meta.getOrDefault("year", "0"));
        String sql = "SELECT e.name, e.baseSalary, s.total_salary, s.calculated_at FROM salary_history s " +
                "JOIN employees e ON e.id = s.employee_id " +
                "WHERE s.employee_id = ? AND s.pay_month = ? AND s.pay_year = ? " +
                "AND (e.login_username = ? OR e.account_username = ?) LIMIT 1";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, employeeId);
            ps.setInt(2, month);
            ps.setInt(3, year);
            ps.setString(4, user.username);
            ps.setString(5, user.username);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException("Không tìm thấy phiếu lương hoặc bạn không có quyền xem.");
                }
                return "Nhân viên: " + rs.getString("name") +
                        "\nTháng: " + month + "/" + year +
                        "\nLương cơ bản: " + String.format("%,.0f VNĐ", rs.getDouble("baseSalary")) +
                        "\nThực lĩnh: " + String.format("%,.0f VNĐ", rs.getDouble("total_salary")) +
                        "\nNgày tính: " + rs.getDate("calculated_at");
            }
        }
    }

    List<ChatMessage> listMessages(CurrentUser user, long conversationId, String search) throws Exception {
        if (!isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
        }
        List<ChatMessage> messages = new ArrayList<>();
        String filter = search == null || search.isBlank() ? "" : " AND (m.body LIKE ? OR a.original_name LIKE ?)";
        String sql = "SELECT DISTINCT m.*, COALESCE(u.full_name, e.name, m.sender_username) sender_name, r.body reply_body " +
                "FROM chat_messages m " +
                "LEFT JOIN users u ON u.username = m.sender_username " +
                "LEFT JOIN employees e ON e.login_username = m.sender_username " +
                "LEFT JOIN chat_messages r ON r.id = m.reply_to_id " +
                "LEFT JOIN chat_attachments a ON a.message_id = m.id " +
                "WHERE m.conversation_id = ? AND m.deleted_at IS NULL" + filter +
                " ORDER BY m.pinned DESC, m.id ASC LIMIT 500";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, conversationId);
            if (!filter.isBlank()) {
                String like = "%" + search.trim() + "%";
                ps.setString(2, like);
                ps.setString(3, like);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatMessage msg = mapMessage(rs);
                    msg.attachments.addAll(listAttachments(c, msg.id));
                    msg.seenCount = seenCount(c, msg.id);
                    msg.reactionSummary = reactionSummary(c, msg.id);
                    messages.add(msg);
                }
            }
        }
        markRead(user, conversationId);
        return messages;
    }

    List<ChatMessage> searchMessages(CurrentUser user, long conversationId, MessageSearchCriteria criteria) throws Exception {
        if (!isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
        }
        MessageSearchCriteria q = criteria == null ? new MessageSearchCriteria() : criteria;
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE m.conversation_id = ? AND m.deleted_at IS NULL");
        params.add(conversationId);
        if (q.keyword != null && !q.keyword.isBlank()) {
            where.append(" AND (m.body LIKE ? OR a.original_name LIKE ?)");
            String like = "%" + q.keyword.trim() + "%";
            params.add(like);
            params.add(like);
        }
        if (q.senderUsername != null && !q.senderUsername.isBlank()) {
            where.append(" AND m.sender_username = ?");
            params.add(q.senderUsername.trim());
        }
        if (q.from != null) {
            where.append(" AND m.created_at >= ?");
            params.add(Timestamp.valueOf(q.from));
        }
        if (q.to != null) {
            where.append(" AND m.created_at <= ?");
            params.add(Timestamp.valueOf(q.to));
        }
        if (q.onlyFiles) {
            where.append(" AND a.id IS NOT NULL");
        }
        if (q.onlyPinned) {
            where.append(" AND m.pinned = TRUE");
        }
        if (q.onlyMentions) {
            where.append(" AND men.mentioned_username = ?");
            params.add(user.username);
        }
        if (q.onlyTasks) {
            where.append(" AND (m.body LIKE '%[CÔNG VIỆC]%' OR m.message_type IN ('TASK_CARD','WORKFLOW_CARD'))");
        }

        String sql = "SELECT DISTINCT m.*, COALESCE(u.full_name, e.name, m.sender_username) sender_name, r.body reply_body " +
                "FROM chat_messages m " +
                "LEFT JOIN users u ON u.username = m.sender_username " +
                "LEFT JOIN employees e ON e.login_username = m.sender_username " +
                "LEFT JOIN chat_messages r ON r.id = m.reply_to_id " +
                "LEFT JOIN chat_attachments a ON a.message_id = m.id " +
                "LEFT JOIN chat_mentions men ON men.message_id = m.id " +
                where +
                " ORDER BY m.pinned DESC, m.id ASC LIMIT 500";
        List<ChatMessage> messages = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                Object value = params.get(i);
                if (value instanceof Timestamp ts) {
                    ps.setTimestamp(i + 1, ts);
                } else if (value instanceof Long l) {
                    ps.setLong(i + 1, l);
                } else {
                    ps.setString(i + 1, String.valueOf(value));
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ChatMessage msg = mapMessage(rs);
                    msg.attachments.addAll(listAttachments(c, msg.id));
                    msg.seenCount = seenCount(c, msg.id);
                    msg.reactionSummary = reactionSummary(c, msg.id);
                    messages.add(msg);
                }
            }
            if (q.includeArchive) {
                String archiveSql = "SELECT m.*, COALESCE(u.full_name, e.name, m.sender_username) sender_name, NULL reply_body " +
                        "FROM chat_messages_archive m " +
                        "LEFT JOIN users u ON u.username = m.sender_username " +
                        "LEFT JOIN employees e ON e.login_username = m.sender_username " +
                        "WHERE m.conversation_id = ? AND (? = '' OR m.body LIKE ?) " +
                        "ORDER BY m.id ASC LIMIT 500";
                try (PreparedStatement archive = c.prepareStatement(archiveSql)) {
                    String keyword = q.keyword == null ? "" : q.keyword.trim();
                    archive.setLong(1, conversationId);
                    archive.setString(2, keyword);
                    archive.setString(3, "%" + keyword + "%");
                    try (ResultSet rs = archive.executeQuery()) {
                        while (rs.next() && messages.size() < 500) {
                            ChatMessage msg = mapMessage(rs);
                            msg.reactionSummary = "";
                            messages.add(msg);
                        }
                    }
                }
            }
        }
        markRead(user, conversationId);
        return messages;
    }

    int archiveOldMessages(CurrentUser user, int olderThanDays) throws Exception {
        if (!user.isAdmin()) {
            throw new IllegalArgumentException("Chỉ admin được archive tin nhắn.");
        }
        int days = Math.max(30, olderThanDays);
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                int inserted;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT IGNORE INTO chat_messages_archive SELECT m.* FROM chat_messages m " +
                                "JOIN chat_conversations cv ON cv.id = m.conversation_id " +
                                "WHERE cv.company_owner = ? AND m.created_at < DATE_SUB(NOW(), INTERVAL ? DAY)")) {
                    ps.setString(1, user.companyOwner);
                    ps.setInt(2, days);
                    inserted = ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE m FROM chat_messages m JOIN chat_conversations cv ON cv.id = m.conversation_id " +
                                "WHERE cv.company_owner = ? AND m.created_at < DATE_SUB(NOW(), INTERVAL ? DAY)")) {
                    ps.setString(1, user.companyOwner);
                    ps.setInt(2, days);
                    ps.executeUpdate();
                }
                c.commit();
                return inserted;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    long sendMessage(CurrentUser user, long conversationId, String body, Long replyToId, List<File> files) throws Exception {
        if (!isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
        }
        boolean hasText = body != null && !body.isBlank();
        boolean hasFiles = files != null && !files.isEmpty();
        if (!hasText && !hasFiles) {
            throw new IllegalArgumentException("Tin nhan rong.");
        }

        List<FileStorageService.StoredFile> storedFiles = new ArrayList<>();
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                checkRateLimit(c, "message:" + user.username, 60, 60);
                long messageId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO chat_messages (conversation_id, sender_username, body, reply_to_id, created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW())",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, conversationId);
                    ps.setString(2, user.username);
                    ps.setString(3, hasText ? body.trim() : "");
                    if (replyToId == null) ps.setNull(4, java.sql.Types.BIGINT); else ps.setLong(4, replyToId);
                    ps.executeUpdate();
                    messageId = generatedId(ps);
                }

                if (files != null) {
                    for (File file : files) {
                        FileStorageService.StoredFile stored = storage.store(file, user, conversationId);
                        storedFiles.add(stored);
                        try (PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO chat_attachments (message_id, original_name, stored_name, file_type, mime_type, file_size, shared_path) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                            ps.setLong(1, messageId);
                            ps.setString(2, stored.originalName);
                            ps.setString(3, stored.storedName);
                            ps.setString(4, stored.fileType);
                            ps.setString(5, stored.mimeType);
                            ps.setLong(6, stored.fileSize);
                            ps.setString(7, stored.sharedPath);
                            ps.executeUpdate();
                        }
                    }
                }

                insertMentions(c, conversationId, messageId, hasText ? body.trim() : "");
                try (PreparedStatement ps = c.prepareStatement("UPDATE chat_conversations SET updated_at = NOW() WHERE id = ?")) {
                    ps.setLong(1, conversationId);
                    ps.executeUpdate();
                }
                c.commit();
                return messageId;
            } catch (Exception e) {
                c.rollback();
                for (FileStorageService.StoredFile stored : storedFiles) {
                    storage.deleteQuietly(stored.sharedPath);
                }
                throw e;
            }
        }
    }

    long createPollMessage(CurrentUser user, long conversationId, String question, List<String> options) throws Exception {
        if (!isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Câu hỏi bình chọn không được để trống.");
        }
        List<String> cleanOptions = options == null ? List.of() : options.stream()
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
        if (cleanOptions.size() < 2) {
            throw new IllegalArgumentException("Vote cần ít nhất 2 lựa chọn.");
        }
        String body = "[VOTE] " + question.trim() + " | " + String.join(" | ", cleanOptions);
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                long messageId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO chat_messages (conversation_id, sender_username, body, message_type, created_at, updated_at) VALUES (?, ?, ?, 'POLL', NOW(), NOW())",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, conversationId);
                    ps.setString(2, user.username);
                    ps.setString(3, body);
                    ps.executeUpdate();
                    messageId = generatedId(ps);
                }
                long pollId;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO chat_polls (message_id, question, created_by, created_at) VALUES (?, ?, ?, NOW())",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, messageId);
                    ps.setString(2, question.trim());
                    ps.setString(3, user.username);
                    ps.executeUpdate();
                    pollId = generatedId(ps);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO chat_poll_options (poll_id, option_text, sort_order) VALUES (?, ?, ?)")) {
                    for (int i = 0; i < cleanOptions.size(); i++) {
                        ps.setLong(1, pollId);
                        ps.setString(2, cleanOptions.get(i));
                        ps.setInt(3, i);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE chat_conversations SET updated_at = NOW() WHERE id = ?")) {
                    ps.setLong(1, conversationId);
                    ps.executeUpdate();
                }
                c.commit();
                return messageId;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    List<PollOption> listPollOptions(CurrentUser user, long messageId) throws Exception {
        long conversationId = conversationIdForMessage(messageId);
        if (!isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
        }
        List<PollOption> result = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT p.id poll_id, o.id option_id, o.option_text, COUNT(v.username) vote_count, " +
                        "SUM(CASE WHEN v.username = ? THEN 1 ELSE 0 END) selected_by_me " +
                        "FROM chat_polls p JOIN chat_poll_options o ON o.poll_id = p.id " +
                        "LEFT JOIN chat_poll_votes v ON v.option_id = o.id " +
                        "WHERE p.message_id = ? GROUP BY p.id, o.id, o.option_text, o.sort_order ORDER BY o.sort_order ASC")) {
            ps.setString(1, user.username);
            ps.setLong(2, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    PollOption option = new PollOption();
                    option.pollId = rs.getLong("poll_id");
                    option.optionId = rs.getLong("option_id");
                    option.optionText = rs.getString("option_text");
                    option.voteCount = rs.getInt("vote_count");
                    option.selectedByMe = rs.getInt("selected_by_me") > 0;
                    result.add(option);
                }
            }
        }
        return result;
    }

    void castPollVote(CurrentUser user, long pollId, long optionId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement check = c.prepareStatement(
                "SELECT m.conversation_id FROM chat_polls p JOIN chat_messages m ON m.id = p.message_id " +
                        "JOIN chat_poll_options o ON o.poll_id = p.id AND o.id = ? WHERE p.id = ?")) {
            check.setLong(1, optionId);
            check.setLong(2, pollId);
            long conversationId = 0;
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) conversationId = rs.getLong(1);
            }
            if (conversationId == 0 || !isMember(conversationId, user.username)) {
                throw new IllegalArgumentException("Vote không hợp lệ.");
            }
        }
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_poll_votes (poll_id, option_id, username, voted_at) VALUES (?, ?, ?, NOW()) " +
                        "ON DUPLICATE KEY UPDATE option_id = VALUES(option_id), voted_at = NOW()")) {
            ps.setLong(1, pollId);
            ps.setLong(2, optionId);
            ps.setString(3, user.username);
            ps.executeUpdate();
        }
    }

    void editMessage(CurrentUser user, long messageId, String body) throws Exception {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Nội dung không được để trống.");
        }
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE chat_messages SET body = ?, edited = TRUE, updated_at = NOW() WHERE id = ? AND sender_username = ? AND recalled = FALSE")) {
            ps.setString(1, body.trim());
            ps.setLong(2, messageId);
            ps.setString(3, user.username);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("Chỉ sửa được tin của bạn và chưa bị thu hồi.");
            }
        }
    }

    void recallMessage(CurrentUser user, long messageId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE chat_messages SET recalled = TRUE, body = '', updated_at = NOW() WHERE id = ? AND sender_username = ?")) {
            ps.setLong(1, messageId);
            ps.setString(2, user.username);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("Chỉ thu hồi được tin của bạn.");
            }
        }
    }

    void togglePinMessage(CurrentUser user, long messageId, boolean pinned) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE chat_messages m JOIN chat_members cm ON cm.conversation_id = m.conversation_id AND cm.username = ? SET m.pinned = ?, m.updated_at = NOW() WHERE m.id = ?")) {
            ps.setString(1, user.username);
            ps.setBoolean(2, pinned);
            ps.setLong(3, messageId);
            ps.executeUpdate();
        }
    }

    void togglePinConversation(CurrentUser user, long conversationId, boolean pinned) throws Exception {
        if (!isMember(conversationId, user.username)) return;
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("UPDATE chat_conversations SET pinned = ?, updated_at = NOW() WHERE id = ?")) {
            ps.setBoolean(1, pinned);
            ps.setLong(2, conversationId);
            ps.executeUpdate();
        }
    }

    void cleanupOldMessages(int retentionDays) throws Exception {
        List<String> paths = new ArrayList<>();
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT a.shared_path FROM chat_attachments a JOIN chat_messages m ON m.id = a.message_id WHERE m.created_at < DATE_SUB(NOW(), INTERVAL ? DAY)")) {
                ps.setInt(1, retentionDays);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) paths.add(rs.getString(1));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM chat_messages WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)")) {
                ps.setInt(1, retentionDays);
                ps.executeUpdate();
            }
        }
        for (String path : paths) {
            storage.deleteQuietly(path);
        }
    }

    private void syncCompanyMembers(long conversationId, String companyOwner) throws Exception {
        CurrentUser virtual = new CurrentUser(companyOwner, companyOwner, "ADMIN", companyOwner, null, null, null);
        for (String username : companyUsernames(virtual)) {
            ensureMember(conversationId, username, username.equals(companyOwner) ? "OWNER" : "MEMBER");
        }
    }

    private void syncDepartmentMembers(long conversationId, String companyOwner, String department) throws Exception {
        ensureMember(conversationId, companyOwner, "OWNER");
        String sql = "SELECT login_username FROM employees WHERE account_username = ? AND department = ? AND status = 'APPROVED'";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, companyOwner);
            ps.setString(2, department);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ensureMember(conversationId, rs.getString("login_username"), "MEMBER");
                }
            }
        }
    }

    private Set<String> companyUsernames(CurrentUser user) throws Exception {
        Set<String> result = new HashSet<>();
        for (ChatUser u : listCompanyUsers(user)) {
            result.add(u.username);
        }
        return result;
    }

    private long findCompanyConversation(String companyOwner) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT id FROM chat_conversations WHERE company_owner = ? AND type = 'COMPANY' LIMIT 1")) {
            ps.setString(1, companyOwner);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private long findConversationByDirectKey(String companyOwner, String directKey) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT id FROM chat_conversations WHERE company_owner = ? AND direct_key = ? LIMIT 1")) {
            ps.setString(1, companyOwner);
            ps.setString(2, directKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private Conversation getConversation(long id) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM chat_conversations WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapConversation(rs) : null;
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

    private void ensureMember(long conversationId, String username, String role) throws Exception {
        try (Connection c = db.getConnection()) {
            insertMember(c, conversationId, username, role);
        }
    }

    private long insertConversation(Connection c, String companyOwner, String type, String title, String directKey, String createdBy, boolean pinned) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_conversations (company_owner, type, title, direct_key, created_by, pinned, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, companyOwner);
            ps.setString(2, type);
            ps.setString(3, title);
            ps.setString(4, directKey);
            ps.setString(5, createdBy);
            ps.setBoolean(6, pinned);
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    private void insertMember(Connection c, long conversationId, String username, String role) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_members (conversation_id, username, member_role, joined_at) VALUES (?, ?, ?, NOW()) " +
                        "ON DUPLICATE KEY UPDATE member_role = VALUES(member_role)")) {
            ps.setLong(1, conversationId);
            ps.setString(2, username);
            ps.setString(3, role);
            ps.executeUpdate();
        }
    }

    private void markRead(CurrentUser user, long conversationId) throws Exception {
        try (Connection c = db.getConnection()) {
            long max = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(id), 0) FROM chat_messages WHERE conversation_id = ?")) {
                ps.setLong(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) max = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE chat_members SET last_read_message_id = GREATEST(last_read_message_id, ?) WHERE conversation_id = ? AND username = ?")) {
                ps.setLong(1, max);
                ps.setLong(2, conversationId);
                ps.setString(3, user.username);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_read_receipts (message_id, username, read_at) " +
                            "SELECT id, ?, NOW() FROM chat_messages WHERE conversation_id = ? AND sender_username <> ? " +
                            "ON DUPLICATE KEY UPDATE read_at = VALUES(read_at)")) {
                ps.setString(1, user.username);
                ps.setLong(2, conversationId);
                ps.setString(3, user.username);
                ps.executeUpdate();
            }
        }
    }

    private int unreadCount(Connection c, long conversationId, String username) throws Exception {
        String sql = "SELECT COUNT(*) FROM chat_messages m JOIN chat_members cm ON cm.conversation_id = m.conversation_id AND cm.username = ? " +
                "WHERE m.conversation_id = ? AND m.id > cm.last_read_message_id AND m.sender_username <> ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setLong(2, conversationId);
            ps.setString(3, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private String lastMessage(Connection c, long conversationId) throws Exception {
        String sql = "SELECT body, recalled FROM chat_messages WHERE conversation_id = ? ORDER BY id DESC LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "";
                return rs.getBoolean("recalled") ? "[Tin da thu hoi]" : Texts.shortText(rs.getString("body"), 40);
            }
        }
    }

    private String directTitle(Connection c, long conversationId, String username) throws Exception {
        String sql = "SELECT COALESCE(e.name, u.full_name, m.username) display_name FROM chat_members m " +
                "LEFT JOIN users u ON u.username = m.username LEFT JOIN employees e ON e.login_username = m.username " +
                "WHERE m.conversation_id = ? AND m.username <> ? LIMIT 1";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, conversationId);
            ps.setString(2, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "Chat";
            }
        }
    }

    private List<Attachment> listAttachments(Connection c, long messageId) throws Exception {
        List<Attachment> list = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM chat_attachments WHERE message_id = ?")) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Attachment a = new Attachment();
                    a.id = rs.getLong("id");
                    a.messageId = messageId;
                    a.originalName = rs.getString("original_name");
                    a.fileType = rs.getString("file_type");
                    a.mimeType = rs.getString("mime_type");
                    a.fileSize = rs.getLong("file_size");
                    a.sharedPath = rs.getString("shared_path");
                    list.add(a);
                }
            }
        }
        return list;
    }

    private int seenCount(Connection c, long messageId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM chat_read_receipts WHERE message_id = ?")) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static Conversation mapConversation(ResultSet rs) throws Exception {
        Conversation c = new Conversation();
        c.id = rs.getLong("id");
        c.companyOwner = rs.getString("company_owner");
        c.type = rs.getString("type");
        c.title = rs.getString("title");
        c.createdBy = rs.getString("created_by");
        c.pinned = rs.getBoolean("pinned");
        Timestamp ts = rs.getTimestamp("updated_at");
        c.updatedAt = ts == null ? null : ts.toLocalDateTime();
        return c;
    }

    private static ChatMessage mapMessage(ResultSet rs) throws Exception {
        ChatMessage m = new ChatMessage();
        m.id = rs.getLong("id");
        m.conversationId = rs.getLong("conversation_id");
        m.senderUsername = rs.getString("sender_username");
        m.senderName = rs.getString("sender_name");
        m.body = rs.getString("body");
        m.messageType = rs.getString("message_type");
        m.metadataJson = rs.getString("metadata_json");
        m.workflowStatus = rs.getString("workflow_status");
        long reply = rs.getLong("reply_to_id");
        m.replyToId = rs.wasNull() ? null : reply;
        m.replyPreview = rs.getString("reply_body");
        m.edited = rs.getBoolean("edited");
        m.recalled = rs.getBoolean("recalled");
        m.pinned = rs.getBoolean("pinned");
        Timestamp ts = rs.getTimestamp("created_at");
        m.createdAt = ts == null ? LocalDateTime.now() : ts.toLocalDateTime();
        return m;
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

    private String reactionSummary(Connection c, long messageId) throws Exception {
        List<String> parts = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT emoji, COUNT(*) total FROM chat_message_reactions WHERE message_id = ? GROUP BY emoji ORDER BY total DESC, emoji ASC")) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    parts.add(rs.getString("emoji") + " " + rs.getInt("total"));
                }
            }
        }
        return String.join("  ", parts);
    }

    private long conversationIdForMessage(long messageId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT conversation_id FROM chat_messages WHERE id = ?")) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        throw new IllegalArgumentException("Tin nhắn không tồn tại.");
    }

    private void insertMentions(Connection c, long conversationId, long messageId, String body) throws Exception {
        if (body == null || body.isBlank()) {
            return;
        }
        Matcher matcher = MENTION_PATTERN.matcher(body);
        Set<String> mentioned = new HashSet<>();
        while (matcher.find()) {
            mentioned.add(matcher.group(1));
        }
        if (mentioned.isEmpty()) {
            return;
        }
        try (PreparedStatement member = c.prepareStatement(
                "SELECT 1 FROM chat_members WHERE conversation_id = ? AND username = ? LIMIT 1");
             PreparedStatement insert = c.prepareStatement(
                     "INSERT IGNORE INTO chat_mentions (message_id, mentioned_username, created_at) VALUES (?, ?, NOW())")) {
            for (String username : mentioned) {
                member.setLong(1, conversationId);
                member.setString(2, username);
                try (ResultSet rs = member.executeQuery()) {
                    if (!rs.next()) {
                        continue;
                    }
                }
                insert.setLong(1, messageId);
                insert.setString(2, username);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void checkRateLimit(Connection c, String rateKey, int maxCount, int windowSeconds) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_rate_limits (scope_key, window_start, counter, updated_at) VALUES (?, NOW(), 1, NOW()) " +
                        "ON DUPLICATE KEY UPDATE " +
                        "counter = IF(window_start < DATE_SUB(NOW(), INTERVAL ? SECOND), 1, counter + 1), " +
                        "window_start = IF(window_start < DATE_SUB(NOW(), INTERVAL ? SECOND), NOW(), window_start), " +
                        "updated_at = NOW()")) {
            ps.setString(1, rateKey);
            ps.setInt(2, windowSeconds);
            ps.setInt(3, windowSeconds);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT counter FROM chat_rate_limits WHERE scope_key = ?")) {
            ps.setString(1, rateKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > maxCount) {
                    throw new IllegalArgumentException("Bạn gửi quá nhanh. Vui lòng thử lại sau.");
                }
            }
        }
    }

    void addReaction(CurrentUser user, long messageId, String emoji) throws Exception {
        if (emoji == null || emoji.isBlank()) {
            throw new IllegalArgumentException("Reaction không hợp lệ.");
        }
        long conversationId = conversationIdForMessage(messageId);
        if (!isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
        }
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO chat_message_reactions (message_id, username, emoji, created_at) VALUES (?, ?, ?, NOW()) " +
                        "ON DUPLICATE KEY UPDATE emoji = VALUES(emoji), created_at = NOW()")) {
            ps.setLong(1, messageId);
            ps.setString(2, user.username);
            ps.setString(3, emoji.trim());
            ps.executeUpdate();
        }
    }

    void removeReaction(CurrentUser user, long messageId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM chat_message_reactions WHERE message_id = ? AND username = ?")) {
            ps.setLong(1, messageId);
            ps.setString(2, user.username);
            ps.executeUpdate();
        }
    }

    long scheduleMessage(CurrentUser user, long conversationId, String body, LocalDateTime scheduledAt) throws Exception {
        if (!isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
        }
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Nội dung hẹn giờ không được để trống.");
        }
        if (scheduledAt == null || !scheduledAt.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Thoi diem gui phai nam trong tuong lai.");
        }
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO chat_scheduled_messages (conversation_id, sender_username, body, scheduled_at, status, created_at) " +
                             "VALUES (?, ?, ?, ?, 'PENDING', NOW())",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, conversationId);
            ps.setString(2, user.username);
            ps.setString(3, body.trim());
            ps.setTimestamp(4, Timestamp.valueOf(scheduledAt));
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    List<ScheduledMessage> listScheduledMessages(CurrentUser user) throws Exception {
        List<ScheduledMessage> result = new ArrayList<>();
        String sql = "SELECT s.*, c.title conversation_title FROM chat_scheduled_messages s " +
                "JOIN chat_conversations c ON c.id = s.conversation_id " +
                "JOIN chat_members m ON m.conversation_id = s.conversation_id AND m.username = ? " +
                "WHERE s.sender_username = ? OR ? = TRUE " +
                "ORDER BY s.scheduled_at DESC LIMIT 300";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.username);
            ps.setString(2, user.username);
            ps.setBoolean(3, user.isAdmin());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapScheduledMessage(rs));
            }
        }
        return result;
    }

    void updateScheduledMessage(CurrentUser user, long id, String body, LocalDateTime scheduledAt) throws Exception {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Nội dung hẹn giờ không được để trống.");
        }
        if (scheduledAt == null || !scheduledAt.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Thời điểm gửi phải nằm trong tương lai.");
        }
        String sql = "UPDATE chat_scheduled_messages s JOIN chat_members m ON m.conversation_id = s.conversation_id AND m.username = ? " +
                "SET s.body = ?, s.scheduled_at = ? WHERE s.id = ? AND s.status = 'PENDING' AND (s.sender_username = ? OR ? = TRUE)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.username);
            ps.setString(2, body.trim());
            ps.setTimestamp(3, Timestamp.valueOf(scheduledAt));
            ps.setLong(4, id);
            ps.setString(5, user.username);
            ps.setBoolean(6, user.isAdmin());
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("Không thể sửa tin hẹn giờ này.");
        }
    }

    void cancelScheduledMessage(CurrentUser user, long id) throws Exception {
        String sql = "UPDATE chat_scheduled_messages s JOIN chat_members m ON m.conversation_id = s.conversation_id AND m.username = ? " +
                "SET s.status = 'CANCELLED' WHERE s.id = ? AND s.status = 'PENDING' AND (s.sender_username = ? OR ? = TRUE)";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.username);
            ps.setLong(2, id);
            ps.setString(3, user.username);
            ps.setBoolean(4, user.isAdmin());
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("Không thể hủy tin hẹn giờ này.");
        }
    }

    long createReminder(CurrentUser user, Long conversationId, String title, String body, LocalDateTime remindAt) throws Exception {
        if (conversationId != null && !isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Tiêu đề nhắc việc không được để trống.");
        }
        if (remindAt == null || !remindAt.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Thoi diem nhac phai nam trong tuong lai.");
        }
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO chat_reminders (username, conversation_id, title, body, remind_at, status, created_at) " +
                             "VALUES (?, ?, ?, ?, ?, 'PENDING', NOW())",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, user.username);
            if (conversationId == null) ps.setNull(2, java.sql.Types.BIGINT); else ps.setLong(2, conversationId);
            ps.setString(3, title.trim());
            ps.setString(4, body == null ? "" : body.trim());
            ps.setTimestamp(5, Timestamp.valueOf(remindAt));
            ps.executeUpdate();
            return generatedId(ps);
        }
    }

    List<ChatReminder> listReminders(CurrentUser user) throws Exception {
        List<ChatReminder> result = new ArrayList<>();
        String sql = "SELECT r.*, c.title conversation_title FROM chat_reminders r " +
                "LEFT JOIN chat_conversations c ON c.id = r.conversation_id " +
                "WHERE r.username = ? OR (? = TRUE AND (c.company_owner = ? OR r.conversation_id IS NULL)) " +
                "ORDER BY r.remind_at DESC LIMIT 300";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, user.username);
            ps.setBoolean(2, user.isAdmin());
            ps.setString(3, user.companyOwner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(mapReminder(rs));
            }
        }
        return result;
    }

    void updateReminder(CurrentUser user, long id, String title, String body, LocalDateTime remindAt) throws Exception {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Tiêu đề nhắc việc không được để trống.");
        }
        if (remindAt == null || !remindAt.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Thời điểm nhắc phải nằm trong tương lai.");
        }
        String sql = "UPDATE chat_reminders r LEFT JOIN chat_conversations c ON c.id = r.conversation_id " +
                "SET r.title = ?, r.body = ?, r.remind_at = ? " +
                "WHERE r.id = ? AND r.status = 'PENDING' AND (r.username = ? OR (? = TRUE AND (c.company_owner = ? OR r.conversation_id IS NULL)))";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, title.trim());
            ps.setString(2, body == null ? "" : body.trim());
            ps.setTimestamp(3, Timestamp.valueOf(remindAt));
            ps.setLong(4, id);
            ps.setString(5, user.username);
            ps.setBoolean(6, user.isAdmin());
            ps.setString(7, user.companyOwner);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("Không thể sửa nhắc việc này.");
        }
    }

    void cancelReminder(CurrentUser user, long id) throws Exception {
        String sql = "UPDATE chat_reminders r LEFT JOIN chat_conversations c ON c.id = r.conversation_id " +
                "SET r.status = 'CANCELLED' " +
                "WHERE r.id = ? AND r.status = 'PENDING' AND (r.username = ? OR (? = TRUE AND (c.company_owner = ? OR r.conversation_id IS NULL)))";
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, user.username);
            ps.setBoolean(3, user.isAdmin());
            ps.setString(4, user.companyOwner);
            if (ps.executeUpdate() == 0) throw new IllegalArgumentException("Không thể hủy nhắc việc này.");
        }
    }

    List<ReactionDetail> listReactionDetails(CurrentUser user, long messageId) throws Exception {
        long conversationId = conversationIdForMessage(messageId);
        if (!isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Bạn không thuộc hội thoại này.");
        }
        List<ReactionDetail> result = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT r.emoji, r.username, COALESCE(e.name, u.full_name, r.username) display_name, r.created_at " +
                        "FROM chat_message_reactions r LEFT JOIN users u ON u.username = r.username " +
                        "LEFT JOIN employees e ON e.login_username = r.username WHERE r.message_id = ? ORDER BY r.created_at DESC")) {
            ps.setLong(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ReactionDetail item = new ReactionDetail();
                    item.emoji = rs.getString("emoji");
                    item.username = rs.getString("username");
                    item.displayName = rs.getString("display_name");
                    Timestamp ts = rs.getTimestamp("created_at");
                    item.createdAt = ts == null ? null : ts.toLocalDateTime();
                    result.add(item);
                }
            }
        }
        return result;
    }

    List<MentionItem> listMentionsForUser(CurrentUser user) throws Exception {
        List<MentionItem> result = new ArrayList<>();
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT m.id message_id, m.conversation_id, c.title conversation_title, m.sender_username, " +
                        "COALESCE(e.name, u.full_name, m.sender_username) sender_name, m.body, m.created_at " +
                        "FROM chat_mentions men JOIN chat_messages m ON m.id = men.message_id " +
                        "JOIN chat_conversations c ON c.id = m.conversation_id " +
                        "JOIN chat_members cm ON cm.conversation_id = c.id AND cm.username = ? " +
                        "LEFT JOIN users u ON u.username = m.sender_username " +
                        "LEFT JOIN employees e ON e.login_username = m.sender_username " +
                        "WHERE men.mentioned_username = ? ORDER BY m.created_at DESC LIMIT 200")) {
            ps.setString(1, user.username);
            ps.setString(2, user.username);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MentionItem item = new MentionItem();
                    item.messageId = rs.getLong("message_id");
                    item.conversationId = rs.getLong("conversation_id");
                    item.conversationTitle = rs.getString("conversation_title");
                    item.senderUsername = rs.getString("sender_username");
                    item.senderName = rs.getString("sender_name");
                    item.body = rs.getString("body");
                    Timestamp ts = rs.getTimestamp("created_at");
                    item.createdAt = ts == null ? null : ts.toLocalDateTime();
                    result.add(item);
                }
            }
        }
        return result;
    }

    private static ScheduledMessage mapScheduledMessage(ResultSet rs) throws Exception {
        ScheduledMessage item = new ScheduledMessage();
        item.id = rs.getLong("id");
        item.conversationId = rs.getLong("conversation_id");
        item.conversationTitle = rs.getString("conversation_title");
        item.senderUsername = rs.getString("sender_username");
        item.body = rs.getString("body");
        item.status = rs.getString("status");
        Timestamp scheduled = rs.getTimestamp("scheduled_at");
        item.scheduledAt = scheduled == null ? null : scheduled.toLocalDateTime();
        Timestamp sent = rs.getTimestamp("sent_at");
        item.sentAt = sent == null ? null : sent.toLocalDateTime();
        Timestamp created = rs.getTimestamp("created_at");
        item.createdAt = created == null ? null : created.toLocalDateTime();
        return item;
    }

    private static ChatReminder mapReminder(ResultSet rs) throws Exception {
        ChatReminder item = new ChatReminder();
        item.id = rs.getLong("id");
        long conversationId = rs.getLong("conversation_id");
        item.conversationId = rs.wasNull() ? null : conversationId;
        item.conversationTitle = rs.getString("conversation_title");
        item.username = rs.getString("username");
        item.title = rs.getString("title");
        item.body = rs.getString("body");
        item.status = rs.getString("status");
        Timestamp remind = rs.getTimestamp("remind_at");
        item.remindAt = remind == null ? null : remind.toLocalDateTime();
        Timestamp sent = rs.getTimestamp("sent_at");
        item.sentAt = sent == null ? null : sent.toLocalDateTime();
        Timestamp created = rs.getTimestamp("created_at");
        item.createdAt = created == null ? null : created.toLocalDateTime();
        return item;
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
            if (!keys.next()) throw new IllegalStateException("Missing generated id.");
            return keys.getLong(1);
        }
    }

    private static String directKey(String a, String b) {
        return a.compareToIgnoreCase(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private static LocalDateTime timestampToLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }
}
