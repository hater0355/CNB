package chatapp;

import java.sql.Connection;
import java.sql.Statement;

final class SchemaManager {
    private final Database db;

    SchemaManager(Database db) {
        this.db = db;
    }

    void init() throws Exception {
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
        }
    }
}
