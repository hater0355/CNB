package chatapp;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

final class PendingMessageService {
    private final Path path;

    PendingMessageService() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            path = Path.of(appData, "CHAT_NOI_BO", "pending-messages.txt");
        } else {
            path = Path.of(System.getProperty("user.home"), ".CHAT_NOI_BO", "pending-messages.txt");
        }
    }

    synchronized void add(String username, long conversationId, String body) {
        add(username, conversationId, body, List.of());
    }

    synchronized void add(String username, long conversationId, String body, List<File> files) {
        if (body == null || body.isBlank()) return;
        try {
            Files.createDirectories(path.getParent());
            String encoded = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
            String encodedFiles = encodeFiles(files);
            String line = username + "|" + conversationId + "|" + LocalDateTime.now() + "|" + encoded + "|" + encodedFiles + System.lineSeparator();
            Files.writeString(path, line, StandardCharsets.UTF_8, Files.exists(path)
                    ? java.nio.file.StandardOpenOption.APPEND
                    : java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception e) {
            System.err.println("Cannot save pending message: " + e.getMessage());
        }
    }

    synchronized List<PendingMessage> list(String username) {
        List<PendingMessage> result = new ArrayList<>();
        if (!Files.isRegularFile(path)) return result;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\\|", 5);
                if (parts.length < 4 || !parts[0].equals(username)) continue;
                PendingMessage msg = new PendingMessage();
                msg.username = parts[0];
                msg.conversationId = Long.parseLong(parts[1]);
                msg.createdAt = parts[2];
                msg.body = new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8);
                if (parts.length == 5 && !parts[4].isBlank()) {
                    String fileText = new String(Base64.getDecoder().decode(parts[4]), StandardCharsets.UTF_8);
                    for (String item : fileText.split("\n")) {
                        if (!item.isBlank()) msg.filePaths.add(item);
                    }
                }
                result.add(msg);
            }
        } catch (Exception e) {
            System.err.println("Cannot read pending messages: " + e.getMessage());
        }
        return result;
    }

    synchronized void remove(String username, long conversationId, String body) {
        if (!Files.isRegularFile(path)) return;
        try {
            String encoded = Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8));
            List<String> kept = new ArrayList<>();
            boolean removed = false;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String prefix = username + "|" + conversationId + "|";
                if (!removed && line.startsWith(prefix) && line.endsWith("|" + encoded)) {
                    removed = true;
                    continue;
                }
                kept.add(line);
            }
            Files.write(path, kept, StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("Cannot update pending messages: " + e.getMessage());
        }
    }

    Path path() {
        return path;
    }

    private static String encodeFiles(List<File> files) {
        if (files == null || files.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (File file : files) {
            if (file != null && file.isFile()) {
                out.append(file.getAbsolutePath()).append('\n');
            }
        }
        return Base64.getEncoder().encodeToString(out.toString().getBytes(StandardCharsets.UTF_8));
    }
}

final class PendingMessage {
    String username;
    long conversationId;
    String body;
    String createdAt;
    final List<String> filePaths = new ArrayList<>();
}
