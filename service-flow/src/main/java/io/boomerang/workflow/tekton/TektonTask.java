package io.boomerang.workflow.tekton;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TektonTask {

  private String apiVersion;
  private String kind;
  private Metadata metadata;
  private Spec spec;
}
