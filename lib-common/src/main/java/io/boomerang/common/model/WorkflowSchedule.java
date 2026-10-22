package io.boomerang.common.model;

import io.boomerang.common.entity.WorkflowScheduleEntity;
import java.util.Date;
import lombok.Data;
import org.springframework.beans.BeanUtils;

@Data
public class WorkflowSchedule extends WorkflowScheduleEntity {

  private Date nextScheduleDate;

  public WorkflowSchedule() {}

  public WorkflowSchedule(WorkflowScheduleEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }

  public WorkflowSchedule(WorkflowScheduleEntity entity, Date nextScheduleDate) {
    BeanUtils.copyProperties(entity, this);
    this.nextScheduleDate = nextScheduleDate;
  }
}
