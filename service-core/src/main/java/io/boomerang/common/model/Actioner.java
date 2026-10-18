package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Date;
import lombok.Data;

@Data
@JsonInclude(Include.NON_NULL)
public class Actioner {

  private String approverId;
  private String approverEmail;
  private String approverName;
  private String comments;
  private Date date;
  private boolean approved;
}
