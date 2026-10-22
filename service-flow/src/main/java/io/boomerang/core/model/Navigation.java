package io.boomerang.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

import io.boomerang.core.enums.NavigationType;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"name", "type", "icon", "link", "disabled", "childLinks"})
public class Navigation {

  private String name;
  private String icon;
  private NavigationType type;
  private boolean disabled;
  private String link;
  private List<Navigation> childLinks;
  private boolean beta = false;
}
