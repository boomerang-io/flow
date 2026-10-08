package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import io.boomerang.common.enums.TaskType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.engine.model.ClaimFilter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

/** Parsing and type selection of a task poll's filters; resolution is DispatcherClaimFilterTest. */
class ClaimFilterServiceTest {

  private static final List<TaskType> REGISTERED =
      List.of(TaskType.template, TaskType.ai, TaskType.uploadartifact, TaskType.downloadartifact);

  private final ClaimFilterService service = new ClaimFilterService(mock(MongoTemplate.class));

  private static int rejected(Runnable resolve) {
    return assertThrows(BoomerangException.class, resolve::run).getCode();
  }

  @Test
  void noFiltersClaimEveryRegisteredType() {
    assertThat(service.resolve(REGISTERED, null, " ", null)).isEqualTo(ClaimFilter.of(REGISTERED));
  }

  @Test
  void aTypeFilterNarrowsToTheRegisteredTypesItNames() {
    assertThat(service.resolve(REGISTERED, "ai, *artifact", null, null).types())
        .containsExactly(TaskType.ai, TaskType.uploadartifact, TaskType.downloadartifact);
  }

  @Test
  void aTypeTheDispatcherDidNotRegisterIsRejected() {
    assertEquals(
        BoomerangError.QUERY_INVALID_FILTERS.getCode(),
        rejected(() -> service.resolve(REGISTERED, "template,script", null, null)));
  }

  @Test
  void aWildcardTypeThatMatchesNothingClaimsNothing() {
    assertThat(service.resolve(REGISTERED, "custom*", null, null).matchesNothing()).isTrue();
  }

  @Test
  void aFilterWithNoValuesIsRejected() {
    assertEquals(
        BoomerangError.QUERY_INVALID_FILTERS.getCode(),
        rejected(() -> service.resolve(REGISTERED, null, " , ", null)));
  }

  @Test
  void aWorkflowLabelWithoutAKeyIsRejected() {
    assertEquals(
        BoomerangError.QUERY_INVALID_FILTERS.getCode(),
        rejected(() -> service.resolve(REGISTERED, null, null, "knowledge")));
  }

  @Test
  void onlyTheStarIsAWildcardAndItIsAnchored() {
    String pattern = ClaimFilterService.anchored("ai-*.v1");

    assertThat("ai-agent.v1").matches(pattern);
    assertThat("ai-agent-v1").doesNotMatch(pattern);
    assertThat("my-ai-agent.v1").doesNotMatch(pattern);
    assertThat("ai-(x).v1").matches(pattern);
  }
}
