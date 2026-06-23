package chatserver;

import chatapp.AppConfig;
import chatapp.AppLog;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class RedisRealtimeBus implements RealtimeBus {
    private final AppConfig config;
    private volatile boolean running;
    private Consumer<String> localConsumer;
    private Thread subscriberThread;
    private RespConnection publishConnection;
    private RespConnection subscribeConnection;

    RedisRealtimeBus(AppConfig config) {
        this.config = config;
    }

    @Override
    public void start(Consumer<String> onMessage) {
        this.localConsumer = onMessage;
        running = true;
        subscriberThread = new Thread(() -> subscribeLoop(onMessage), "chat-redis-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    @Override
    public void publish(String message) {
        try {
            if (publishConnection == null) {
                publishConnection = connect();
            }
            publishConnection.command("PUBLISH", config.redisChannel, message);
        } catch (Exception e) {
            AppLog.warn("Không publish được event lên Redis, bỏ qua bridge Redis.", e);
            if (localConsumer != null) {
                localConsumer.accept(message);
            }
            closeQuietly(publishConnection);
            publishConnection = null;
        }
    }

    @Override
    public void markOnline(String username) {
        if (username == null || username.isBlank()) return;
        try {
            if (publishConnection == null) {
                publishConnection = connect();
            }
            publishConnection.command("SETEX", "chat:presence:" + username, String.valueOf(config.redisPresenceTtlSeconds), "online");
        } catch (Exception e) {
            AppLog.warn("Không cập nhật presence Redis.", e);
            closeQuietly(publishConnection);
            publishConnection = null;
        }
    }

    @Override
    public void markOffline(String username) {
        if (username == null || username.isBlank()) return;
        try {
            if (publishConnection == null) {
                publishConnection = connect();
            }
            publishConnection.command("DEL", "chat:presence:" + username);
        } catch (Exception e) {
            AppLog.warn("Không xóa presence Redis.", e);
            closeQuietly(publishConnection);
            publishConnection = null;
        }
    }

    @Override
    public void close() {
        running = false;
        closeQuietly(publishConnection);
        closeQuietly(subscribeConnection);
    }

    private void subscribeLoop(Consumer<String> onMessage) {
        while (running) {
            try {
                subscribeConnection = connect();
                subscribeConnection.command("SUBSCRIBE", config.redisChannel);
                while (running) {
                    Object frame = subscribeConnection.readFrame();
                    if (frame instanceof List<?> parts && parts.size() >= 3 && "message".equals(String.valueOf(parts.get(0)))) {
                        onMessage.accept(String.valueOf(parts.get(2)));
                    }
                }
            } catch (Exception e) {
                if (running) {
                    AppLog.warn("Mất kết nối Redis Pub/Sub, sẽ thử lại sau.", e);
                    sleep(3000);
                }
            } finally {
                closeQuietly(subscribeConnection);
                subscribeConnection = null;
            }
        }
    }

    private RespConnection connect() throws IOException {
        RespConnection connection = new RespConnection(config.redisHost, config.redisPort);
        if (config.redisPassword != null && !config.redisPassword.isBlank()) {
            connection.command("AUTH", config.redisPassword);
        }
        if (config.redisDatabase > 0) {
            connection.command("SELECT", String.valueOf(config.redisDatabase));
        }
        return connection;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Exception ignored) {
        }
    }

    private static final class RespConnection implements Closeable {
        private final Socket socket;
        private final BufferedInputStream in;
        private final BufferedOutputStream out;

        RespConnection(String host, int port) throws IOException {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            in = new BufferedInputStream(socket.getInputStream());
            out = new BufferedOutputStream(socket.getOutputStream());
        }

        synchronized Object command(String... args) throws IOException {
            writeCommand(args);
            return readFrame();
        }

        private void writeCommand(String... args) throws IOException {
            out.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (String arg : args) {
                byte[] data = arg.getBytes(StandardCharsets.UTF_8);
                out.write(("$" + data.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(data);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        }

        Object readFrame() throws IOException {
            int type = in.read();
            if (type == -1) throw new IOException("Redis connection closed");
            return switch (type) {
                case '+' -> readLine();
                case '-' -> throw new IOException(readLine());
                case ':' -> Long.parseLong(readLine());
                case '$' -> readBulk();
                case '*' -> readArray();
                default -> throw new IOException("Unknown Redis frame: " + (char) type);
            };
        }

        private String readBulk() throws IOException {
            int len = Integer.parseInt(readLine());
            if (len < 0) return "";
            byte[] data = in.readNBytes(len);
            readCrlf();
            return new String(data, StandardCharsets.UTF_8);
        }

        private List<Object> readArray() throws IOException {
            int len = Integer.parseInt(readLine());
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < len; i++) {
                out.add(readFrame());
            }
            return out;
        }

        private String readLine() throws IOException {
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1) {
                if (ch == '\r') {
                    int next = in.read();
                    if (next != '\n') throw new IOException("Invalid Redis line ending");
                    return line.toString();
                }
                line.append((char) ch);
            }
            throw new IOException("Redis connection closed");
        }

        private void readCrlf() throws IOException {
            if (in.read() != '\r' || in.read() != '\n') {
                throw new IOException("Invalid Redis bulk ending");
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
