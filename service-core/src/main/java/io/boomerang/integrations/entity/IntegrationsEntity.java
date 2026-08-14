package io.boomerang.integrations.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.integrations.enums.IntegrationStatus;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('integrations')}")
public class IntegrationsEntity {

  @Id private String id;
  private String type;
  private String ref;
  private Object data;
  private IntegrationStatus status = IntegrationStatus.unlinked;
  private Map<String, String> labels = new HashMap<>();
}
