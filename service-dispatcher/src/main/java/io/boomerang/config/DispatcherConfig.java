package io.boomerang.config;

import io.boomerang.dispatcher.TaskService;
import io.boomerang.dispatcher.WorkflowService;
import io.boomerang.dispatcher.sdk.Dispatcher;
import io.boomerang.dispatcher.sdk.DispatcherClient;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Flow's Kubernetes dispatcher on the dispatcher SDK: the SDK registers, polls, starts, renews
 * leases and reports ends; {@link TaskService} runs each task as a Kubernetes object and {@link
 * WorkflowService} provisions each workflow run's volumes.
 */
@Configuration
public class DispatcherConfig {

  // The engine calls keep RestConfig's pooled transport and timeouts; its 60 s read outlasts the
  // engine's 30 s long-poll hold.
  @Bean
  public DispatcherClient dispatcherClient(
      RestConfig restConfig,
      @Value("${flow.engine.url}") String engineUrl,
      @Value("${flow.engine.dispatcher.token:}") String token) {
    RestClient http =
        RestClient.builder().requestFactory(restConfig.clientHttpRequestFactory()).build();
    return new DispatcherClient(http, engineUrl, token);
  }

  @Bean(initMethod = "start", destroyMethod = "stop")
  public Dispatcher dispatcher(
      DispatcherClient dispatcherClient,
      TaskService taskService,
      WorkflowService workflowService,
      @Value("${flow.dispatcher.name}") String name,
      @Value("${flow.dispatcher.task-types}") List<String> taskTypes,
      @Value("${flow.dispatcher.task.max-in-flight:25}") int maxInFlight,
      @Value("${flow.dispatcher.lease.enabled:true}") boolean leaseEnabled,
      @Value("${flow.dispatcher.lease.beat-ms:30000}") long beatMs) {
    return Dispatcher.builder()
        .client(dispatcherClient)
        .name(name)
        .taskTypes(taskTypes)
        .tasks(taskService, () -> maxInFlight)
        .workflows(workflowService)
        .heartbeatInterval(leaseEnabled ? Duration.ofMillis(beatMs) : Duration.ZERO)
        .build();
  }
}
