package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.workflow.config.ArtifactStoreConfiguration;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * The artifact store against SeaweedFS, the S3-compatible store the compose stack runs. Logs and
 * artifacts of every shape store and read back through the client {@link
 * ArtifactStoreConfiguration} builds, and an upload through a presigned link completes. With the
 * SDK's default request checksums, six of these logs (plain text, 1352 to 2030 bytes) were refused
 * with "The Content-Md5 you specified is not valid".
 */
class S3ArtifactStoreSeaweedFsTest {

  private static final String ACCESS_KEY = "flow-local";
  private static final String SECRET_KEY = "flow-local-secret";
  private static final String BUCKET = "flow-artifacts";

  private static final GenericContainer<?> SEAWEEDFS =
      new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:4.47"))
          // Mounted, as compose mounts it, so the file exists when SeaweedFS reads it at startup; a
          // copied file can land too late.
          .withFileSystemBind(
              MountableFile.forClasspathResource("seaweedfs/s3.json").getResolvedPath(),
              "/etc/seaweedfs/s3.json",
              BindMode.READ_ONLY)
          .withCommand(
              "server",
              "-ip.bind=0.0.0.0",
              "-dir=/data",
              "-s3",
              "-s3.port=8333",
              "-s3.config=/etc/seaweedfs/s3.json",
              "-master.telemetry=false")
          .withExposedPorts(8333)
          .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));

  private static ArtifactStore store;
  private static String proxyHost;
  private static String proxyPort;

  @BeforeAll
  static void startStore() throws Exception {
    // Surefire passes http.proxyHost/Port through, unresolved when unset, and the store's HTTP client
    // honours them; this store is reached directly.
    proxyHost = System.clearProperty("http.proxyHost");
    proxyPort = System.clearProperty("http.proxyPort");
    SEAWEEDFS.start();
    // "localhost" can resolve to the IPv6 loopback, where some Docker hosts map nothing.
    String host = "localhost".equals(SEAWEEDFS.getHost()) ? "127.0.0.1" : SEAWEEDFS.getHost();
    String endpoint = "http://" + host + ":" + SEAWEEDFS.getMappedPort(8333);
    createBucket(endpoint);

    ArtifactStoreConfiguration configuration = new ArtifactStoreConfiguration();
    ReflectionTestUtils.setField(configuration, "endpoint", endpoint);
    ReflectionTestUtils.setField(configuration, "linkEndpoint", "");
    ReflectionTestUtils.setField(configuration, "region", "us-east-1");
    ReflectionTestUtils.setField(configuration, "bucket", BUCKET);
    ReflectionTestUtils.setField(configuration, "accessKey", ACCESS_KEY);
    ReflectionTestUtils.setField(configuration, "secretKey", SECRET_KEY);
    ReflectionTestUtils.setField(configuration, "pathStyle", true);
    ReflectionTestUtils.setField(configuration, "proxyHost", java.util.Optional.empty());
    ReflectionTestUtils.setField(configuration, "proxyPort", java.util.Optional.empty());
    store = configuration.artifactStore();
  }

  @AfterAll
  static void stopStore() {
    SEAWEEDFS.stop();
    if (proxyHost != null) {
      System.setProperty("http.proxyHost", proxyHost);
    }
    if (proxyPort != null) {
      System.setProperty("http.proxyPort", proxyPort);
    }
  }

  // The bucket exists before Flow starts in every deployment; the seed creates it in compose.
  private static void createBucket(String endpoint) throws Exception {
    S3Client admin =
        S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(URI.create(endpoint))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    // The S3 gateway answers before the filer behind it is ready; retry briefly.
    for (int attempt = 0; ; attempt++) {
      try {
        admin.createBucket(request -> request.bucket(BUCKET));
        return;
      } catch (RuntimeException e) {
        if (attempt >= 30) {
          throw new IllegalStateException("SeaweedFS never answered; its log:\n" + SEAWEEDFS.getLogs(), e);
        }
        Thread.sleep(1000);
      }
    }
  }

  private static byte[] roundTrip(String key, byte[] content) throws Exception {
    store.put(key, content, "text/plain; charset=utf-8");
    try (InputStream in = store.open(key)) {
      return in.readAllBytes();
    }
  }

  @Test
  void logsOfEveryShapeStoreAndReadBack() throws Exception {
    List<byte[]> logs = new ArrayList<>();
    logs.add(new byte[0]);
    logs.add("x".getBytes(StandardCharsets.UTF_8));
    logs.add("tick-1\n".getBytes(StandardCharsets.UTF_8));
    logs.add("héllo wörld ✓ 日本語\n".getBytes(StandardCharsets.UTF_8));
    Random random = new Random(42);
    for (int i = 0; i < 120; i++) {
      byte[] log = new byte[random.nextInt(i < 60 ? 2048 : 300_000)];
      for (int b = 0; b < log.length; b++) {
        log[b] = (byte) (32 + random.nextInt(95));
      }
      logs.add(log);
    }

    List<String> failures = new ArrayList<>();
    for (int i = 0; i < logs.size(); i++) {
      try {
        assertThat(roundTrip("logs/run-1/task-" + i + ".log", logs.get(i)))
            .as("log %d (%d bytes)", i, logs.get(i).length)
            .isEqualTo(logs.get(i));
      } catch (Exception | AssertionError e) {
        failures.add("log " + i + " (" + logs.get(i).length + " bytes): " + e.getMessage());
      }
    }
    assertThat(failures).isEmpty();
  }

  @Test
  void anUploadThroughAPresignedLinkCompletes() throws Exception {
    byte[] artifact = "report contents\n".getBytes(StandardCharsets.UTF_8);
    ArtifactStore.Link link = store.uploadLink("artifacts/run-1/report.txt", Duration.ofMinutes(5));
    HttpRequest.Builder put = HttpRequest.newBuilder(link.url()).PUT(HttpRequest.BodyPublishers.ofByteArray(artifact));
    link.headers().forEach(put::header);
    HttpResponse<String> response =
        HttpClient.newHttpClient().send(put.build(), HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(store.head("artifacts/run-1/report.txt")).hasValueSatisfying(
        stored -> assertThat(stored.size()).isEqualTo(artifact.length));
  }
}
