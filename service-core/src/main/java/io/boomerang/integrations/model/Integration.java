package io.boomerang.integrations.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.integrations.enums.IntegrationStatus;
import lombok.Data;
import org.springframework.data.annotation.Id;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public class Integration {
  @Id private String id;
  private String name;
  private String link;
  private String icon;
  private IntegrationStatus status = IntegrationStatus.unlinked;
  private String description;
  private String instructions;
  private String ref;
}
