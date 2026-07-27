package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.WorkflowScheduleStatus;
import io.boomerang.common.enums.WorkflowScheduleType;
import io.boomerang.common.model.RunParam;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.format.annotation.DateTimeFormat;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('workflow_schedules')}")
public class WorkflowScheduleEntity {

  @Id
  private String id =
      new ObjectId()
          .toString(); // created by default as its passed to JobRunr and needed prior to saving

  private String workflowRef;
  // Legacy JobRunr job id - no longer written; kept to avoid a data migration.
  @Indexed private String schedulerRef;
  // The next computed fire time. The ScheduleWatcher fires active schedules whose nextFireAt has
  // passed, then advances it in one Compare-And-Set - the advance is the exactly-once fence.
  @Indexed(sparse = true)
  private Date nextFireAt;
  private Date lastFiredAt;
  // Retry counter for a failed fire; incremented on each failed submit, reset on success or once
  // the bounded attempts are exhausted (the occurrence is then skipped to the next).
  private int fireAttempts;
  private String name;
  private String description;

  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
  private Date creationDate = new Date();

  private WorkflowScheduleType type = WorkflowScheduleType.cron;
  private WorkflowScheduleStatus status = WorkflowScheduleStatus.active;
  private Map<String, String> labels = new HashMap<>();
  private String cronSchedule;
  private Date dateSchedule;
  private String timezone;
  private List<RunParam> params = new LinkedList<>();
}
