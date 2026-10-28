package io.boomerang.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryCriteriaTest {

  @Test
  void noFilterMatchesEverythingAndNeverBuildsAnEmptyAnd() {
    Document built =
        new QueryCriteria()
            .labels(Optional.empty())
            .in("status", Optional.empty())
            .between("creationDate", Optional.empty(), Optional.empty())
            .build()
            .getCriteriaObject();

    assertThat(built).isEmpty();
  }

  @Test
  void aDateRangeIsHalfOpen() {
    Date from = new Date(1_000L);
    Date to = new Date(2_000L);

    assertThat(only(new QueryCriteria().between("creationDate", Optional.of(from), Optional.of(to))))
        .isEqualTo(new Document("creationDate", new Document("$gte", from).append("$lt", to)));
    assertThat(only(new QueryCriteria().between("time", Optional.empty(), Optional.of(to))))
        .isEqualTo(new Document("time", new Document("$lt", to)));
  }

  @Test
  void aLabelIsSplitOnceSoItsValueMayHoldAnEquals() {
    assertThat(only(new QueryCriteria().labels(Optional.of(List.of("a%3Db%3Dc")))))
        .isEqualTo(new Document("labels.a", "b=c"));
    assertThat(only(new QueryCriteria().labels(Optional.of(List.of("team=platform")))))
        .isEqualTo(new Document("labels.team", "platform"));
  }

  @Test
  void aDotInALabelKeyIsMatchedInItsStoredForm() {
    assertThat(only(new QueryCriteria().labels(Optional.of(List.of("example.workspaceId=w1")))))
        .isEqualTo(new Document("labels.example#workspaceId", "w1"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"team", "=platform", " =platform", "team=", "%zz"})
  void aLabelWithoutAKeyOrAValueIsRefused(String label) {
    assertThatThrownBy(() -> new QueryCriteria().labels(Optional.of(List.of(label))))
        .isInstanceOf(BoomerangException.class)
        .satisfies(
            ex -> {
              assertThat(((BoomerangException) ex).getCode())
                  .isEqualTo(BoomerangError.QUERY_INVALID_FILTERS.getCode());
              assertThat(((BoomerangException) ex).getArgs()).containsExactly("labels");
            });
  }

  @Test
  void inMatchesTheValuesOfTheListNotTheOptional() {
    assertThat(only(new QueryCriteria().in("id", Optional.of(List.of("u1", "u2")))))
        .isEqualTo(new Document("id", new Document("$in", List.of("u1", "u2"))));
  }

  @Test
  void anEnumFilterReadsValuesIgnoringCaseAndRefusesAnUnknownOne() {
    assertThat(
            only(
                new QueryCriteria()
                    .in("status", RunStatus.class, Optional.of(List.of("SUCCEEDED")), "status")))
        .isEqualTo(new Document("status", new Document("$in", List.of(RunStatus.succeeded))));

    assertThatThrownBy(
            () ->
                new QueryCriteria()
                    .in("status", RunStatus.class, Optional.of(List.of("finished")), "status"))
        .isInstanceOf(BoomerangException.class)
        .satisfies(ex -> assertThat(((BoomerangException) ex).getArgs()).containsExactly("status"));
  }

  @Test
  void severalFiltersAreAnded() {
    Document built =
        new QueryCriteria()
            .in("workflowRef", List.of("w1"))
            .labels(Optional.of(List.of("team=platform")))
            .build()
            .getCriteriaObject();

    assertThat(built.getList("$and", Document.class))
        .containsExactly(
            new Document("workflowRef", new Document("$in", List.of("w1"))),
            new Document("labels.team", "platform"));
  }

  // The one filter a builder holds, unwrapped from its $and.
  private static Document only(QueryCriteria criteria) {
    List<Document> and = criteria.build().getCriteriaObject().getList("$and", Document.class);
    assertThat(and).hasSize(1);
    return and.get(0);
  }
}
