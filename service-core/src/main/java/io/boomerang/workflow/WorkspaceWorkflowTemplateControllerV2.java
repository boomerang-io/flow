package io.boomerang.workflow;

import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.audit.AuditAction;
import io.boomerang.core.audit.Audited;
import io.boomerang.core.security.AuthCriteria;
import io.boomerang.core.security.enums.AuthScope;
import io.boomerang.core.security.enums.PermissionAction;
import io.boomerang.core.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A template run in a workspace by name: the run is the workspace's link to the template, created
 * under the template's name the first time, so the workspace needs no link of its own before the
 * first run. Everything else about the run is the link's workflow submit.
 */
@RestController
@RequestMapping("/api/v2/workspace/{workspace}/workflowtemplate")
@Tag(
    name = "Workflow Templates",
    description = "Retrieve and search the Workflow Templates and starters available to every workspace.")
public class WorkspaceWorkflowTemplateControllerV2 {

  private final WorkflowTemplateService workflowTemplateService;

  public WorkspaceWorkflowTemplateControllerV2(WorkflowTemplateService workflowTemplateService) {
    this.workflowTemplateService = workflowTemplateService;
  }

  @PostMapping(value = "/{template}/submit")
  @AuthCriteria(
      action = PermissionAction.ACTION,
      resource = PermissionResource.WORKFLOW,
      assignableScopes = {AuthScope.global, AuthScope.key, AuthScope.user, AuthScope.session})
  @Audited(
      action = AuditAction.SUBMIT,
      resourceType = "workflow",
      resourceId = "#template",
      resourceName = "#result?.getId()",
      workspaceId = "#workspace")
  @Operation(
      summary =
          "Submit a Workflow Template to be run in the workspace. The workspace's link to the template is created on first use.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRun submit(
      @Parameter(
              name = "workspace",
              description = "Workspace the run belongs to.",
              example = "my-amazing-workspace",
              required = true)
          @PathVariable
          String workspace,
      @Parameter(
              name = "template",
              description = "Name of the Workflow Template",
              example = "my-amazing-template",
              required = true)
          @PathVariable
          String template,
      @Parameter(
              name = "start",
              description =
                  "Start the WorkflowRun after submission (default). Pass start=false to park the run at ready for a later PUT /workflowrun/{id}/start.",
              required = false)
          @RequestParam(required = false, defaultValue = "true")
          boolean start,
      @RequestBody WorkflowSubmitRequest request) {
    return workflowTemplateService.submit(workspace, template, request, start);
  }
}
