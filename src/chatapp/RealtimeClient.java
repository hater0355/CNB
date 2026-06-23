package chatapp;

import chatshared.JsonUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class RealtimeClient implements WebSocket.Listener {
    private final AppConfig config;
    private final Consumer<Map<String, String>> onEvent;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "chat-realtime-client");
        thread.setDaemon(true);
        return thread;
    });
    private volatile WebSocket socket;
    private volatile String username = "";
    private volatile String token = "";
    private volatile boolean closedByUser;
    private final StringBuilder buffer = new StringBuilder();

    RealtimeClient(AppConfig config, Consumer<Map<String, String>> onEvent) {
        this.config = config;
        this.onEvent = onEvent;
    }

    void connect(String username, String token) {
        this.username = username == null ? "" : username;
        this.token = token == null ? "" : token;
        this.closedByUser = false;
        executor.submit(this::connectInternal);
    }

    void publish(String type, long conversationId) {
        WebSocket ws = socket;
        if (ws == null || ws.isOutputClosed()) {
            return;
        }
        ws.sendText(JsonUtil.stringify(JsonUtil.event(type, conversationId, username)), true);
    }

    void publishTyping(long conversationId) {
        publish("TYPING", conversationId);
    }

    void close() {
        closedByUser = true;
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        executor.shutdownNow();
    }

    private void connectInternal() {
        try {
            socket = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build()
                    .newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .buildAsync(URI.create(config.realtimeUrl), this)
                    .join();
            sendAuth();
        } catch (Exception e) {
            AppLog.warn("Realtime server chưa sẵn sàng, sẽ thử kết nối lại.", e);
            scheduleReconnect();
        }
    }

    private void sendAuth() {
        WebSocket ws = socket;
        if (ws != null) {
            ws.sendText(JsonUtil.stringify(Map.of("type", "AUTH", "token", token)), true);
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        WebSocket.Listener.super.onOpen(webSocket);
        sendAuth();
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        buffer.append(data);
        if (last) {
            Map<String, String> event = JsonUtil.parseObject(buffer.toString());
            buffer.setLength(0);
            String type = event.getOrDefault("type", "");
            if (type.endsWith("_UPDATED") || type.endsWith("_CREATED") || "BOT_BIRTHDAY".equals(type) || "TYPING".equals(type)) {
                onEvent.accept(event);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        socket = null;
        if (!closedByUser) {
            AppLog.warn("Realtime đã ngắt kết nối: " + statusCode + " " + reason);
            scheduleReconnect();
        }
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        socket = null;
        if (!closedByUser) {
            AppLog.warn("Lỗi kết nối realtime, sẽ thử lại.", error);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (closedByUser || executor.isShutdown()) {
            return;
        }
        executor.submit(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!closedByUser && socket == null) {
                connectInternal();
            }
        });
    }
}
