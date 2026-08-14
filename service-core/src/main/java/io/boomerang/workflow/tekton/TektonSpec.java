package io.boomerang.workflow.tekton;

import io.boomerang.common.model.ParamSpec;
import io.boomerang.common.model.ResultSpec;
import java.time.Duration;
import java.util.List;
import lombok.Data;

@Data
public class Spec {

  private String description;
  private List<ParamSpec> params;
  private List<Step> steps;
  private Duration timeout;
  private List<ResultSpec> results;
}
