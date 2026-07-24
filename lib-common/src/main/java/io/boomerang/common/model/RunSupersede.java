package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Date;
import lombok.Data;

/**
 * Supersede marker for a TaskRun generation. Absent means this is the live generation. {@code at}
 * is when the generation was retired; {@code by} records what triggered the re-run.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunSupersede {

  private Date at;
  private String by;
}
