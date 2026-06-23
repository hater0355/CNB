package chatapp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AppLog {
    private static final Logger LOG = LoggerFactory.getLogger("CHAT_NOI_BO");

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
        String safe = message == null ? "" : message;
        if ("ERROR".equals(level)) {
            LOG.error(safe, error);
        } else if ("WARN".equals(level)) {
            if (error == null) {
                LOG.warn(safe);
            } else {
                LOG.warn(safe, error);
            }
        } else {
            LOG.info(safe);
        }
    }
}
