package chatserver;

import chatapp.AppConfig;
import chatapp.AppLog;
import chatapp.Database;
import chatapp.SchemaManager;
import chatapp.SecurityService;
import chatshared.JsonUtil;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

public final class ChatServerApp {
    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();
        Database database = new Database(config);
        try {
            new SchemaManager(database).init();
        } catch (Exception e) {
            AppLog.warn("Không khởi tạo được schema lúc server mở. Server vẫn chạy và bot jobs sẽ thử lại sau.", e);
        }
        RealtimeServer server = new RealtimeServer(config, database);
        server.start();
        AppLog.info("Realtime server đang nghe tại ws://" + config.realtimeHost + ":" + config.realtimePort);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.stop(1000);
            } catch (Exception ignored) {
            }
            database.close();
        }));
    }

    private static final class RealtimeServer extends WebSocketServer {
        private final AppConfig config;
        private final Database db;
        private final SecurityService securityService;
        private final Set<WebSocket> sockets = ConcurrentHashMap.newKeySet();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "chat-birthday-bot");
            thread.setDaemon(true);
            return thread;
        });

        RealtimeServer(AppConfig config, Database db) {
            super(new InetSocketAddress(config.realtimeHost, config.realtimePort));
            this.config = config;
            this.db = db;
            this.securityService = new SecurityService(db, config);
            scheduler.scheduleWithFixedDelay(this::sendBirthdayMessagesQuietly, 5, TimeUnit.HOURS.toSeconds(6), TimeUnit.SECONDS);
            scheduler.scheduleWithFixedDelay(this::processDueAutomationQuietly, 8, 15, TimeUnit.SECONDS);
            scheduler.scheduleWithFixedDelay(this::archiveOldMessagesQuietly, 1, 24, TimeUnit.HOURS);
        }

        @Override
        public void onOpen(WebSocket conn, ClientHandshake handshake) {
            sockets.add(conn);
            conn.send(JsonUtil.stringify(Map.of("type", "CONNECTED", "at", System.currentTimeMillis())));
        }

        @Override
        public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            sockets.remove(conn);
        }

        @Override
        public void onMessage(WebSocket conn, String message) {
            Map<String, String> event = JsonUtil.parseObject(message);
            String type = event.getOrDefault("type", "");
            if ("AUTH".equals(type)) {
                try {
                    String username = securityService.validateToken(event.getOrDefault("token", ""));
                    if (username == null || username.isBlank()) {
                        conn.send(JsonUtil.stringify(Map.of("type", "AUTH_FAILED", "at", System.currentTimeMillis())));
                        conn.close(1008, "auth failed");
                        return;
                    }
                    conn.setAttachment(username);
                    conn.send(JsonUtil.stringify(Map.of("type", "AUTH_OK", "at", System.currentTimeMillis())));
                } catch (Exception e) {
                    conn.send(JsonUtil.stringify(Map.of("type", "AUTH_FAILED", "at", System.currentTimeMillis())));
                    conn.close(1011, "auth error");
                }
                return;
            }
            if (conn.getAttachment() == null) {
                conn.close(1008, "auth required");
                return;
            }
            try {
                securityService.touchUser(String.valueOf(conn.getAttachment()));
            } catch (Exception e) {
                AppLog.warn("Không cập nhật được last seen.", e);
            }
            broadcast(message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            AppLog.warn("Lỗi WebSocket server.", ex);
        }

        @Override
        public void onStart() {
            setConnectionLostTimeout(30);
        }

        @Override
        public void broadcast(String text) {
            for (WebSocket socket : sockets) {
                if (socket != null && socket.isOpen()) {
                    socket.send(text);
                }
            }
        }

        private void sendBirthdayMessagesQuietly() {
            try {
                sendBirthdayMessages();
            } catch (Exception e) {
                AppLog.warn("Birthday bot lỗi.", e);
            }
        }

        private void processDueAutomationQuietly() {
            try {
                sendDueScheduledMessages();
                sendDueReminders();
            } catch (Exception e) {
                AppLog.warn("Scheduled chat job lỗi.", e);
            }
        }

        private void archiveOldMessagesQuietly() {
            try {
                archiveOldMessages();
            } catch (Exception e) {
                AppLog.warn("Archive tin nhắn cũ lỗi.", e);
            }
        }

        private void archiveOldMessages() throws Exception {
            int days = Math.max(30, config.retentionDays);
            try (Connection c = db.getConnection()) {
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT IGNORE INTO chat_messages_archive SELECT * FROM chat_messages " +
                                    "WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)")) {
                        ps.setInt(1, days);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "DELETE FROM chat_messages WHERE created_at < DATE_SUB(NOW(), INTERVAL ? DAY)")) {
                        ps.setInt(1, days);
                        ps.executeUpdate();
                    }
                    c.commit();
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            }
        }

        private void sendDueScheduledMessages() throws Exception {
            List<PushEvent> events = new ArrayList<>();
            try (Connection c = db.getConnection()) {
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT id, conversation_id, sender_username, body FROM chat_scheduled_messages " +
                                    "WHERE status = 'PENDING' AND scheduled_at <= NOW() ORDER BY scheduled_at ASC LIMIT 25");
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long scheduledId = rs.getLong("id");
                            long conversationId = rs.getLong("conversation_id");
                            String sender = rs.getString("sender_username");
                            String body = rs.getString("body");
                            long messageId = insertUserMessage(c, conversationId, sender, body, "TEXT", null);
                            try (PreparedStatement done = c.prepareStatement(
                                    "UPDATE chat_scheduled_messages SET status = 'SENT', sent_at = NOW() WHERE id = ?")) {
                                done.setLong(1, scheduledId);
                                done.executeUpdate();
                            }
                            events.add(new PushEvent("MESSAGE_CREATED", conversationId, messageId, sender));
                        }
                    }
                    c.commit();
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            }
            for (PushEvent event : events) {
                broadcast(JsonUtil.stringify(JsonUtil.event(event.type, event.conversationId, event.actor)));
            }
        }

        private void sendDueReminders() throws Exception {
            List<PushEvent> events = new ArrayList<>();
            try (Connection c = db.getConnection()) {
                c.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT id, conversation_id, title, body FROM chat_reminders " +
                                    "WHERE status = 'PENDING' AND remind_at <= NOW() AND conversation_id IS NOT NULL " +
                                    "ORDER BY remind_at ASC LIMIT 25");
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long reminderId = rs.getLong("id");
                            long conversationId = rs.getLong("conversation_id");
                            String title = rs.getString("title");
                            String body = rs.getString("body");
                            String text = "[Nhắc việc] " + title + (body == null || body.isBlank() ? "" : "\n" + body);
                            long messageId = insertBotMessage(c, conversationId, text, "REMINDER_CARD", "{\"reminderId\":" + reminderId + "}");
                            try (PreparedStatement done = c.prepareStatement(
                                    "UPDATE chat_reminders SET status = 'SENT', sent_at = NOW() WHERE id = ?")) {
                                done.setLong(1, reminderId);
                                done.executeUpdate();
                            }
                            events.add(new PushEvent("MESSAGE_CREATED", conversationId, messageId, "system_bot"));
                        }
                    }
                    c.commit();
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                }
            }
            for (PushEvent event : events) {
                broadcast(JsonUtil.stringify(JsonUtil.event(event.type, event.conversationId, event.actor)));
            }
        }

        private void sendBirthdayMessages() throws Exception {
            String sql = "SELECT account_username, name, login_username FROM employees " +
                    "WHERE status = 'APPROVED' AND ngay_sinh IS NOT NULL " +
                    "AND MONTH(ngay_sinh) = MONTH(CURRENT_DATE()) AND DAY(ngay_sinh) = DAY(CURRENT_DATE())";
            try (Connection c = db.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String companyOwner = rs.getString("account_username");
                    String name = rs.getString("name");
                    String login = rs.getString("login_username");
                    String eventKey = "birthday:" + LocalDate.now() + ":" + login;
                    if (!claimBotEvent(c, eventKey)) {
                        continue;
                    }
                    long conversationId = ensureCompanyConversation(c, companyOwner);
                    long messageId = insertBotMessage(c, conversationId,
                            "🎂 Chúc mừng sinh nhật " + name + "! Chúc bạn một ngày thật vui và nhiều năng lượng.",
                            "BIRTHDAY_CARD",
                            "{\"employee\":\"" + login + "\"}");
                    broadcast(JsonUtil.stringify(JsonUtil.event("MESSAGE_CREATED", conversationId, "system_bot")));
                    broadcast(JsonUtil.stringify(Map.of("type", "BOT_BIRTHDAY", "conversationId", conversationId, "messageId", messageId)));
                }
            }
        }

        private boolean claimBotEvent(Connection c, String key) throws Exception {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO chat_bot_events (event_key, created_at) VALUES (?, NOW())")) {
                ps.setString(1, key);
                return ps.executeUpdate() > 0;
            }
        }

        private long ensureCompanyConversation(Connection c, String companyOwner) throws Exception {
            try (PreparedStatement find = c.prepareStatement("SELECT id FROM chat_conversations WHERE company_owner = ? AND type = 'COMPANY' LIMIT 1")) {
                find.setString(1, companyOwner);
                try (ResultSet rs = find.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO chat_conversations (company_owner, type, title, direct_key, created_by, pinned, created_at, updated_at) " +
                            "VALUES (?, 'COMPANY', 'Toan cong ty', 'COMPANY', ?, FALSE, NOW(), NOW())",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, companyOwner);
                insert.setString(2, companyOwner);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new IllegalStateException("Missing company conversation id.");
                    }
                    long id = keys.getLong(1);
                    syncCompanyMembers(c, id, companyOwner);
                    return id;
                }
            }
        }

        private void syncCompanyMembers(Connection c, long conversationId, String companyOwner) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT login_username FROM employees WHERE account_username = ? AND status = 'APPROVED' UNION SELECT ?")) {
                ps.setString(1, companyOwner);
                ps.setString(2, companyOwner);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        insertMember(c, conversationId, rs.getString(1));
                    }
                }
            }
        }

        private void insertMember(Connection c, long conversationId, String username) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_members (conversation_id, username, member_role, joined_at) VALUES (?, ?, 'MEMBER', NOW()) " +
                            "ON DUPLICATE KEY UPDATE username = VALUES(username)")) {
                ps.setLong(1, conversationId);
                ps.setString(2, username);
                ps.executeUpdate();
            }
        }

        private long insertBotMessage(Connection c, long conversationId, String body, String messageType, String metadataJson) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_messages (conversation_id, sender_username, body, message_type, metadata_json, created_at, updated_at) " +
                            "VALUES (?, 'system_bot', ?, ?, ?, NOW(), NOW())",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, conversationId);
                ps.setString(2, body);
                ps.setString(3, messageType);
                ps.setString(4, metadataJson);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new IllegalStateException("Missing bot message id.");
                    }
                    long messageId = keys.getLong(1);
                    try (PreparedStatement update = c.prepareStatement("UPDATE chat_conversations SET updated_at = NOW() WHERE id = ?")) {
                        update.setLong(1, conversationId);
                        update.executeUpdate();
                    }
                    return messageId;
                }
            }
        }

        private long insertUserMessage(Connection c, long conversationId, String sender, String body, String messageType, String metadataJson) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO chat_messages (conversation_id, sender_username, body, message_type, metadata_json, created_at, updated_at) " +
                            "VALUES (?, ?, ?, ?, ?, NOW(), NOW())",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, conversationId);
                ps.setString(2, sender);
                ps.setString(3, body);
                ps.setString(4, messageType);
                ps.setString(5, metadataJson);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new IllegalStateException("Missing scheduled message id.");
                    }
                    long messageId = keys.getLong(1);
                    try (PreparedStatement update = c.prepareStatement("UPDATE chat_conversations SET updated_at = NOW() WHERE id = ?")) {
                        update.setLong(1, conversationId);
                        update.executeUpdate();
                    }
                    return messageId;
                }
            }
        }

        private static final class PushEvent {
            final String type;
            final long conversationId;
            final long messageId;
            final String actor;

            PushEvent(String type, long conversationId, long messageId, String actor) {
                this.type = type;
                this.conversationId = conversationId;
                this.messageId = messageId;
                this.actor = actor;
            }
        }
    }
}
