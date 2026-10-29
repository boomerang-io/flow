package io.boomerang.integrations.model;

import java.util.Map;
import lombok.Data;

@Data
public class GHShareRequest {

  private String workspace;
  private boolean shared;
  // A form integration's field values, by field name; an app integration takes none.
  private Map<String, String> values;
}
