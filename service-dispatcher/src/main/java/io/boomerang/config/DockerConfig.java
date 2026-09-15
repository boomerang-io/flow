package io.boomerang.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One shared Docker Engine API client for the docker executor, over the httpclient5 transport.
 * {@code dispatcher.docker.host} wins when set; otherwise docker-java reads {@code DOCKER_HOST} and
 * falls back to the local socket. No response timeout is set: following a task's log blocks until
 * the container exits.
 */
@Configuration
@ConditionalOnProperty(name = "dispatcher.executor", havingValue = "docker")
public class DockerConfig {

  @Bean
  public DockerClient dockerClient(@Value("${dispatcher.docker.host:}") String dockerHost) {
    DefaultDockerClientConfig.Builder builder = DefaultDockerClientConfig.createDefaultConfigBuilder();
    if (dockerHost != null && !dockerHost.isBlank()) {
      builder.withDockerHost(dockerHost);
    }
    DockerClientConfig config = builder.build();
    ApacheDockerHttpClient httpClient =
        new ApacheDockerHttpClient.Builder()
            .dockerHost(config.getDockerHost())
            .sslConfig(config.getSSLConfig())
            .connectionTimeout(Duration.ofSeconds(30))
            .build();
    return DockerClientImpl.getInstance(config, httpClient);
  }
}
