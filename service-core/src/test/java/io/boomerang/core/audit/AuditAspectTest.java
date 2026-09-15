package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

/**
 * The aspect's contract: one record per attempt with the right outcome, SpEL resolved off the
 * method arguments and result, level gating at capture time, and the disabled path doing nothing
 * but proceed. The target is a plain object behind an AspectJ proxy - no Spring context.
 */
class AuditAspectTest {

  private AuditEventEmitter emitter;
  private AuditEventWriter writer;
  private AuditedTarget target;

  /** Stands in for a controller: an audited happy path, a failure, and a denial. */
  public static class AuditedTarget {

    @Audited(
        action = AuditAction.CREATE,
        resourceType = "workflow",
        resourceId = "#result?.toUpperCase()",
        resourceName = "#name",
        workspaceId = "#workspace")
    public String create(String workspace, String name) {
      return name;
    }

    @Audited(action = AuditAction.UPDATE, resourceType = "workflow", resourceId = "#name")
    public String fail(String name) {
      throw new IllegalStateException("boom");
    }

    @Audited(action = AuditAction.UPDATE, resourceType = "workflow", resourceId = "#name")
    public String deny(String name) {
      throw new BoomerangException(BoomerangError.PERMISSION_DENIED);
    }

    @Audited(action = AuditAction.UPDATE, resourceType = "workflow", resourceId = "#name")
    public String denySpring(String name) {
      throw new org.springframework.security.access.AccessDeniedException("no");
    }

    @Audited(
        action = AuditAction.DELETE,
        resourceType = "workflow",
        resourceId = "#name",
        level = AuditLevel.DESTRUCTIVE)
    public void destroy(String name) {}
  }

  @BeforeEach
  void proxyTarget() {
    emitter = mock(AuditEventEmitter.class);
    writer = mock(AuditEventWriter.class);
    when(emitter.currentActor(any())).thenReturn(AuditActor.system());
    AspectJProxyFactory factory = new AspectJProxyFactory(new AuditedTarget());
    factory.addAspect(new AuditAspect(emitter, writer));
    target = factory.getProxy();
  }

  @Test
  void successRecordsOneEventWithSpelResolvedFromArgumentsAndResult() {
    when(emitter.captureEnabled(AuditLevel.WRITE)).thenReturn(true);

    String result = target.create("acme", "my-workflow");

    assertThat(result).isEqualTo("my-workflow");
    ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(writer).persist(captor.capture());
    AuditRecord record = captor.getValue();
    assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCESS);
    assertThat(record.action()).isEqualTo(AuditAction.CREATE);
    assertThat(record.resourceType()).isEqualTo("workflow");
    assertThat(record.resourceId()).isEqualTo("MY-WORKFLOW");
    assertThat(record.resourceName()).isEqualTo("my-workflow");
    assertThat(record.workspaceId()).isEqualTo("acme");
    assertThat(record.actor().id()).isEqualTo("system");
    assertThat(record.durationMs()).isNotNull();
  }

  @Test
  void failureRecordsFailedWithTheErrorSummaryAndAlwaysRethrows() {
    when(emitter.captureEnabled(AuditLevel.WRITE)).thenReturn(true);

    assertThatThrownBy(() -> target.fail("my-workflow")).isInstanceOf(IllegalStateException.class);

    ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(writer).persist(captor.capture());
    AuditRecord record = captor.getValue();
    assertThat(record.outcome()).isEqualTo(AuditOutcome.FAILED);
    assertThat(record.errorSummary()).isEqualTo("IllegalStateException: boom");
    // #result is null on failure - the null-safe SpEL yields null rather than breaking.
    assertThat(record.resourceId()).isEqualTo("my-workflow");
  }

  @Test
  void refusedAuthorizationRecordsDeniedAndAlwaysRethrows() {
    when(emitter.captureEnabled(AuditLevel.WRITE)).thenReturn(true);

    assertThatThrownBy(() -> target.deny("my-workflow"))
        .isInstanceOf(BoomerangException.class)
        .extracting("reason")
        .isEqualTo("PERMISSION_DENIED");

    ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(writer).persist(captor.capture());
    assertThat(captor.getValue().outcome()).isEqualTo(AuditOutcome.DENIED);
  }

  @Test
  void springAccessDeniedRecordsDenied() {
    when(emitter.captureEnabled(AuditLevel.WRITE)).thenReturn(true);

    assertThatThrownBy(() -> target.denySpring("wf"))
        .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

    ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(writer).persist(captor.capture());
    assertThat(captor.getValue().outcome()).isEqualTo(AuditOutcome.DENIED);
  }

  @Test
  void disabledSiteProceedsWithoutCaptureWork() {
    when(emitter.captureEnabled(AuditLevel.WRITE)).thenReturn(false);

    assertThat(target.create("acme", "my-workflow")).isEqualTo("my-workflow");

    verify(writer, never()).persist(any());
  }

  @Test
  void levelGateIsAskedWithTheSiteLevel() {
    // A DESTRUCTIVE site asks the gate at DESTRUCTIVE, so a DESTRUCTIVE-only configured
    // instance still captures deletes while skipping WRITE sites.
    when(emitter.captureEnabled(AuditLevel.DESTRUCTIVE)).thenReturn(true);
    when(emitter.captureEnabled(AuditLevel.WRITE)).thenReturn(false);

    target.destroy("my-workflow");
    target.create("acme", "my-workflow");

    ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(writer).persist(captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.DELETE);
    assertThat(captor.getValue().level()).isEqualTo(AuditLevel.DESTRUCTIVE);
  }
}
