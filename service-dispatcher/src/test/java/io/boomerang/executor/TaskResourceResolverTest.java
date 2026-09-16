package io.boomerang.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The six {@code kube.resource.*} values size every task container this deployment runs. A blank
 * value means that request or limit is not set at all, so an operator can run with memory limits
 * and no CPU limit, or with none at all.
 */
class TaskResourceResolverTest {

  private static TaskResourceResolver resolver(
      String requestMemory,
      String limitMemory,
      String requestEphemeralStorage,
      String limitEphemeralStorage,
      String requestCpu,
      String limitCpu) {
    TaskResourceResolver resolver = new TaskResourceResolver();
    ReflectionTestUtils.setField(resolver, "requestMemory", requestMemory);
    ReflectionTestUtils.setField(resolver, "limitMemory", limitMemory);
    ReflectionTestUtils.setField(resolver, "requestEphemeralStorage", requestEphemeralStorage);
    ReflectionTestUtils.setField(resolver, "limitEphemeralStorage", limitEphemeralStorage);
    ReflectionTestUtils.setField(resolver, "requestCpu", requestCpu);
    ReflectionTestUtils.setField(resolver, "limitCpu", limitCpu);
    return resolver;
  }

  @Test
  void everyConfiguredValueReachesTheMatchingRequestOrLimit() {
    ResourceRequirements requirements =
        resolver("2Gi", "16Gi", "2Gi", "16Gi", "100m", "500m").requirements();

    assertEquals(new Quantity("2Gi"), requirements.getRequests().get("memory"));
    assertEquals(new Quantity("2Gi"), requirements.getRequests().get("ephemeral-storage"));
    assertEquals(new Quantity("100m"), requirements.getRequests().get("cpu"));
    assertEquals(new Quantity("16Gi"), requirements.getLimits().get("memory"));
    assertEquals(new Quantity("16Gi"), requirements.getLimits().get("ephemeral-storage"));
    assertEquals(new Quantity("500m"), requirements.getLimits().get("cpu"));
  }

  @Test
  void aBlankValueSetsNoRequestOrLimitRatherThanAnEmptyQuantity() {
    // The shipped default: memory and ephemeral-storage sized, CPU left to the operator.
    ResourceRequirements requirements =
        resolver("2Gi", "16Gi", "2Gi", "16Gi", "", "").requirements();

    assertEquals(2, requirements.getRequests().size());
    assertEquals(2, requirements.getLimits().size());
    assertFalse(requirements.getRequests().containsKey("cpu"));
    assertFalse(requirements.getLimits().containsKey("cpu"));
  }

  @Test
  void aSingleConfiguredValueIsTheOnlyOneSet() {
    ResourceRequirements requirements = resolver("", "16Gi", "", "", "", "").requirements();

    assertTrue(requirements.getRequests().isEmpty());
    assertEquals(1, requirements.getLimits().size());
    assertEquals(new Quantity("16Gi"), requirements.getLimits().get("memory"));
  }

  @Test
  void allSixBlankMeansNoResourcesBlockAtAll() {
    // Null, not an empty ResourceRequirements: setResources(null) leaves the container without one.
    assertNull(resolver("", "", "", "", "", "").requirements());
    assertNull(resolver(null, null, null, null, null, null).requirements());
  }

  @Test
  void aRuntimeThatTakesBytesGetsTheMemoryLimitInBytes() {
    // Docker takes a byte count, so the Kubernetes quantity is parsed rather than re-declared.
    assertEquals(16L * 1024 * 1024 * 1024, resolver("2Gi", "16Gi", "", "", "", "").memoryLimitBytes());
    assertEquals(512L * 1024 * 1024, resolver("", "512Mi", "", "", "", "").memoryLimitBytes());
    assertNull(resolver("2Gi", "", "", "", "", "").memoryLimitBytes());
  }

  @Test
  void aRuntimeThatTakesCpuCountsGetsTheCpuLimitInNanoCpus() {
    assertEquals(500_000_000L, resolver("", "", "", "", "", "500m").cpuLimitNanos());
    assertEquals(2_000_000_000L, resolver("", "", "", "", "", "2").cpuLimitNanos());
    assertNull(resolver("", "", "", "", "100m", "").cpuLimitNanos());
  }
}
