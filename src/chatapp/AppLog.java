package chatapp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class AppLog {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AppLog() {
    }

    public static void info(String message) {
        write("INFO", message, null);
    }

    public static void warn(String message) {
        write("WARN", message, null);
    }

    public static void warn(String message, Throwable error) {
        write("WARN", message, error);
    }

    public static void error(String message, Throwable error) {
        write("ERROR", message, error);
    }

    private static void write(String level, String message, Throwable error) {
        StringBuilder line = new StringBuilder()
                .append('[').append(LocalDateTime.now().format(TS)).append("] ")
                .append(level).append(" CHAT_NOI_BO - ")
                .append(message == null ? "" : message);
        if (error != null && error.getMessage() != null && !error.getMessage().isBlank()) {
            line.append(" | ").append(error.getClass().getSimpleName()).append(": ").append(error.getMessage());
        }
        System.err.println(line);
    }
}
