package io.boomerang.workflow.tekton;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TektonMetadata {
  private String name;
  private Map<String, String> labels = new HashMap<String, String>();
  private Map<String, Object> annotations = new HashMap<String, Object>();
}
