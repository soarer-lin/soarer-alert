import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 容器健康检查入口，用于探测应用进程是否可用。
 */
public final class HealthCheck {
    private HealthCheck() {
    }

    public static void main(String[] args) {
        String port = System.getenv().getOrDefault(
                "MANAGEMENT_SERVER_PORT",
                System.getenv().getOrDefault("SERVER_PORT", "9900")
        );
        URI uri = URI.create("http://127.0.0.1:" + port + "/actuator/health");

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<Void> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.discarding()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.exit(1);
            }
        } catch (Exception exception) {
            System.exit(1);
        }
    }
}
