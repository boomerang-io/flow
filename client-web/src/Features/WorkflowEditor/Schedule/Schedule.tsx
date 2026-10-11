import React from "react";
import { Loading } from "@carbon/react";
import isArray from "lodash/isArray";
import moment from "moment-timezone";
import queryString from "query-string";
import { useLocation, useNavigate } from "react-router-dom";
import ErrorDragon from "Components/ErrorDragon";
import ScheduleCreator from "Components/ScheduleCreator";
import ScheduleEditor from "Components/ScheduleEditor";
import SchedulePanelDetail from "Components/SchedulePanelDetail";
import ScheduleViews from "Components/ScheduleViews";
import { queryStringOptions } from "Config/appConfig";
import type { CalendarDateRange, ScheduleDate, ScheduleUnion, WorkflowCanvas } from "Types";
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
        <ScheduleViews
          calendarEntries={scheduleData.calendarEntries}
          heightOffset={330}
          includeStatusFilter={true}
          onDateRangeChange={handleDateRangeChange}
          schedulesData={scheduleData.schedulesData}
          setActiveSchedule={setActiveSchedule}
          setIsCreatorOpen={setIsCreatorOpen}
          setIsEditorOpen={setIsEditorOpen}
          setIsPanelOpen={setIsPanelOpen}
          setNewSchedule={setNewSchedule}
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
