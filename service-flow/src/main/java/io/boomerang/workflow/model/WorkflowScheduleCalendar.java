package io.boomerang.workflow.model;

import lombok.Data;

import java.util.Date;
import java.util.List;

/*
 * Maps a schedule's list of upcoming trigger Dates for displaying on the calendar
 */

@Data
public class WorkflowScheduleCalendar {

  private String scheduleId;

  private List<Date> dates;
}
