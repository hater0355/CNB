package chatserver;

import java.util.function.Consumer;

final class InMemoryRealtimeBus implements RealtimeBus {
    private Consumer<String> onMessage;

    @Override
    public void start(Consumer<String> onMessage) {
        this.onMessage = onMessage;
    }

    @Override
    public void publish(String message) {
        if (onMessage != null) {
            onMessage.accept(message);
        }
    }

    @Override
    public void markOnline(String username) {
    }

    @Override
    public void markOffline(String username) {
    }

    @Override
    public void close() {
    }
}
