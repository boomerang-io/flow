package io.boomerang.common.model;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;

public class TaskRunResponsePage extends PageImpl<TaskRun> {
  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public TaskRunResponsePage(@JsonProperty("content") List<TaskRun> content,
                      @JsonProperty("number") int number,
                      @JsonProperty("size") int size,
                      @JsonProperty("totalElements") Long totalElements,
                      @JsonProperty("pageable") JsonNode pageable,
                      @JsonProperty("last") boolean last,
                      @JsonProperty("totalPages") int totalPages,
                      @JsonProperty("sort") JsonNode sort,
                      @JsonProperty("first") boolean first,
                      @JsonProperty("numberOfElements") int numberOfElements) {

      super(content, PageRequest.of(number, size), totalElements);
  }

  public TaskRunResponsePage(List<TaskRun> content, Pageable pageable, long total) {
      super(content, pageable, total);
  }

  public TaskRunResponsePage(List<TaskRun> content) {
      super(content);
  }

  public TaskRunResponsePage() {
      super(new ArrayList<>());
  }
}
