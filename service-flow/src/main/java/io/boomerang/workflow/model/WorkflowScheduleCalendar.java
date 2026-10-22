package io.boomerang.workflow.model;

import lombok.Data;

import java.util.Date;
/*
 * Maps a list of Dates returned by Quartz utils to a schedule for displaying on the calendar
 */
import java.util.List;

@Data
public class WorkflowScheduleCalendar {

  private String scheduleId;

  private List<Date> dates;
}
