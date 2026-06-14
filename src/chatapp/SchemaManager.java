package chatapp;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

public final class SchemaManager {
    private final Database db;

    public SchemaManager(Database db) {
        this.db = db;
    }

    public void init() throws Exception {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS chat_conversations (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "company_owner VARCHAR(50) NOT NULL," +
                    "type VARCHAR(20) NOT NULL," +
                    "title VARCHAR(150) NOT NULL," +
                    "direct_key VARCHAR(140) NULL," +
                    "created_by VARCHAR(50) NOT NULL," +
                    "pinned BOOLEAN DEFAULT FALSE," +
                    "created_at DATETIME NOT NULL," +
                    "updated_at DATETIME NOT NULL," +
                    "UNIQUE KEY uk_chat_direct (company_owner, direct_key)," +
                    "INDEX idx_chat_conversation_company (company_owner, updated_at))");
            st.execute("CREATE TABLE IF NOT EXISTS chat_members (" +
                    "conversation_id BIGINT NOT NULL," +
                    "username VARCHAR(50) NOT NULL," +
                    "member_role VARCHAR(20) NOT NULL DEFAULT 'MEMBER'," +
                    "last_read_message_id BIGINT DEFAULT 0," +
                    "joined_at DATETIME NOT NULL," +
                    "PRIMARY KEY (conversation_id, username)," +
                    "INDEX idx_chat_members_user (username)," +
                    "CONSTRAINT fk_chat_members_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_messages (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "conversation_id BIGINT NOT NULL," +
                    "sender_username VARCHAR(50) NOT NULL," +
                    "body TEXT," +
                    "reply_to_id BIGINT NULL," +
                    "edited BOOLEAN DEFAULT FALSE," +
                    "recalled BOOLEAN DEFAULT FALSE," +
                    "pinned BOOLEAN DEFAULT FALSE," +
                    "created_at DATETIME NOT NULL," +
                    "updated_at DATETIME NOT NULL," +
                    "deleted_at DATETIME NULL," +
                    "INDEX idx_chat_messages_conversation (conversation_id, id)," +
                    "CONSTRAINT fk_chat_messages_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE CASCADE)");
            addColumnIfMissing(c, "chat_messages", "message_type", "VARCHAR(30) NOT NULL DEFAULT 'TEXT'");
            addColumnIfMissing(c, "chat_messages", "forwarded_from_message_id", "BIGINT NULL");
            addColumnIfMissing(c, "chat_messages", "metadata_json", "TEXT NULL");
            addColumnIfMissing(c, "chat_messages", "workflow_status", "VARCHAR(30) NULL");
            st.execute("CREATE TABLE IF NOT EXISTS chat_attachments (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "message_id BIGINT NOT NULL," +
                    "original_name VARCHAR(255) NOT NULL," +
                    "stored_name VARCHAR(255) NOT NULL," +
                    "file_type VARCHAR(20) NOT NULL," +
                    "mime_type VARCHAR(120)," +
                    "file_size BIGINT NOT NULL," +
                    "shared_path VARCHAR(1000) NOT NULL," +
                    "CONSTRAINT fk_chat_attachments_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_read_receipts (" +
                    "message_id BIGINT NOT NULL," +
                    "username VARCHAR(50) NOT NULL," +
                    "read_at DATETIME NOT NULL," +
                    "PRIMARY KEY (message_id, username)," +
                    "CONSTRAINT fk_chat_receipts_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS tasks (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "message_id BIGINT NULL," +
                    "employee_id VARCHAR(20) NOT NULL," +
                    "description TEXT NOT NULL," +
                    "deadline DATE NULL," +
                    "status VARCHAR(30) NOT NULL DEFAULT 'OPEN'," +
                    "created_at DATETIME NOT NULL," +
                    "INDEX idx_tasks_employee_status (employee_id, status)," +
                    "CONSTRAINT fk_tasks_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE SET NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_tasks (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "conversation_id BIGINT NOT NULL," +
                    "title VARCHAR(255) NOT NULL," +
                    "description TEXT," +
                    "assignee_username VARCHAR(50) NOT NULL," +
                    "created_by VARCHAR(50) NOT NULL," +
                    "status VARCHAR(30) NOT NULL DEFAULT 'TODO'," +
                    "priority VARCHAR(30) NOT NULL DEFAULT 'MEDIUM'," +
                    "deadline DATETIME NULL," +
                    "kpi_points INT NOT NULL DEFAULT 0," +
                    "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                    "INDEX idx_chat_tasks_conversation_status_deadline (conversation_id, status, deadline)," +
                    "INDEX idx_chat_tasks_assignee (assignee_username, status)," +
                    "CONSTRAINT fk_chat_tasks_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_polls (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "message_id BIGINT NOT NULL," +
                    "question TEXT NOT NULL," +
                    "created_by VARCHAR(50) NOT NULL," +
                    "created_at DATETIME NOT NULL," +
                    "CONSTRAINT fk_chat_polls_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_poll_options (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "poll_id BIGINT NOT NULL," +
                    "option_text VARCHAR(500) NOT NULL," +
                    "sort_order INT NOT NULL DEFAULT 0," +
                    "CONSTRAINT fk_chat_poll_options_poll FOREIGN KEY (poll_id) REFERENCES chat_polls(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_poll_votes (" +
                    "poll_id BIGINT NOT NULL," +
                    "option_id BIGINT NOT NULL," +
                    "username VARCHAR(50) NOT NULL," +
                    "voted_at DATETIME NOT NULL," +
                    "PRIMARY KEY (poll_id, username)," +
                    "CONSTRAINT fk_chat_poll_votes_poll FOREIGN KEY (poll_id) REFERENCES chat_polls(id) ON DELETE CASCADE," +
                    "CONSTRAINT fk_chat_poll_votes_option FOREIGN KEY (option_id) REFERENCES chat_poll_options(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_workflows (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "message_id BIGINT NOT NULL," +
                    "workflow_type VARCHAR(30) NOT NULL," +
                    "employee_id VARCHAR(20) NOT NULL," +
                    "work_date DATE NULL," +
                    "payload_json TEXT NULL," +
                    "status VARCHAR(30) NOT NULL DEFAULT 'PENDING'," +
                    "decided_by VARCHAR(50) NULL," +
                    "decided_at DATETIME NULL," +
                    "created_at DATETIME NOT NULL," +
                    "INDEX idx_chat_workflows_status (status)," +
                    "CONSTRAINT fk_chat_workflows_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_bot_events (" +
                    "event_key VARCHAR(160) PRIMARY KEY," +
                    "created_at DATETIME NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_audit_logs (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "actor_username VARCHAR(50) NULL," +
                    "action VARCHAR(80) NOT NULL," +
                    "target_type VARCHAR(60) NULL," +
                    "target_id VARCHAR(120) NULL," +
                    "detail TEXT NULL," +
                    "ip_address VARCHAR(80) NULL," +
                    "created_at DATETIME NOT NULL," +
                    "INDEX idx_chat_audit_actor_time (actor_username, created_at)," +
                    "INDEX idx_chat_audit_action_time (action, created_at))");
            st.execute("CREATE TABLE IF NOT EXISTS chat_sessions (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "username VARCHAR(50) NOT NULL," +
                    "token_hash CHAR(64) NOT NULL UNIQUE," +
                    "issued_at DATETIME NOT NULL," +
                    "expires_at DATETIME NOT NULL," +
                    "revoked_at DATETIME NULL," +
                    "last_seen_at DATETIME NULL," +
                    "INDEX idx_chat_sessions_user (username, expires_at))");
            st.execute("CREATE TABLE IF NOT EXISTS admin_2fa_secrets (" +
                    "username VARCHAR(50) PRIMARY KEY," +
                    "secret_base32 VARCHAR(80) NOT NULL," +
                    "enabled BOOLEAN NOT NULL DEFAULT TRUE," +
                    "created_at DATETIME NOT NULL," +
                    "updated_at DATETIME NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_message_reactions (" +
                    "message_id BIGINT NOT NULL," +
                    "username VARCHAR(50) NOT NULL," +
                    "emoji VARCHAR(40) NOT NULL," +
                    "created_at DATETIME NOT NULL," +
                    "PRIMARY KEY (message_id, username, emoji)," +
                    "CONSTRAINT fk_chat_reactions_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_mentions (" +
                    "message_id BIGINT NOT NULL," +
                    "mentioned_username VARCHAR(50) NOT NULL," +
                    "created_at DATETIME NOT NULL," +
                    "PRIMARY KEY (message_id, mentioned_username)," +
                    "INDEX idx_chat_mentions_user (mentioned_username, created_at)," +
                    "CONSTRAINT fk_chat_mentions_message FOREIGN KEY (message_id) REFERENCES chat_messages(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_scheduled_messages (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "conversation_id BIGINT NOT NULL," +
                    "sender_username VARCHAR(50) NOT NULL," +
                    "body TEXT NOT NULL," +
                    "scheduled_at DATETIME NOT NULL," +
                    "sent_at DATETIME NULL," +
                    "status VARCHAR(30) NOT NULL DEFAULT 'PENDING'," +
                    "created_at DATETIME NOT NULL," +
                    "INDEX idx_chat_scheduled_due (status, scheduled_at)," +
                    "CONSTRAINT fk_chat_scheduled_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversations(id) ON DELETE CASCADE)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_typing_status (" +
                    "conversation_id BIGINT NOT NULL," +
                    "username VARCHAR(50) NOT NULL," +
                    "updated_at DATETIME NOT NULL," +
                    "PRIMARY KEY (conversation_id, username))");
            st.execute("CREATE TABLE IF NOT EXISTS chat_reminders (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "conversation_id BIGINT NULL," +
                    "username VARCHAR(50) NULL," +
                    "title VARCHAR(255) NOT NULL," +
                    "body TEXT NULL," +
                    "remind_at DATETIME NOT NULL," +
                    "sent_at DATETIME NULL," +
                    "status VARCHAR(30) NOT NULL DEFAULT 'PENDING'," +
                    "created_at DATETIME NOT NULL," +
                    "INDEX idx_chat_reminders_due (status, remind_at))");
            st.execute("CREATE TABLE IF NOT EXISTS chat_daily_stats (" +
                    "stat_date DATE NOT NULL," +
                    "company_owner VARCHAR(50) NOT NULL," +
                    "conversation_id BIGINT NOT NULL," +
                    "message_count INT NOT NULL DEFAULT 0," +
                    "active_users INT NOT NULL DEFAULT 0," +
                    "PRIMARY KEY (stat_date, company_owner, conversation_id))");
            st.execute("CREATE TABLE IF NOT EXISTS chat_keyword_stats (" +
                    "keyword VARCHAR(120) NOT NULL," +
                    "company_owner VARCHAR(50) NOT NULL," +
                    "use_count INT NOT NULL DEFAULT 0," +
                    "updated_at DATETIME NOT NULL," +
                    "PRIMARY KEY (keyword, company_owner))");
            st.execute("CREATE TABLE IF NOT EXISTS chat_messages_archive LIKE chat_messages");
            st.execute("CREATE TABLE IF NOT EXISTS chat_rate_limits (" +
                    "scope_key VARCHAR(160) PRIMARY KEY," +
                    "counter INT NOT NULL DEFAULT 0," +
                    "window_start DATETIME NOT NULL," +
                    "updated_at DATETIME NOT NULL)");
            st.execute("CREATE TABLE IF NOT EXISTS chat_notification_reads (" +
                    "username VARCHAR(50) NOT NULL," +
                    "event_key VARCHAR(160) NOT NULL," +
                    "read_at DATETIME NOT NULL," +
                    "PRIMARY KEY (username, event_key))");
            addColumnIfMissing(c, "chat_reminders", "body", "TEXT NULL");
            st.execute("INSERT IGNORE INTO users (username, password, role, company_code, full_name, email, phone) " +
                    "VALUES ('system_bot', '', 'SYSTEM', 'SYSTEM', 'System Notification Bot', '', '')");
        }
    }

    private static void addColumnIfMissing(Connection c, String table, String column, String definition) throws Exception {
        DatabaseMetaData meta = c.getMetaData();
        try (ResultSet rs = meta.getColumns(c.getCatalog(), null, table, column)) {
            if (rs.next()) {
                return;
            }
        }
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }
}
