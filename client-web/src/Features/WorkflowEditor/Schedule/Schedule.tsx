import React from "react";
import { Loading } from "@carbon/react";
import isArray from "lodash/isArray";
import moment from "moment-timezone";
import queryString from "query-string";
import type { SlotInfo } from "react-big-calendar";
import { useQuery, UseQueryResult } from "react-query";
import ErrorDragon from "Components/ErrorDragon";
import ScheduleCalendar from "Components/ScheduleCalendar";
import ScheduleCreator from "Components/ScheduleCreator";
import ScheduleEditor from "Components/ScheduleEditor";
import SchedulePanelDetail from "Components/SchedulePanelDetail";
import SchedulePanelList from "Components/SchedulePanelList";
import { useTeamContext } from "Hooks";
import { scheduleStatusOptions } from "Constants";
import { queryStringOptions } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
import type {
  CalendarDateRange,
  CalendarEvent,
  CalendarEntry,
  ScheduleDate,
  ScheduleUnion,
  PaginatedSchedulesResponse,
  WorkflowCanvas,
} from "Types";
import styles from "./Schedule.module.scss";

interface ScheduleProps {
  workflow: WorkflowCanvas;
}

export default function ScheduleView(props: ScheduleProps) {
  const { team } = useTeamContext();
  const [activeSchedule, setActiveSchedule] = React.useState<ScheduleUnion | undefined>();
  const [newSchedule, setNewSchedule] = React.useState<Pick<ScheduleDate, "dateSchedule" | "type"> | undefined>();
  const [isPanelOpen, setIsPanelOpen] = React.useState(false);
  const [isEditorOpen, setIsEditorOpen] = React.useState(false);
  const [isCreatorOpen, setIsCreatorOpen] = React.useState(false);
  const [fromDate, setFromDate] = React.useState(moment().startOf("month").unix());
  const [toDate, setToDate] = React.useState(moment().endOf("month").unix());

  /**
   * Get the schedules for the workflow
   * A schedule is an object that defines the events
   */
  const schedulesUrlQuery = queryString.stringify(
    {
      statuses: scheduleStatusOptions.map((statusObj) => statusObj.value),
      workflows: props.workflow.name,
    },
    queryStringOptions,
  );
  const getSchedulesUrl = serviceUrl.team.schedule.getSchedules({ team: team?.name, query: schedulesUrlQuery });

  const schedulesQuery = useQuery<PaginatedSchedulesResponse, string>({
    queryKey: getSchedulesUrl,
    queryFn: resolver.query(getSchedulesUrl),
  });

  /**
   * Get the calendar for the workflow
   * A "calendar" is the scheduled events that are well scheduled to occur
   * in a given time frame. We default to fetching the calendar for the current month
   */
  let scheduleIds = [];
  if (schedulesQuery.data?.content) {
    for (const schedule of schedulesQuery.data?.content) {
      scheduleIds.push(schedule.id);
    }
  }

  const calendarUrlQuery = queryString.stringify(
    {
      schedules: scheduleIds,
      fromDate: fromDate,
      toDate: toDate,
    },
    queryStringOptions,
  );
  const getCalendarUrl = serviceUrl.team.schedule.getSchedulesCalendars({ team: team?.name, query: calendarUrlQuery });

  const calendarQuery = useQuery<Array<CalendarEntry>, string>({
    queryKey: getCalendarUrl,
    queryFn: resolver.query(getCalendarUrl),
    enabled: Boolean(schedulesQuery.data && schedulesQuery.data.content.length > 0),
  });

  /**
   * Component functions
   */
  const handleDateRangeChange = (dateRange: CalendarDateRange) => {
    if (!isArray(dateRange)) {
      const fromDate = moment(dateRange.start).unix();
      const toDate = moment(dateRange.end).unix();
      setFromDate(fromDate);
      setToDate(toDate);
    }
  };

  /**
   * Start rendering
   */

  if (!schedulesQuery.data) {
    return <Loading withOverlay={true} />;
  }

  if (schedulesQuery.error) {
    return <ErrorDragon />;
  }

  return (
    <>
      <div className={styles.container}>
        <SchedulePanelList
          getCalendarUrl={getCalendarUrl}
          getSchedulesUrl={getSchedulesUrl}
          includeStatusFilter={true}
          schedulesIsLoading={schedulesQuery.isLoading}
          schedulesData={schedulesQuery.data}
          setActiveSchedule={setActiveSchedule}
          setIsCreatorOpen={setIsCreatorOpen}
          setIsEditorOpen={setIsEditorOpen}
        />
        <CalendarView
          onDateRangeChange={handleDateRangeChange}
          setActiveSchedule={setActiveSchedule}
          setIsCreatorOpen={setIsCreatorOpen}
          setIsEditorOpen={setIsEditorOpen}
          setIsPanelOpen={setIsPanelOpen}
          setNewSchedule={setNewSchedule}
          workflowSchedules={schedulesQuery.data?.content}
          workflowCalendarQuery={calendarQuery}
        />
      </div>
      <SchedulePanelDetail
        className={styles.panelContainer}
        event={activeSchedule}
        isOpen={isPanelOpen}
        setIsOpen={setIsPanelOpen}
        setIsEditorOpen={setIsEditorOpen}
      />
      <ScheduleCreator
        getCalendarUrl={getCalendarUrl}
        getSchedulesUrl={getSchedulesUrl}
        isModalOpen={isCreatorOpen}
        onCloseModal={() => setIsCreatorOpen(false)}
        schedule={newSchedule}
        workflow={props.workflow}
      />
      <ScheduleEditor
        getCalendarUrl={getCalendarUrl}
        getSchedulesUrl={getSchedulesUrl}
        isModalOpen={isEditorOpen}
        onCloseModal={() => setIsEditorOpen(false)}
        schedule={activeSchedule}
        workflow={props.workflow}
      />
    </>
  );
}

