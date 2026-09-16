package io.boomerang.executor;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides the compute resources every Task container gets. Each {@link TaskExecutor} asks this
 * instead of reading {@code kube.resource.*} itself, so a task is sized the same way on Tekton,
 * Kubernetes Jobs and any later runtime.
 *
 * <p>The six values are per deployment, not per task: a workflow author cannot ask for a bigger
 * container, the same way an author cannot ask for an isolation tier. A deployment that needs
 * different sizing runs a second dispatcher with its own task types.
 *
 * <p>Every value tolerates being blank, and blank means that request or limit is not set at all -
 * never an empty {@code Quantity} and never a zero limit. Both CPU values are blank by default,
 * because a CPU limit throttles a task rather than failing it, which is a worse default than no
 * limit; memory and ephemeral-storage ship with values because a container without them can take a
 * node. With all six blank the container carries no resources block.
 *
 * <p>Memory covers more than the process: a memory-backed {@code /data}
 * ({@code kube.task.storage.data.memory} with the task's own {@code worker.storage.data.memory}
 * param) is a tmpfs, so what the task writes there counts against the memory limit rather than
 * against ephemeral-storage. That is the point of it - it is how a container that would breach the
 * ephemeral-storage limit keeps running - so a deployment that enables it sizes the memory limit to
 * cover the data as well.
 *
 * <p>Runtimes that take a byte count or a CPU count rather than a Kubernetes quantity (Docker)
 * read {@link #memoryLimitBytes()} and {@link #cpuLimitNanos()}; the configured strings are the
 * same. Docker has no ephemeral-storage concept, so that pair applies to Kubernetes only.
 */
@Component
public class TaskResourceResolver {

  static final String MEMORY = "memory";

  static final String EPHEMERAL_STORAGE = "ephemeral-storage";

  static final String CPU = "cpu";

  private static final BigDecimal NANOS_PER_CPU = BigDecimal.valueOf(1_000_000_000L);

  @Value("${kube.resource.request.memory}")
  private String requestMemory;

  @Value("${kube.resource.limit.memory}")
  private String limitMemory;

  @Value("${kube.resource.request.ephemeral-storage}")
  private String requestEphemeralStorage;

  @Value("${kube.resource.limit.ephemeral-storage}")
  private String limitEphemeralStorage;

  @Value("${kube.resource.request.cpu}")
  private String requestCpu;

  @Value("${kube.resource.limit.cpu}")
  private String limitCpu;

  /**
   * Return the requests and limits to set on the Task container, or {@code null} when none of the
   * six values is configured - so the container carries no resources block rather than an empty
   * one.
   */
  public ResourceRequirements requirements() {
    Map<String, Quantity> requests =
        quantities(requestMemory, requestEphemeralStorage, requestCpu);
    Map<String, Quantity> limits = quantities(limitMemory, limitEphemeralStorage, limitCpu);
    if (requests.isEmpty() && limits.isEmpty()) {
      return null;
    }
    ResourceRequirements requirements = new ResourceRequirements();
    if (!requests.isEmpty()) {
      requirements.setRequests(requests);
    }
    if (!limits.isEmpty()) {
      requirements.setLimits(limits);
    }
    return requirements;
  }

  /**
   * Return the memory limit in bytes for a runtime that takes a byte count, or {@code null} when it
   * is not configured.
   */
  public Long memoryLimitBytes() {
    BigDecimal bytes = amount(limitMemory);
    return (bytes != null ? bytes.longValue() : null);
  }

  /**
   * Return the CPU limit in nano-CPUs for a runtime that takes a CPU count, or {@code null} when it
   * is not configured. One Kubernetes CPU is 1,000,000,000 nano-CPUs, so {@code 500m} is
   * {@code 500000000}.
   */
  public Long cpuLimitNanos() {
    BigDecimal cpus = amount(limitCpu);
    return (cpus != null ? cpus.multiply(NANOS_PER_CPU).longValue() : null);
  }

  private Map<String, Quantity> quantities(String memory, String ephemeralStorage, String cpu) {
    Map<String, Quantity> quantities = new LinkedHashMap<>();
    put(quantities, MEMORY, memory);
    put(quantities, EPHEMERAL_STORAGE, ephemeralStorage);
    put(quantities, CPU, cpu);
    return quantities;
  }

  private void put(Map<String, Quantity> quantities, String resource, String value) {
    if (value != null && !value.isBlank()) {
      quantities.put(resource, new Quantity(value.trim()));
    }
  }

  /** The numeric value of a Kubernetes quantity ({@code 16Gi}, {@code 500m}), or null when blank. */
  private BigDecimal amount(String value) {
    return (value != null && !value.isBlank()
        ? Quantity.getAmountInBytes(new Quantity(value.trim()))
        : null);
  }
}
