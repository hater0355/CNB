package chatserver;

import chatapp.AppConfig;
import chatapp.Database;
import chatapp.SchemaManager;
import chatshared.JsonUtil;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
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
            System.err.println("Cannot initialize schema at startup. Server will keep running and retry bot jobs later: " + e.getMessage());
        }
        RealtimeServer server = new RealtimeServer(config, database);
        server.start();
        System.out.println("CHAT_NOI_BO realtime server listening on ws://" + config.realtimeHost + ":" + config.realtimePort);
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
            scheduler.scheduleWithFixedDelay(this::sendBirthdayMessagesQuietly, 5, TimeUnit.HOURS.toSeconds(6), TimeUnit.SECONDS);
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
                conn.setAttachment(event.getOrDefault("username", ""));
                conn.send(JsonUtil.stringify(Map.of("type", "AUTH_OK", "at", System.currentTimeMillis())));
                return;
            }
            broadcast(message);
        }

        @Override
        public void onError(WebSocket conn, Exception ex) {
            ex.printStackTrace();
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
                System.err.println("Birthday bot error: " + e.getMessage());
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
                    return keys.getLong(1);
                }
            }
        }
    }
}
