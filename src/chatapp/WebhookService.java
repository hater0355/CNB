package chatapp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

final class WebhookService {
    private final AppConfig config;
    private final HttpClient client = HttpClient.newHttpClient();

    WebhookService(AppConfig config) {
        this.config = config;
    }

    void notifySlack(String text) {
        post(config.slackWebhookUrl, "{\"text\":\"" + json(text) + "\"}");
    }

    void notifyTeams(String title, String text) {
        post(config.teamsWebhookUrl, "{\"title\":\"" + json(title) + "\",\"text\":\"" + json(text) + "\"}");
    }

    void notifyIntegrations(String title, String text) {
        notifySlack(title + "\n" + text);
        notifyTeams(title, text);
    }

    void notifyIntegrations(String title, String text, String slackWebhookUrl, String teamsWebhookUrl) {
        String slack = slackWebhookUrl == null || slackWebhookUrl.isBlank() ? config.slackWebhookUrl : slackWebhookUrl;
        String teams = teamsWebhookUrl == null || teamsWebhookUrl.isBlank() ? config.teamsWebhookUrl : teamsWebhookUrl;
        post(slack, "{\"text\":\"" + json(title + "\n" + text) + "\"}");
        post(teams, "{\"title\":\"" + json(title) + "\",\"text\":\"" + json(text) + "\"}");
    }

    private void post(String url, String payload) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url.trim()))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            AppLog.warn("Gửi webhook thất bại.", e);
        }
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}
