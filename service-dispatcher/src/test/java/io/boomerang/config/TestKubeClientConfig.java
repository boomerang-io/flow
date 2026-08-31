package io.boomerang.config;

import io.fabric8.kubernetes.client.Config;
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
 */
@Configuration
public class TestKubeClientConfig {

  @Bean
  @Primary
  public KubernetesClient testKubernetesClient() {
    return new KubernetesClientBuilder().withConfig(Config.empty()).build();
  }

  @Bean
  @Primary
  public TektonClient testTektonClient(KubernetesClient testKubernetesClient) {
    return testKubernetesClient.adapt(TektonClient.class);
  }
}
