package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.entity.WorkflowScheduleEntity;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.beans.BeanUtils;
import org.springframework.data.annotation.Id;

/*
 * Public API model based on WorkflowScheduleEntity.
 *
 * Standalone POJO (no longer extends the entity) so the public contract is explicit. No entity
 * field is currently @JsonIgnore, so every entity field stays here; nextScheduleDate is a
 * model-only addition (the computed next trigger time, not persisted on the entity).
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowSchedule {

  @Id private String id;
  private String workflowRef;
  private String schedulerRef;
  private Date nextFireAt;
  private Date lastFiredAt;
  private int retryCount;
  private String name;
  private String description;
  private Date creationDate;
  private WorkflowScheduleType type = WorkflowScheduleType.cron;
  private WorkflowScheduleStatus status = WorkflowScheduleStatus.active;
  private Map<String, String> labels = new HashMap<>();
  private String cronSchedule;
  private Date dateSchedule;
  private String timezone;
  private List<RunParam> params = new LinkedList<>();
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
