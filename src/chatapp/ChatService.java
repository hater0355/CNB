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

final class ChatService {
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
        String adminSql = "SELECT username, full_name, role FROM users WHERE username = ?";
        String empSql = "SELECT u.username, COALESCE(e.name, u.full_name, u.username) display_name, u.role, e.position " +
                "FROM employees e JOIN users u ON u.username = e.login_username " +
                "WHERE e.account_username = ? AND e.status = 'APPROVED' ORDER BY display_name";
        try (Connection c = db.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(adminSql)) {
                ps.setString(1, user.companyOwner);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        users.put(rs.getString("username"), new ChatUser(rs.getString("username"), rs.getString("full_name"), rs.getString("role"), "Admin"));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(empSql)) {
                ps.setString(1, user.companyOwner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        users.put(rs.getString("username"), new ChatUser(rs.getString("username"), rs.getString("display_name"), rs.getString("role"), rs.getString("position")));
                    }
                }
            }
        }
        return new ArrayList<>(users.values());
    }

    long openDirectConversation(CurrentUser user, String otherUsername) throws Exception {
        if (user.username.equals(otherUsername)) {
            throw new IllegalArgumentException("Khong the chat voi chinh minh.");
        }
        if (!companyUsernames(user).contains(otherUsername)) {
            throw new IllegalArgumentException("Nguoi dung khong thuoc cong ty.");
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
            throw new IllegalArgumentException("Chi Admin hoac Truong phong duoc tao nhom.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Ten nhom khong duoc de trong.");
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
            throw new IllegalArgumentException("Chi Admin hoac Truong phong duoc quan ly nhom.");
        }
        Conversation conv = getConversation(conversationId);
        if (conv == null || !"GROUP".equals(conv.type) || !user.companyOwner.equals(conv.companyOwner)) {
            throw new IllegalArgumentException("Nhom khong hop le.");
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
            throw new IllegalArgumentException("Chua chon nhan vien.");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("Noi dung cong viec khong duoc de trong.");
        }
        String allowedSql = "SELECT 1 FROM employees WHERE id = ? AND account_username = ? AND status = 'APPROVED'";
        try (Connection c = db.getConnection()) {
            try (PreparedStatement allowed = c.prepareStatement(allowedSql)) {
                allowed.setString(1, employeeId);
                allowed.setString(2, user.companyOwner);
                try (ResultSet rs = allowed.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException("Nhan vien khong thuoc cong ty hoac chua duoc duyet.");
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
            throw new IllegalArgumentException("Ban khong thuoc hoi thoai nay.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Ten cong viec khong duoc de trong.");
        }
        if (assignee == null || assignee.isBlank() || !isMember(conversationId, assignee)) {
            throw new IllegalArgumentException("Nguoi thuc hien khong thuoc hoi thoai.");
        }
        String safePriority = normalizePriority(priority);
        if (kpi < 0) {
            throw new IllegalArgumentException("Diem KPI khong duoc am.");
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
            throw new IllegalArgumentException("Ban khong thuoc hoi thoai nay.");
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
                            throw new IllegalArgumentException("Khong tim thay cong viec.");
                        }
                        task = mapTask(rs);
                    }
                }
                if (!isMember(task.conversationId, user.username)) {
                    throw new IllegalArgumentException("Ban khong thuoc hoi thoai nay.");
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
            throw new IllegalArgumentException("Chi nhan vien moi tao duoc yeu cau workflow.");
        }
        if (workDate == null) {
            throw new IllegalArgumentException("Chua chon ngay.");
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

    void decideWorkflow(CurrentUser user, long messageId, boolean approved) throws Exception {
        if (!user.canManageGroups()) {
            throw new IllegalArgumentException("Chi Admin hoac Truong phong duoc duyet workflow.");
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
                            throw new IllegalArgumentException("Khong tim thay workflow.");
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
                    throw new IllegalArgumentException("Khong tim thay phieu luong hoac ban khong co quyen xem.");
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
            throw new IllegalArgumentException("Ban khong thuoc hoi thoai nay.");
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
                    messages.add(msg);
                }
            }
        }
        markRead(user, conversationId);
        return messages;
    }

    long sendMessage(CurrentUser user, long conversationId, String body, Long replyToId, List<File> files) throws Exception {
        if (!isMember(conversationId, user.username)) {
            throw new IllegalArgumentException("Ban khong thuoc hoi thoai nay.");
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

    void editMessage(CurrentUser user, long messageId, String body) throws Exception {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Noi dung khong duoc de trong.");
        }
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE chat_messages SET body = ?, edited = TRUE, updated_at = NOW() WHERE id = ? AND sender_username = ? AND recalled = FALSE")) {
            ps.setString(1, body.trim());
            ps.setLong(2, messageId);
            ps.setString(3, user.username);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("Chi sua duoc tin cua ban va chua bi thu hoi.");
            }
        }
    }

    void recallMessage(CurrentUser user, long messageId) throws Exception {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE chat_messages SET recalled = TRUE, body = '', updated_at = NOW() WHERE id = ? AND sender_username = ?")) {
            ps.setLong(1, messageId);
            ps.setString(2, user.username);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("Chi thu hoi duoc tin cua ban.");
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

    private static String normalizeStatus(String value) {
        String status = value == null ? "" : value.trim().toUpperCase();
        if (Set.of("TODO", "IN_PROGRESS", "REVIEW", "DONE").contains(status)) {
            return status;
        }
        throw new IllegalArgumentException("Trang thai cong viec khong hop le.");
    }

    private static String normalizePriority(String value) {
        String priority = value == null || value.isBlank() ? "MEDIUM" : value.trim().toUpperCase();
        if (Set.of("LOW", "MEDIUM", "HIGH").contains(priority)) {
            return priority;
        }
        throw new IllegalArgumentException("Do uu tien khong hop le.");
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
}
