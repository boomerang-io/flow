package io.boomerang.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;

/** A node that runs the sleep task is typed sleep; every other node keeps its type. */
class SleepNodesRunAsSleepTest {

  private static final String SLEEP_TASK = "5bd97bea5a5df954ad592c06";
  private static final String EMAIL_TASK = "612989ab63a78c0c37074e91";

  @Test
  void aTemplateTypedSleepNodeIsRetypedAndOtherNodesAreUntouched() {
    List<Document> tasks =
        List.of(
            new Document("name", "start").append("type", "start"),
            node("Sleep 1", "template", SLEEP_TASK),
            node("Sleep 2", "sleep", SLEEP_TASK),
            node("Send Email", "template", EMAIL_TASK),
            node("Custom", "custom", SLEEP_TASK),
            new Document("name", "end").append("type", "end"));

    assertThat(_0047__SleepNodesRunAsSleep.retype(tasks, List.of(SLEEP_TASK))).isEqualTo(1);

    assertThat(tasks)
        .extracting(task -> task.getString("type"))
        .containsExactly("start", "sleep", "sleep", "template", "custom", "end");
    assertThat(_0047__SleepNodesRunAsSleep.retype(tasks, List.of(SLEEP_TASK)))
        .as("a second pass finds nothing")
        .isZero();
  }

  private static Document node(String name, String type, String taskRef) {
    return new Document("name", name).append("type", type).append("taskRef", taskRef).append("taskVersion", 1);
  }
}
