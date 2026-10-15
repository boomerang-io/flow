import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Container health probe for the Java images, which run on a chiseled JRE with no shell, curl or
 * wget (decision 0108): exits 0 when the URL answers with a 2xx, 1 otherwise. Compiled in each
 * image's build stage and copied beside the jar; docker-compose.yml's healthchecks run it as
 * {@code java -cp /opt/boomerang Health http://localhost:7700/health}. Kubernetes probes are HTTP
 * from the kubelet and need nothing in the container.
 */
public final class Health {
  public static void main(String[] args) throws Exception {
    String url = args.length > 0 ? args[0] : "http://localhost:7700/health";
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build();
    HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
    System.exit(response.statusCode() / 100 == 2 ? 0 : 1);
  }
}
