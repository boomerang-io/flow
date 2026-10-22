package io.boomerang.workflow.tekton;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.model.ParamSpec;
import io.boomerang.common.model.ResultSpec;
import java.time.Duration;
import java.util.List;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TektonSpec {

  private String description;
  private List<ParamSpec> params;
  private List<Step> steps;
  private Duration timeout;
  private List<ResultSpec> results;
}
