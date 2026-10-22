package io.boomerang.workflow.tekton;

import java.util.HashMap;
import java.util.Map;
import lombok.Data;

@Data
public class Metadata {
  private String name;
  private Map<String, String> labels = new HashMap<String, String>();
  private Map<String, Object> annotations = new HashMap<String, Object>();
}
