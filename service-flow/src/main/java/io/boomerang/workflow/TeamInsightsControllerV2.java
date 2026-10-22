package io.boomerang.workflow;

import io.boomerang.common.model.WorkflowRunInsight;
import io.boomerang.security.AuthCriteria;
import io.boomerang.security.enums.AuthScope;
import io.boomerang.security.enums.PermissionAction;
import io.boomerang.security.enums.PermissionResource;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/team/{team}/insights")
@Tag(name = "Insights", description = "Provide the ability to search and retrieve Insights.")
public class TeamInsightsControllerV2 {

  private final InsightsService insightsService;

  public TeamInsightsControllerV2(InsightsService insightsService) {
    this.insightsService = insightsService;
  }

  @GetMapping(value = "")
  @AuthCriteria(
      action = PermissionAction.READ,
      resource = PermissionResource.INSIGHTS,
      assignableScopes = {AuthScope.team, AuthScope.user, AuthScope.session})
  @Operation(
      summary = "Retrieve insights for a team",
      description = "The insights are based on the workflow runs and their statuses.")
  @ApiResponses(
      value = {
        @ApiResponse(responseCode = "200", description = "OK"),
        @ApiResponse(responseCode = "400", description = "Bad Request")
      })
  public WorkflowRunInsight getTeamInsights(
      @Parameter(
              name = "team",
              description = "Owning team name.",
              example = "my-amazing-team",
              required = true)
          @PathVariable
          String team,
      @RequestParam Optional<Long> fromDate,
      @RequestParam Optional<Long> toDate,
      @RequestParam Optional<List<String>> workflows,
      @RequestParam Optional<List<String>> statuses) {

    // Todays date
    Date to = new Date();
    // 30 days prior
    Calendar calendar = Calendar.getInstance();
    calendar.setTime(to);
    calendar.add(Calendar.DAY_OF_MONTH, 30);
    Date from = calendar.getTime();
    if (fromDate.isPresent()) {
      from = new Date(fromDate.get());
    }
    if (toDate.isPresent()) {
      to = new Date(toDate.get());
    }

    return insightsService.get(team, from, to, workflows, statuses);
  }
}
