import React from "react";
import { Loading } from "@carbon/react";
import isArray from "lodash/isArray";
import moment from "moment-timezone";
import queryString from "query-string";
import type { SlotInfo } from "react-big-calendar";
import { useLocation, useNavigate } from "react-router-dom";
import ErrorDragon from "Components/ErrorDragon";
import ScheduleCalendar from "Components/ScheduleCalendar";
import ScheduleCreator from "Components/ScheduleCreator";
import ScheduleEditor from "Components/ScheduleEditor";
import SchedulePanelDetail from "Components/SchedulePanelDetail";
import SchedulePanelList from "Components/SchedulePanelList";
import { queryStringOptions } from "Config/appConfig";
import type {
  CalendarDateRange,
  CalendarEvent,
  CalendarEntry,
  ScheduleDate,
  ScheduleUnion,
  WorkflowCanvas,
} from "Types";
import { useEditorRouteData } from "../editorRouteData";
import styles from "./Schedule.module.scss";

/*
 * The schedules and calendar reads moved to the editor route's loader (editorRoute.ts), which
 * only issues them when the route's splat is "schedule" - the same "fetch when this tab is
 * mounted" behaviour the two useQuery calls gave. They are read back through useMatches()
 * (editorRouteData.ts) because this component renders inside Editor.tsx's descendant <Routes>.
 *
 * The four writes live in Components/ScheduleCreator, Components/ScheduleEditor and
 * Components/SchedulePanelList, which submit their namespaced intents through bare useFetcher()
 * calls - resolved against THIS route's editorAction, which dispatches SCHEDULE_INTENTS to the
 * shared scheduleAction (Features/Schedules/scheduleRoute.ts). The fetcher settle re-runs this
 * route's loader, which is what refreshes this page after a write (the old ScheduleManagerForm
 * await-contract that kept these on react-query was reworked to the closeModalRef/fetcher-settle
 * pattern - see ScheduleCreator.tsx).
 *
 * The calendar's visible window is `fromDate`/`toDate` search params rather than useState, for
 * the same reason the version switcher is: a loader re-runs on URL change, not on setState. This
 * mirrors Features/Schedules/Schedules.tsx exactly.
 */

interface ScheduleProps {
  workflow: WorkflowCanvas;
}

export default function ScheduleView(props: ScheduleProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const scheduleData = useEditorRouteData()?.schedule;
  const [activeSchedule, setActiveSchedule] = React.useState<ScheduleUnion | undefined>();
  const [newSchedule, setNewSchedule] = React.useState<Pick<ScheduleDate, "dateSchedule" | "type"> | undefined>();
  const [isPanelOpen, setIsPanelOpen] = React.useState(false);
  const [isEditorOpen, setIsEditorOpen] = React.useState(false);
  const [isCreatorOpen, setIsCreatorOpen] = React.useState(false);

  /**
   * Component functions
   */
  const handleDateRangeChange = (dateRange: CalendarDateRange) => {
    if (!isArray(dateRange)) {
      const search = queryString.stringify(
        {
          ...queryString.parse(location.search, queryStringOptions),
          fromDate: moment(dateRange.start).unix(),
          toDate: moment(dateRange.end).unix(),
        },
        queryStringOptions,
      );
      navigate({ search: `?${search}` });
    }
  };

  /**
   * Start rendering
   */

  // Undefined only until the route's loader has attached its data (or when this component is
  // rendered outside that route) - the same window the previous `!schedulesQuery.data` spinner
  // covered.
  if (!scheduleData) {
    return <Loading withOverlay={true} />;
  }

  if (scheduleData.errorLoadingSchedules) {
    return <ErrorDragon />;
  }

  return (
    <>
      <div className={styles.container}>
        <SchedulePanelList
          includeStatusFilter={true}
          schedulesIsLoading={false}
          schedulesData={scheduleData.schedulesData}
          setActiveSchedule={setActiveSchedule}
          setIsCreatorOpen={setIsCreatorOpen}
          setIsEditorOpen={setIsEditorOpen}
        />
        <CalendarView
          calendarEntries={scheduleData.calendarEntries}
          onDateRangeChange={handleDateRangeChange}
          setActiveSchedule={setActiveSchedule}
          setIsCreatorOpen={setIsCreatorOpen}
          setIsEditorOpen={setIsEditorOpen}
          setIsPanelOpen={setIsPanelOpen}
          setNewSchedule={setNewSchedule}
          workflowSchedules={scheduleData.schedulesData?.content ?? []}
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
        isModalOpen={isCreatorOpen}
        onCloseModal={() => setIsCreatorOpen(false)}
        schedule={newSchedule}
        workflow={props.workflow}
      />
      <ScheduleEditor
        isModalOpen={isEditorOpen}
        onCloseModal={() => setIsEditorOpen(false)}
        schedule={activeSchedule}
        workflow={props.workflow}
      />
    </>
  );
}

interface CalendarViewProps {
  calendarEntries: Array<CalendarEntry>;
  onDateRangeChange: (dateRange: CalendarDateRange) => void;
  setActiveSchedule: React.Dispatch<React.SetStateAction<ScheduleUnion | undefined>>;
  setIsCreatorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsEditorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsPanelOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setNewSchedule: React.Dispatch<React.SetStateAction<Pick<ScheduleDate, "dateSchedule" | "type"> | undefined>>;
  workflowSchedules: Array<ScheduleUnion>;
}

// calendarEntries now arrives already resolved from the route loader, replacing this component's
// UseQueryResult prop - and with it the `data-is-loading` attribute that mirrored the query's
// isLoading, since there is no client-side loading window left once the loader has resolved.
function CalendarView(props: CalendarViewProps) {
  const calendarEvents: Array<CalendarEvent> = [];
  if (props.workflowSchedules) {
    for (let calendarEntry of props.calendarEntries) {
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
    <section className={styles.calendarContainer}>
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
