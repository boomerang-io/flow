package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/**
 * The writer is best-effort: a failed Mongo write never surfaces to the caller, and the record
 * maps onto the flat event document faithfully.
 */
class AuditEventWriterTest {

  private static AuditRecord record(AuditOutcome outcome) {
    return new AuditRecord(
        new AuditActor("u1", "Jane", "session", "t1"),
        "acme",
        AuditAction.CREATE,
        AuditLevel.WRITE,
        "workflow",
        "my-workflow",
        "My Workflow",
        outcome,
        "10.0.0.1",
        "curl",
        "POST",
        "/api/v2/workspace/acme/workflow",
        12L,
        null,
        null,
        java.util.Map.of("workflowRef", "wf1"));
  }

  @Test
  void aFailedWriteNeverThrows() {
    AuditEventRepository repository = mock(AuditEventRepository.class);
    when(repository.save(any())).thenThrow(new RuntimeException("mongo down"));

    assertThatCode(() -> new AuditEventWriter(repository).persist(record(AuditOutcome.SUCCESS)))
        .doesNotThrowAnyException();
  }

  @Test
  void theRecordMapsOntoTheFlatEvent() {
    AuditEventEntity event = AuditEventWriter.toEntity(record(AuditOutcome.DENIED));

    assertThat(event.getActorId()).isEqualTo("u1");
    assertThat(event.getActorName()).isEqualTo("Jane");
    assertThat(event.getActorType()).isEqualTo("session");
    assertThat(event.getWorkspaceId()).isEqualTo("acme");
    assertThat(event.getAction()).isEqualTo("CREATE");
    assertThat(event.getResourceType()).isEqualTo("workflow");
    assertThat(event.getResourceId()).isEqualTo("my-workflow");
    assertThat(event.getResourceName()).isEqualTo("My Workflow");
    assertThat(event.getOutcome()).isEqualTo("DENIED");
    assertThat(event.getLevel()).isEqualTo("WRITE");
    assertThat(event.getSubject()).isEqualTo("my-workflow");
    assertThat(event.getTime()).isNotNull();
    assertThat(event.getCreatedAt()).isNotNull();
    assertThat(event.getType()).isEqualTo("io.boomerang.flow.audit.v1");
    assertThat(event.getPayload())
        .containsEntry("sourceIp", "10.0.0.1")
        .containsEntry("userAgent", "curl")
        .containsEntry("httpMethod", "POST")
        .containsEntry("requestPath", "/api/v2/workspace/acme/workflow")
        .containsEntry("durationMs", 12L)
        .containsEntry("workflowRef", "wf1")
        .doesNotContainKeys("errorSummary", "detail");
  }
}
