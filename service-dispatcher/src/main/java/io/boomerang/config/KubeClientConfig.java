package io.boomerang.config;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.tekton.client.TektonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/*
 * One shared client, auto-configured from the in-cluster service account or the local
 * kubeconfig. Tests override both beans so no kubeconfig (or its exec credential plugin) is read.
 */
@Configuration
public class KubeClientConfig {

  @Bean
  @Lazy
  public KubernetesClient kubernetesClient() {
    return new KubernetesClientBuilder().build();
  }

  @Bean
  @Lazy
  public TektonClient tektonClient(KubernetesClient kubernetesClient) {
    return kubernetesClient.adapt(TektonClient.class);
  }
}
