package io.boomerang.integrations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.integrations.enums.IntegrationKind;
import io.boomerang.integrations.enums.IntegrationStatus;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public class Integration {
  @Id private String id;
  private String name;
  private String type;
  private IntegrationKind kind;
  private String link;
  private String icon;
  private IntegrationStatus status = IntegrationStatus.unlinked;
  private String description;
  private String instructions;
  private String ref;
  // A form integration's fields carry the values the workspace holds, a set secret as the redaction
  // marker.
  private List<AbstractParam> fields;
  // Whether the workspace holds the integration's fields as managed parameters; absent when the
  // integration has none or is not linked.
  private Boolean shared;
}
