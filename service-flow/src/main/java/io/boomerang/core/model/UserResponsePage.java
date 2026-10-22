package io.boomerang.core.model;

import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public class UserResponsePage extends PageImpl<User> {
  @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
  public UserResponsePage(@JsonProperty("content") List<User> content,
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

  public UserResponsePage(List<User> content, Pageable pageable, long total) {
      super(content, pageable, total);
  }

  public UserResponsePage(List<User> content) {
      super(content);
  }

  public UserResponsePage() {
      super(new ArrayList<>());
  }
}