interface CalendarViewProps {
  onDateRangeChange: (dateRange: CalendarDateRange) => void;
  setActiveSchedule: React.Dispatch<React.SetStateAction<ScheduleUnion | undefined>>;
  setIsCreatorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsEditorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsPanelOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setNewSchedule: React.Dispatch<React.SetStateAction<Pick<ScheduleDate, "dateSchedule" | "type"> | undefined>>;
  workflowCalendarQuery: UseQueryResult<Array<CalendarEntry>, any>;
  workflowSchedules: Array<ScheduleUnion>;
}

function CalendarView(props: CalendarViewProps) {
  const calendarEvents: Array<CalendarEvent> = [];
  if (props.workflowCalendarQuery.data && props.workflowSchedules) {
    for (let calendarEntry of props.workflowCalendarQuery.data) {
      const matchingSchedule: ScheduleUnion | undefined = props.workflowSchedules.find(
        (schedule: ScheduleUnion) => schedule.id === calendarEntry.scheduleId,
      );
      if (matchingSchedule) {
        for (const date of calendarEntry.dates) {
          const newEntry = {
            resource: matchingSchedule,
            start: moment.tz(date, matchingSchedule.timezone).toDate(),
            end: moment.tz(date, matchingSchedule.timezone).toDate(),
            title: matchingSchedule.name,
            onClick: () => {
              props.setActiveSchedule(matchingSchedule);
              props.setIsPanelOpen(true);
            },
          };
          calendarEvents.push(newEntry);
        }
      }
    }
  }

  return (
    <section className={styles.calendarContainer} data-is-loading={props.workflowCalendarQuery.isLoading}>
      <ScheduleCalendar
        //@ts-ignore
        onSelectEvent={(data: CalendarEvent) => {
          props.setIsPanelOpen(true);
          props.setActiveSchedule({ ...data.resource, nextScheduleDate: new Date(data.start).toISOString() });
        }}
        onRangeChange={props.onDateRangeChange}
        onSelectSlot={(slot: SlotInfo) => {
          const selectedDate = moment(slot.start);
          const isCurrentDay = selectedDate.isSame(new Date(), "day");
          if (selectedDate.isAfter() || isCurrentDay) {
            const dateSchedule = isCurrentDay ? moment().toISOString() : selectedDate.toISOString();
            props.setNewSchedule({ dateSchedule, type: "runOnce" });
            props.setIsCreatorOpen(true);
          }
        }}
        //@ts-ignore
        dayPropGetter={(date: Date) => {
          const selectedDate = moment(date);
          if (selectedDate.isBefore(new Date(), "day")) {
            return {
              style: {
                cursor: "initial",
              },
            };
          }
        }}
        events={calendarEvents}
      />
    </section>
  );
}
