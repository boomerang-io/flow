package io.boomerang.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.tekton.client.TektonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/*
 * Picked up by every @SpringBootTest through component scanning. The clients carry an empty
 * Config so the developer's kubeconfig (and any exec credential plugin such as gcloud) is never
 * consulted; tests that talk to Kubernetes swap in the @EnableKubernetesMockClient client.
 *
 * The placeholder points at a closed local port with no retries, so anything that reaches it
 * before a test has swapped the mock client in fails in milliseconds instead of waiting out a
 * name lookup for the in-cluster service address and the client's retry backoff.
 */
@Configuration
public class TestKubeClientConfig {

  @Bean
  @Primary
  public KubernetesClient testKubernetesClient() {
    Config config =
        new ConfigBuilder(Config.empty())
            .withMasterUrl("http://127.0.0.1:1")
            .withConnectionTimeout(200)
            .withRequestTimeout(200)
            .withRequestRetryBackoffLimit(0)
            .build();
    return new KubernetesClientBuilder().withConfig(config).build();
  }

  @Bean
  @Primary
  public TektonClient testTektonClient(KubernetesClient testKubernetesClient) {
    return testKubernetesClient.adapt(TektonClient.class);
  }
}
