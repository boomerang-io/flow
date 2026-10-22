package io.boomerang.common.model;

import lombok.Data;

@Data
public class ChangeLogVersion extends ChangeLog {
  private Integer version;
}
