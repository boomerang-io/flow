package io.boomerang.workflow;

import io.boomerang.common.enums.ArtifactStatus;
import io.boomerang.core.audit.AuditAction;
import io.boomerang.core.audit.AuditLevel;
import io.boomerang.core.audit.Audited;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.boomerang.workflow.ArtifactService.ArtifactFile;
import io.boomerang.workflow.model.Artifact;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/*
 * Artifacts: listed and fetched through their run, and listed across the workspace to manage
 * storage. Every call checks the run's (or the artifact's workflow's) relationship to the
 * workspace in the path. A download streams through service-core; no store link reaches the
 * caller.
 */
@RestController
@RequestMapping("/api/v2/workspace/{workspace}")
@Tag(name = "Artifacts", description = "List, download and delete the files tasks upload to runs.")
@SecurityRequirement(name = "BearerAuth")
@SecurityRequirement(name = "x-access-token")
public class WorkspaceArtifactControllerV2 {

  private final ArtifactService artifactService;

  public WorkspaceArtifactControllerV2(ArtifactService artifactService) {
    this.artifactService = artifactService;
  }

  @GetMapping(value = "/workflowrun/{workflowRunId}/artifacts")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOWRUN,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(summary = "List a WorkflowRun's artifacts, expired ones included")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "404", description = "Not Found")
      })
  public List<Artifact> listForRun(
      @Parameter(name = "workspace", description = "Owning workspace name.", required = true)
          @PathVariable
          String workspace,
      @Parameter(name = "workflowRunId", description = "WorkflowRun ID", required = true)
          @PathVariable
          String workflowRunId) {
    return artifactService.listForRun(workspace, workflowRunId);
  }

  @GetMapping(value = "/workflowrun/{workflowRunId}/artifacts/{name}")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOWRUN,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(summary = "Download an artifact's file")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "404", description = "Not Found"),
        @ApiResponse(responseCode = "410", description = "Expired")
      })
  public ResponseEntity<StreamingResponseBody> download(
      @Parameter(name = "workspace", description = "Owning workspace name.", required = true)
          @PathVariable
          String workspace,
      @Parameter(name = "workflowRunId", description = "WorkflowRun ID", required = true)
          @PathVariable
          String workflowRunId,
      @Parameter(name = "name", description = "Artifact name", required = true) @PathVariable
          String name) {
    ArtifactFile file = artifactService.open(workspace, workflowRunId, name);
    StreamingResponseBody body =
        out -> {
          try (InputStream in = file.content()) {
            in.transferTo(out);
          }
        };
    String contentType = file.artifact().contentType();
    return ResponseEntity.ok()
        .contentType(
            contentType != null
                ? MediaType.parseMediaType(contentType)
                : MediaType.APPLICATION_OCTET_STREAM)
        .contentLength(file.artifact().size())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(name).build().toString())
        .body(body);
  }

  @DeleteMapping(value = "/workflowrun/{workflowRunId}/artifacts/{name}")
  @AuthCriteria(
      action = PermissionAction.DELETE,
      resource = PermissionResource.WORKFLOWRUN,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Audited(
      action = AuditAction.DELETE,
      resourceType = "artifact",
      resourceId = "#workflowRunId + '/' + #name",
      resourceName = "#name",
      workspaceId = "#workspace",
      level = AuditLevel.DESTRUCTIVE)
  @Operation(summary = "Delete an artifact and its file")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "404", description = "Not Found")
      })
  public void deleteFromRun(
      @Parameter(name = "workspace", description = "Owning workspace name.", required = true)
          @PathVariable
          String workspace,
      @Parameter(name = "workflowRunId", description = "WorkflowRun ID", required = true)
          @PathVariable
          String workflowRunId,
      @Parameter(name = "name", description = "Artifact name", required = true) @PathVariable
          String name) {
    artifactService.delete(workspace, workflowRunId, name);
  }

  @GetMapping(value = "/artifacts")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.WORKFLOWRUN,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Operation(summary = "List the workspace's artifacts, largest first")
  @ApiResponses(value = {@ApiResponse(responseCode = "200", description = "OK")})
  public Page<Artifact> query(
      @Parameter(name = "workspace", description = "Owning workspace name.", required = true)
          @PathVariable
          String workspace,
      @Parameter(
              name = "statuses",
              description = "Artifact statuses to include. Defaults to available.",
              example = "available,expired")
          @RequestParam(required = false)
          Optional<List<ArtifactStatus>> statuses,
      @Parameter(name = "page", description = "Page Number", example = "0")
          @RequestParam(defaultValue = "0")
          int page,
      @Parameter(name = "limit", description = "Result Size", example = "10")
          @RequestParam(defaultValue = "10")
          int limit) {
    return artifactService.query(workspace, statuses, page, limit);
  }

  @DeleteMapping(value = "/artifacts/{artifactId}")
  @AuthCriteria(
      action = PermissionAction.DELETE,
      resource = PermissionResource.WORKFLOWRUN,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Audited(
      action = AuditAction.DELETE,
      resourceType = "artifact",
      resourceId = "#artifactId",
      workspaceId = "#workspace",
      level = AuditLevel.DESTRUCTIVE)
  @Operation(summary = "Delete an artifact and its file")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "404", description = "Not Found")
      })
  public void delete(
      @Parameter(name = "workspace", description = "Owning workspace name.", required = true)
          @PathVariable
          String workspace,
      @Parameter(name = "artifactId", description = "Artifact ID", required = true) @PathVariable
          String artifactId) {
    artifactService.deleteById(workspace, artifactId);
  }
}
