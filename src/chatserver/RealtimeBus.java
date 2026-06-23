package chatserver;

import java.util.function.Consumer;

interface RealtimeBus extends AutoCloseable {
    void start(Consumer<String> onMessage);

    void publish(String message);

    void markOnline(String username);

    void markOffline(String username);

    @Override
    void close();
}
