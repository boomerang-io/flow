package io.boomerang.client;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.boomerang.common.model.Task;

public class TaskResponsePage extends PageImpl<Task> {
  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public TaskResponsePage(@JsonProperty("content") List<Task> content,
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

  public TaskResponsePage(List<Task> content, Pageable pageable, long total) {
      super(content, pageable, total);
  }

  public TaskResponsePage(List<Task> content) {
      super(content);
  }

  public TaskResponsePage() {
      super(new ArrayList<>());
  }
}
