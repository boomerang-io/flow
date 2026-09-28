package io.boomerang.workflow.config;

import io.boomerang.workflow.ArtifactStore;
import io.boomerang.workflow.S3ArtifactStore;
import java.net.URI;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.ProxyConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/*
 * The artifact store exists only when flow.artifacts.enabled is true; without it every artifact
 * call answers ARTIFACTS_NOT_CONFIGURED and the watcher's artifact sweeps do nothing. The client
 * goes through the same proxy.host/proxy.port as RestConfig, so an install behind an enterprise
 * proxy reaches its store the way it reaches everything else.
 */
@Configuration
@ConditionalOnProperty(name = "flow.artifacts.enabled", havingValue = "true")
public class ArtifactStoreConfiguration {

  @Value("${flow.artifacts.store.endpoint:}")
  private String endpoint;

  @Value("${flow.artifacts.store.link-endpoint:}")
  private String linkEndpoint;

  @Value("${flow.artifacts.store.region:us-east-1}")
  private String region;

  @Value("${flow.artifacts.store.bucket:flow-artifacts}")
  private String bucket;

  @Value("${flow.artifacts.store.access-key:}")
  private String accessKey;

  @Value("${flow.artifacts.store.secret-key:}")
  private String secretKey;

  @Value("${flow.artifacts.store.path-style:true}")
  private boolean pathStyle;

  @Value("${proxy.host:#{null}}")
  private Optional<String> proxyHost;

  @Value("${proxy.port:#{null}}")
  private Optional<String> proxyPort;

  @Bean
  public ArtifactStore artifactStore() {
    StaticCredentialsProvider credentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
    S3Configuration s3 = S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build();

    UrlConnectionHttpClient.Builder http = UrlConnectionHttpClient.builder();
    if (proxyHost.filter(host -> !host.isBlank()).isPresent()
        && proxyPort.filter(port -> !port.isBlank()).isPresent()) {
      http.proxyConfiguration(
          ProxyConfiguration.builder()
              .endpoint(URI.create("http://" + proxyHost.get() + ":" + proxyPort.get()))
              .build());
    }

    S3ClientBuilder client =
        S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .serviceConfiguration(s3)
            .httpClientBuilder(http);
    S3Presigner.Builder presigner =
        S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .serviceConfiguration(s3);
    if (!endpoint.isBlank()) {
      client.endpointOverride(URI.create(endpoint));
    }
    // Links name the host a task's pod reaches, which may differ from service-core's address.
    String links = linkEndpoint.isBlank() ? endpoint : linkEndpoint;
    if (!links.isBlank()) {
      presigner.endpointOverride(URI.create(links));
    }
    return new S3ArtifactStore(client.build(), presigner.build(), bucket);
  }
}
