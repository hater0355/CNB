package chatapp;

import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class BackupService {
    private static final List<String> CHAT_TABLES = List.of(
            "chat_conversations",
            "chat_members",
            "chat_messages",
            "chat_attachments",
            "chat_read_receipts",
            "chat_tasks",
            "chat_message_reactions",
            "chat_mentions",
            "chat_scheduled_messages",
            "chat_reminders",
            "chat_workflows",
            "chat_polls",
            "chat_poll_options",
            "chat_poll_votes",
            "chat_audit_logs"
    );

    private final Database db;

    BackupService(Database db) {
        this.db = db;
    }

    Path exportChatBackup(CurrentUser user, Path targetZip) throws Exception {
        if (!user.isAdmin()) {
            throw new IllegalArgumentException("Chỉ admin được backup dữ liệu chat.");
        }
        Path parent = targetZip.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Connection c = db.getConnection();
             ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(targetZip), StandardCharsets.UTF_8)) {
            for (String table : CHAT_TABLES) {
                if (!tableExists(c, table)) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(table + ".csv"));
                OutputStreamWriter writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
                writeTableCsv(c, table, writer);
                writer.flush();
                zip.closeEntry();
            }
        }
        return targetZip;
    }

    int restoreChatBackup(CurrentUser user, Path sourceZip) throws Exception {
        if (!user.isAdmin()) {
            throw new IllegalArgumentException("Chỉ admin được restore dữ liệu chat.");
        }
        if (!Files.isRegularFile(sourceZip)) {
            throw new IllegalArgumentException("File backup không tồn tại.");
        }
        int imported = 0;
        try (Connection c = db.getConnection(); ZipInputStream zip = new ZipInputStream(Files.newInputStream(sourceZip), StandardCharsets.UTF_8)) {
            c.setAutoCommit(false);
            try {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory() || !entry.getName().endsWith(".csv")) continue;
                    String table = entry.getName().substring(0, entry.getName().length() - 4);
                    if (!CHAT_TABLES.contains(table) || !tableExists(c, table)) continue;
                    imported += importCsv(c, table, new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8)));
                    zip.closeEntry();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
        return imported;
    }

    private boolean tableExists(Connection c, String table) throws Exception {
        try (ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, table, null)) {
            return rs.next();
        }
    }

    private void writeTableCsv(Connection c, String table, OutputStreamWriter writer) throws Exception {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) writer.write(',');
                writer.write(csv(meta.getColumnLabel(i)));
            }
            writer.write('\n');
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    if (i > 1) writer.write(',');
                    writer.write(csv(rs.getString(i)));
                }
                writer.write('\n');
            }
        }
    }

    private int importCsv(Connection c, String table, BufferedReader reader) throws Exception {
        String header = reader.readLine();
        if (header == null || header.isBlank()) return 0;
        List<String> columns = parseCsvLine(header);
        if (columns.isEmpty()) return 0;
        String placeholders = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT IGNORE INTO " + table + " (" + String.join(",", columns) + ") VALUES (" + placeholders + ")";
        int count = 0;
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> values = parseCsvLine(line);
                if (values.size() != columns.size()) continue;
                for (int i = 0; i < values.size(); i++) {
                    String value = values.get(i);
                    if (value == null || value.isEmpty()) ps.setString(i + 1, null); else ps.setString(i + 1, value);
                }
                ps.addBatch();
                count++;
                if (count % 200 == 0) ps.executeBatch();
            }
            ps.executeBatch();
        }
        return count;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
