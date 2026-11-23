package io.boomerang.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowRunTransition;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UnmeteredWorkflows;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.security.IdentityService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * A failed read while telling whether a run is unmetered never costs the run its audit events: it
 * is counted as metered, its CREATE and UPDATE are written, and the UPDATE carries its minutes.
 */
class WorkflowRunAuditBridgeUnmeteredReadTest {

  @Test
  @SuppressWarnings("unchecked")
  void aFailedUnmeteredReadCountsTheRunAsMetered() {
    AuditEventEmitter emitter = mock(AuditEventEmitter.class);
    when(emitter.captureEnabled(AuditLevel.WRITE)).thenReturn(true);
    RelationshipService relationships = mock(RelationshipService.class);
    when(relationships.getParentByLabel(any(), any(), anyString())).thenReturn("workspace-ref");
    when(relationships.nodesByRef(anyCollection(), any(), any()))
        .thenReturn(
            Map.of(
                "workflow-ref", node("workflow", "nightly"),
                "workspace-ref", node("workspace", "team")));
    UnmeteredWorkflows unmetered = mock(UnmeteredWorkflows.class);
    when(unmetered.test(anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("workflows read failed"));
    // No task runs and no run document: the deep stubs answer an empty find and a null findOne.
    MongoTemplate mongoTemplate = mock(MongoTemplate.class, RETURNS_DEEP_STUBS);
    WorkflowRunAuditBridge bridge =
        new WorkflowRunAuditBridge(
            emitter, mock(IdentityService.class), relationships, mongoTemplate, unmetered);

    // A cancel straight out of notstarted: the run's CREATE and its UPDATE at once.
    bridge.onWorkflowRunTransition(
        new WorkflowRunTransition(
            "run-1",
            "workflow-ref",
            RunStatus.notstarted,
            RunPhase.pending,
            RunStatus.cancelled,
            RunPhase.completed));

    ArgumentCaptor<Map<String, Object>> created = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<Map<String, Object>> updated = ArgumentCaptor.forClass(Map.class);
    verify(emitter)
        .emitAs(
            any(), eq(AuditAction.CREATE), any(), any(), eq("run-1"), eq("nightly"), eq("team"),
            created.capture());
    verify(emitter)
        .emitAs(
            any(), eq(AuditAction.UPDATE), any(), any(), eq("run-1"), eq("nightly"), eq("team"),
            updated.capture());
    assertThat(created.getValue()).doesNotContainKey("unmetered");
    assertThat(updated.getValue())
        .doesNotContainKey("unmetered")
        .containsEntry("cumulativeDuration", 0L);
  }

  private static RelationshipNodeEntity node(String type, String slug) {
    RelationshipNodeEntity node = new RelationshipNodeEntity();
    node.setType(type);
    node.setSlug(slug);
    return node;
  }
}
