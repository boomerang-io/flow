package io.boomerang.config;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.tekton.client.TektonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/*
 * One shared client, auto-configured from the in-cluster service account or the local
 * kubeconfig. Tests override both beans so no kubeconfig (or its exec credential plugin) is read.
 */
@Configuration
public class KubeClientConfig {

  private final String kubeNamespace;

  public KubeClientConfig(@Value("${kube.namespace:}") String kubeNamespace) {
    this.kubeNamespace = kubeNamespace;
  }

  @Bean
  @Lazy
  public KubernetesClient kubernetesClient() {
    return buildClient(Config.autoConfigure(null));
  }

  // kube.namespace wins when set; otherwise the namespace must already resolve from the
  // kubeconfig context or (in-cluster) the service account. Fail fast rather than let the
  // first task job fail with a Kubernetes "namespace not specified" error.
  KubernetesClient buildClient(Config config) {
    if (kubeNamespace != null && !kubeNamespace.isBlank()) {
      config.setNamespace(kubeNamespace);
    }
    KubernetesClient client = new KubernetesClientBuilder().withConfig(config).build();
    if (client.getNamespace() == null || client.getNamespace().isBlank()) {
      throw new IllegalStateException(
          "No Kubernetes namespace resolved for the dispatcher client: set kube.namespace, "
              + "or give the kubeconfig context a namespace.");
    }
    return client;
  }

  @Bean
  @Lazy
  public TektonClient tektonClient(KubernetesClient kubernetesClient) {
    return kubernetesClient.adapt(TektonClient.class);
  }
}
