import React from "react";
import { InlineNotification, Layer, FilterableMultiSelect, Breadcrumb, BreadcrumbItem } from "@carbon/react";
import {
  Error,
  FeatureHeader as Header,
  FeatureHeaderSubtitle as HeaderSubtitle,
  FeatureHeaderTitle as HeaderTitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { sortByProp } from "@boomerang-io/utils";
import isArray from "lodash/isArray";
import moment from "moment-timezone";
import queryString from "query-string";
import type { SlotInfo } from "react-big-calendar";
import { useLoaderData, useNavigate, useLocation, Link } from "react-router-dom";
import Calendar from "Components/ScheduleCalendar";
import ScheduleCreator from "Components/ScheduleCreator";
import ScheduleEditor from "Components/ScheduleEditor";
import SchedulePanelDetail from "Components/SchedulePanelDetail";
import SchedulePanelList from "Components/SchedulePanelList";
import { useWorkspaceContext } from "Hooks";
import { scheduleStatusOptions } from "Constants";
import { queryStringOptions, appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import type {
  CalendarDateRange,
  CalendarEntry,
  CalendarEvent,
  MultiSelectItem,
  MultiSelectItems,
  ScheduleDate,
  ScheduleUnion,
  Workflow,
  PaginatedWorkflowResponse,
  PaginatedSchedulesResponse,
} from "Types";
import styles from "./Schedules.module.scss";

// Route module: this file's `loader` is attached to the route in app/routes/schedules.tsx (path
// "/:workspace/schedules") rather than being defined inline there, following the
// GlobalParameters.tsx reference conversion (see that file for the fuller rationale comment).
// The route's `action` is the shared scheduleAction (./scheduleRoute.ts, exported from
// app/routes/schedules.tsx): the four writes (create/update/delete/toggle-status) live in
// ScheduleCreator/ScheduleEditor/SchedulePanelList, which submit their namespaced intents
// through bare useFetcher() calls - resolved against this route here and against editorAction's
// SCHEDULE_INTENTS dispatch when the same components render in WorkflowEditor/Schedule/
// Schedule.tsx. The fetcher settle re-runs this loader, which is what refreshes the page after
// a write.

// FilterableMultiSelect's item type requires an (optional) `disabled` field;
// carry it alongside our domain types rather than widening them.
type SelectableWorkflow = Workflow & { disabled?: boolean };
type SelectableStatus = MultiSelectItem & { disabled?: boolean };

// FilterableMultiSelectProps declares filterItems/compareItems/sortItems as required,
// even though the component supplies working defaults at runtime. Provide equivalents
// (mirroring Carbon's own default filter/sort behaviour) to satisfy the contract.
function filterItemsByLabel<Item>(items: readonly Item[], { itemToString, inputValue }: { itemToString: (item: Item) => string; inputValue: string | null }): Item[] {
  if (!inputValue) {
    return items.slice();
  }
  return items.filter((item) => itemToString(item).toLowerCase().includes(inputValue.toLowerCase()));
}

function compareItemLabels(labelA: string, labelB: string, { locale }: { locale: string }): number {
  return labelA.localeCompare(labelB, locale, { numeric: true });
}

// Carbon types compareItems/sortItems' compareItems against the raw item (not its
// rendered label), so adapt the label comparator to that item-based contract.
function makeCompareItems<Item>(itemToString: (item: Item) => string) {
  return (itemA: Item, itemB: Item, options: { locale: string }): number =>
    compareItemLabels(itemToString(itemA), itemToString(itemB), options);
}

function sortItemsBySelection<Item>(
  items: Item[],
  {
    selectedItems = [],
    compareItems,
    locale = "en",
  }: { selectedItems?: Item[]; itemToString?: (item: Item) => string; compareItems: (a: Item, b: Item, ctx: { locale: string }) => number; locale?: string },
): Item[] {
  return [...items].sort((itemA, itemB) => {
    const hasItemA = selectedItems.includes(itemA);
    const hasItemB = selectedItems.includes(itemB);
    if (hasItemA && !hasItemB) return -1;
    if (hasItemB && !hasItemA) return 1;
    return compareItems(itemA, itemB, { locale });
  });
}

const defaultStatusArray = scheduleStatusOptions.map((statusObj) => statusObj.value);
/*
 * Computed per call, not hoisted to module constants: this module is imported ONCE into a
 * long-lived Node server (ssr:true), so a module-level `moment()` freezes the default window at
 * process boot and every later request reuses it - the page would silently omit newer records
 * while the client-rendered date picker showed today, and a refresh would not help.
 * Features/WorkflowEditor/editorRoute.ts documents the same hazard.
 */
const defaultFromDate = () => moment().startOf("month").unix();
const defaultToDate = () => moment().endOf("month").unix();

type LoaderData = {
  workflowsData?: PaginatedWorkflowResponse;
  schedulesData?: PaginatedSchedulesResponse;
  calendarEntries: Array<CalendarEntry>;
  errorLoadingWorkflows: boolean;
  errorLoadingSchedules: boolean;
  errorLoadingCalendar: boolean;
};

// Server loader (see CLAUDE.md client-web SSR direction) - runs in Node via serverFetch(request),
// never the browser resolver/axios instance (see GlobalParameters.tsx for the fuller rationale).
//
// Sequencing: workflows and schedules are independent of each other, so they go out in ONE wave -
// a loader blocks first paint with no pending UI behind it, and this comment previously claimed
// the parallelism the code did not actually have (they were awaited one after the other).
//
// The calendar fetch is the genuine dependency: it needs the resolved schedule ids, so it stays an
// explicit `await` after schedules resolve rather than being fired alongside them and gated after
// the fact - the previous client-side version expressed the same thing with react-query's
// `enabled: hasScheduleData`.
export async function loader({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<LoaderData> {
  const workspace = String(params.workspace);
  const url = new URL(request.url);
  const { statuses = defaultStatusArray, workflows: workflowsFilter } = queryString.parse(url.search, queryStringOptions);
  const { fromDate = defaultFromDate(), toDate = defaultToDate() } = queryString.parse(url.search, queryStringOptions);

  const api = serverFetch(request);
  const schedulesUrlQuery = queryString.stringify({ statuses, workflows: workflowsFilter }, queryStringOptions);

  // `allSettled` rather than `all`: each read's failure stays its own, exactly as the per-call
  // try/catch did - one rejection must not blank out the other.
  const [workflowsResult, schedulesResult] = await Promise.allSettled([
    api.get(serviceUrl.workspace.workflow.getWorkflows({ workspace, query: `statuses=active,inactive` })),
    api.get(serviceUrl.workspace.schedule.getSchedules({ workspace, query: schedulesUrlQuery })),
  ]);

  const workflowsData: PaginatedWorkflowResponse | undefined =
    workflowsResult.status === "fulfilled" ? workflowsResult.value.data : undefined;
  const errorLoadingWorkflows = workflowsResult.status === "rejected";

  const schedulesData: PaginatedSchedulesResponse | undefined =
    schedulesResult.status === "fulfilled" ? schedulesResult.value.data : undefined;
  const errorLoadingSchedules = schedulesResult.status === "rejected";

  let calendarEntries: Array<CalendarEntry> = [];
  let errorLoadingCalendar = false;
  const scheduleIds = (schedulesData?.content ?? []).map((schedule) => schedule.id);
  if (scheduleIds.length > 0) {
    try {
      const calendarUrlQuery = queryString.stringify({ schedules: scheduleIds, fromDate, toDate }, queryStringOptions);
      const response = await serverFetch(request).get(
        serviceUrl.workspace.schedule.getSchedulesCalendars({ workspace, query: calendarUrlQuery }),
      );
      calendarEntries = response.data ?? [];
    } catch (error) {
      errorLoadingCalendar = true;
    }
  }

  return { workflowsData, schedulesData, calendarEntries, errorLoadingWorkflows, errorLoadingSchedules, errorLoadingCalendar };
}

export default function Schedules() {
  const navigate = useNavigate();
  const location = useLocation();
  const { workspace } = useWorkspaceContext();
  const {
    workflowsData,
    schedulesData,
    calendarEntries,
    errorLoadingWorkflows,
    errorLoadingSchedules,
    errorLoadingCalendar,
  } = useLoaderData() as LoaderData;
  const [activeSchedule, setActiveSchedule] = React.useState<ScheduleUnion | undefined>();
  const [newSchedule, setNewSchedule] = React.useState<Pick<ScheduleDate, "dateSchedule" | "type"> | undefined>();
  const [isPanelOpen, setIsPanelOpen] = React.useState(false);
  const [isEditorOpen, setIsEditorOpen] = React.useState(false);
  const [isCreatorOpen, setIsCreatorOpen] = React.useState(false);

  /**
   * Component functions
   */
  function handleDateRangeChange(range: CalendarDateRange) {
    if (!isArray(range)) {
      const toDate = moment(range.end).unix();
      const fromDate = moment(range.start).unix();
      updateHistorySearch({ ...queryString.parse(location.search, queryStringOptions), toDate, fromDate });
    }
  }

  function updateHistorySearch({ ...props }) {
    const queryStr = `?${queryString.stringify({ ...props }, queryStringOptions)}`;
    navigate({ search: queryStr });
    return;
  }

  function handleSelectWorkflows({ selectedItems }: MultiSelectItems<SelectableWorkflow>) {
    const workflowRefs = selectedItems.length > 0 ? selectedItems.map((worflow) => worflow.name) : undefined;
    updateHistorySearch({
      ...queryString.parse(location.search, queryStringOptions),
      workflows: workflowRefs,
    });
    return;
  }

  function handleSelectStatuses({ selectedItems }: MultiSelectItems<SelectableStatus>) {
    //@ts-ignore next-line
    const statuses = selectedItems.length > 0 ? selectedItems.map((status) => status.value) : undefined;
    updateHistorySearch({ ...queryString.parse(location.search, queryStringOptions), statuses: statuses });
    return;
  }

  function handleSetActiveSchedule(schedule: ScheduleUnion) {
    const workflowFindPredicate = (workflow: Workflow) => {
      return workflow.name === schedule.workflowRef;
    };
    let workflow: Workflow | undefined;
    if (workflowsData && !workflow) {
      const foundWorkflow = workflowsData.content.find(workflowFindPredicate);
      if (foundWorkflow) {
        workflow = foundWorkflow;
      }
    }

    setActiveSchedule({ ...schedule, workflow });
  }

  function getWorkflowFilter() {
    let workflowsList: Array<Workflow> = [];
    if (workflowsData?.content) {
      workflowsList = workflowsData.content;
    }
    return sortByProp(workflowsList, "name", "ASC");
  }

  // The workspace object itself is a client-side concern - until WorkspaceContainer resolves it,
  // there is nothing to render.
  if (!workspace) {
    return null;
  }

  const NavigationComponent = () => {
    return (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.home()}>Home</Link>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <p>{workspace.displayName}</p>
        </BreadcrumbItem>
      </Breadcrumb>
    );
  };

  /*
   * A failed read renders the page chrome plus an explicit error, never an empty list - the same
   * convention as Features/Activity/Activity.tsx and Features/Insights/Insights.tsx. Both flags
   * were computed by the loader but read by nobody here, so an API failure arrived on screen as
   * "no schedules", which a user reasonably reads as "my schedules were deleted". The calendar's
   * own failure is handled separately in CalendarView below: it is a partial failure, and the
   * schedule list beside it is still accurate.
   */
  if (errorLoadingWorkflows || errorLoadingSchedules) {
    return (
      <>
        <Header
          nav={<NavigationComponent />}
          className={styles.header}
          includeBorder={true}
          header={
            <>
              <HeaderTitle className={styles.headerTitle}>Schedules</HeaderTitle>
              <HeaderSubtitle>Your Workflow's calendar assistant - set it and forget it!</HeaderSubtitle>
            </>
          }
        />
        <section aria-label="Schedules Error" className={styles.content}>
          <Error />
        </section>
      </>
    );
  }

  if (workflowsData) {
    const { workflows = "", statuses = "" } = queryString.parse(location.search, queryStringOptions);
    const selectedWorkflowRefs = typeof workflows === "string" ? [workflows] : workflows;
    const selectedStatuses = typeof statuses === "string" ? [statuses] : statuses;

    const itemToStringWorkflow = (workflow: SelectableWorkflow | null) => (workflow ? workflow.displayName : "");
    const itemToStringStatus = (item: SelectableStatus | null) => (item ? item.label : "");

    return (
      <>
        <Header
          nav={<NavigationComponent />}
          className={styles.header}
          includeBorder={true}
          header={
            <>
              <HeaderTitle className={styles.headerTitle}>Schedules</HeaderTitle>
              <HeaderSubtitle>Your Workflow's calendar assistant - set it and forget it!</HeaderSubtitle>
            </>
          }
          actions={
            <section aria-label="Schedule filters" className={styles.dataFiltersContainer}>
              <Layer className={styles.dataFilter}>
                <FilterableMultiSelect<SelectableWorkflow>
                  light
                  id="schedules-workflows-select"
                  placeholder="Choose workflow(s)"
                  invalid={false}
                  onChange={handleSelectWorkflows}
                  items={getWorkflowFilter()}
                  itemToString={itemToStringWorkflow}
                  filterItems={filterItemsByLabel}
                  compareItems={makeCompareItems(itemToStringWorkflow)}
                  sortItems={sortItemsBySelection}
                  initialSelectedItems={getWorkflowFilter().filter((workflow: Workflow) =>
                    Boolean(selectedWorkflowRefs?.find((ref) => ref === workflow.name)),
                  )}
                  titleText="Filter by Workflow"
                />
              </Layer>
              <Layer className={styles.dataFilter}>
                <FilterableMultiSelect<SelectableStatus>
                  id="schedules-statuses-select"
                  placeholder="Choose status(es)"
                  invalid={false}
                  onChange={handleSelectStatuses}
                  items={scheduleStatusOptions}
                  itemToString={itemToStringStatus}
                  filterItems={filterItemsByLabel}
                  compareItems={makeCompareItems(itemToStringStatus)}
                  sortItems={sortItemsBySelection}
                  initialSelectedItems={scheduleStatusOptions.filter((option) =>
                    Boolean(selectedStatuses?.find((status) => status === option.value)),
                  )}
                  titleText="Filter by status"
                />
              </Layer>
            </section>
          }
        />
        <div className={styles.content}>
          <div className={styles.contentContainer}>
            <SchedulePanelList
              includeStatusFilter={false}
              schedulesIsLoading={false}
              schedulesData={schedulesData}
              setActiveSchedule={handleSetActiveSchedule}
              setIsCreatorOpen={setIsCreatorOpen}
              setIsEditorOpen={setIsEditorOpen}
            />
            <CalendarView
              handleDateRangeChange={handleDateRangeChange}
              calendarEntries={calendarEntries}
              errorLoadingCalendar={errorLoadingCalendar}
              schedules={schedulesData?.content}
              setActiveSchedule={handleSetActiveSchedule}
              setIsCreatorOpen={setIsCreatorOpen}
              setIsEditorOpen={setIsEditorOpen}
              setIsPanelOpen={setIsPanelOpen}
              setNewSchedule={setNewSchedule}
              updateHistorySearch={updateHistorySearch}
            />
            <SchedulePanelDetail
              className={styles.panelContainer}
              event={activeSchedule}
              isOpen={isPanelOpen}
              setIsOpen={setIsPanelOpen}
              setIsEditorOpen={setIsEditorOpen}
            />
            <ScheduleCreator
              includeWorkflowDropdown={true}
              isModalOpen={isCreatorOpen}
              onCloseModal={() => setIsCreatorOpen(false)}
              schedule={newSchedule}
              workflowOptions={getWorkflowFilter()}
            />
            <ScheduleEditor
              includeWorkflowDropdown={true}
              isModalOpen={isEditorOpen}
              onCloseModal={() => setIsEditorOpen(false)}
              schedule={activeSchedule}
              workflowOptions={getWorkflowFilter()}
            />
          </div>
        </div>
      </>
    );
  }

  return null;
}

interface CalendarViewProps {
  calendarEntries: Array<CalendarEntry>;
  errorLoadingCalendar: boolean;
  handleDateRangeChange: (dateInfo: CalendarDateRange) => void;
  schedules?: Array<ScheduleUnion>;
  setActiveSchedule: (schedule: ScheduleUnion) => void;
  setIsCreatorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsEditorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsPanelOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setNewSchedule: React.Dispatch<React.SetStateAction<Pick<ScheduleDate, "dateSchedule" | "type"> | undefined>>;
  updateHistorySearch: (props: any) => void;
}

// calendarEntries now arrives already resolved from the route loader (see the loader's sequencing
// comment above) instead of this component's own `useQuery({ enabled: hasScheduleData })` -
// dropped along with the `isLoading` state that gated it (there's no client-side loading window
// left to show once the loader has resolved before this renders).
function CalendarView(props: CalendarViewProps) {
  const calendarEvents: Array<CalendarEvent> = [];
  if (props.schedules) {
    for (let calendarEntry of props.calendarEntries) {
      const matchingSchedule: ScheduleUnion | undefined = props.schedules.find(
        (schedule: ScheduleUnion) => schedule.id === calendarEntry.scheduleId,
      );
      if (matchingSchedule) {
        for (const date of calendarEntry.dates) {
          const newEntry = {
            resource: matchingSchedule,
            start: moment.tz(date, matchingSchedule.timezone).toDate(),
            end: moment.tz(date, matchingSchedule.timezone).toDate(),
            title: matchingSchedule.name,
          };
          calendarEvents.push(newEntry);
        }
      }
    }
  }

  return (
    <section className={styles.calendarContainer}>
      {/*
       * The calendar entries are a separate (dependent) fetch from the schedule list, so this is a
       * partial failure: the list is still accurate and the calendar is simply empty. It used to
       * be piped into a `data-is-loading` attribute on this section - a loading flag driven by an
       * error flag, which showed the user nothing either way.
       */}
      {props.errorLoadingCalendar ? (
        <InlineNotification
          lowContrast
          hideCloseButton={true}
          kind="error"
          title="Calendar unavailable"
          subtitle="The scheduled dates could not be loaded. The schedule list is still up to date."
        />
      ) : null}
      <Calendar
        heightOffset={220}
        //@ts-ignore
        onSelectEvent={(data: CalendarEvent) => {
          props.setIsPanelOpen(true);
          props.setActiveSchedule({ ...data.resource, nextScheduleDate: new Date(data.start).toISOString() });
        }}
        onRangeChange={props.handleDateRangeChange}
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
