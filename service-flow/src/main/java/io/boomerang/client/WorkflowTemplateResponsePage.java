package io.boomerang.client;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import io.boomerang.common.model.WorkflowTemplate;

public class WorkflowTemplateResponsePage extends PageImpl<WorkflowTemplate> {
  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public WorkflowTemplateResponsePage(@JsonProperty("content") List<WorkflowTemplate> content,
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

  public WorkflowTemplateResponsePage(List<WorkflowTemplate> content, Pageable pageable, long total) {
      super(content, pageable, total);
  }

  public WorkflowTemplateResponsePage(List<WorkflowTemplate> content) {
      super(content);
  }

  public WorkflowTemplateResponsePage() {
      super(new ArrayList<>());
  }
}
