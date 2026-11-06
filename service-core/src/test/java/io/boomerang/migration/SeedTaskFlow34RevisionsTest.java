package io.boomerang.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/** The seed holds the task-flow 3.4.0 revisions the unit adds, and none of them pins an image. */
class SeedTaskFlow34RevisionsTest {

  private static final Map<String, Integer> NEWEST =
      Map.of(
          "execute-basic-http-call", 2,
          "execute-advanced-http-call", 5,
          "send-simple-slack-message", 3,
          "send-slack-message-with-file-contents", 3,
          "send-custom-slack-message", 3,
          "jsonpath-to-parameter", 4,
          "file-jsonpath-to-parameter", 3,
          "execute-shell", 3);

  @Test
  void theSeedHoldsEachNewRevisionAsTheNewestOfItsTask() {
    assertThat(_0049__SeedTaskFlow34Revisions.REVISED_TASKS).containsExactlyInAnyOrderElementsOf(NEWEST.keySet());
    List<Document> revisions = SeedResources.load("seed/task-revisions.json");
    Map<String, Integer> newest =
        revisions.stream()
            .filter(revision -> NEWEST.containsKey(revision.getString("parentRef")))
            .collect(
                Collectors.toMap(
                    revision -> revision.getString("parentRef"),
                    revision -> revision.getInteger("version"),
                    Math::max));
    assertThat(newest).isEqualTo(NEWEST);
  }

  @Test
  void noNewRevisionSetsAnImageOfItsOwn() {
    SeedResources.load("seed/task-revisions.json").stream()
        .filter(revision -> NEWEST.containsKey(revision.getString("parentRef")))
        .filter(revision -> revision.getInteger("version") == NEWEST.get(revision.getString("parentRef")))
        .forEach(
            revision ->
                assertThat(revision.get("spec", Document.class).getString("image"))
                    .as("%s revision %s", revision.getString("parentRef"), revision.getInteger("version"))
                    .isNullOrEmpty());
  }
}
