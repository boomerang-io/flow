import React from "react";
import { Layer, FilterableMultiSelect, Breadcrumb, BreadcrumbItem } from "@carbon/react";
import {
  FeatureHeader as Header,
  FeatureHeaderSubtitle as HeaderSubtitle,
  FeatureHeaderTitle as HeaderTitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { sortByProp } from "@boomerang-io/utils";
import isArray from "lodash/isArray";
import moment from "moment-timezone";
import queryString from "query-string";
import type { SlotInfo } from "react-big-calendar";
import { useQuery } from "react-query";
import { useHistory, useLocation, Link } from "react-router-dom";
import Calendar from "Components/ScheduleCalendar";
import ScheduleCreator from "Components/ScheduleCreator";
import ScheduleEditor from "Components/ScheduleEditor";
import SchedulePanelDetail from "Components/SchedulePanelDetail";
import SchedulePanelList from "Components/SchedulePanelList";
import { useWorkspaceContext } from "Hooks";
import { scheduleStatusOptions } from "Constants";
import { queryStringOptions, appLink } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
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
const defaultFromDate = moment().startOf("month").unix();
const defaultToDate = moment().endOf("month").unix();

export default function Schedules() {
  const history = useHistory();
  const location = useLocation();
  const { workspace } = useWorkspaceContext();
  const [activeSchedule, setActiveSchedule] = React.useState<ScheduleUnion | undefined>();
  const [newSchedule, setNewSchedule] = React.useState<Pick<ScheduleDate, "dateSchedule" | "type"> | undefined>();
  const [isPanelOpen, setIsPanelOpen] = React.useState(false);
  const [isEditorOpen, setIsEditorOpen] = React.useState(false);
  const [isCreatorOpen, setIsCreatorOpen] = React.useState(false);

  /**
   * Get schedule and calendar data
   */
  const { statuses = defaultStatusArray, workflows } = queryString.parse(location.search, queryStringOptions);

  /** Retrieve Workflows */
  const getWorkflowsUrl = serviceUrl.workspace.workflow.getWorkflows({
    workspace: workspace?.name,
    query: `statuses=active,inactive`,
  });
  const workflowsQuery = useQuery<PaginatedWorkflowResponse, string>({
    queryKey: getWorkflowsUrl,
    queryFn: resolver.query(getWorkflowsUrl),
  });

  const schedulesUrlQuery = queryString.stringify(
    {
      statuses,
      workflows,
    },
    queryStringOptions,
  );
  const getSchedulesUrl = serviceUrl.workspace.schedule.getSchedules({ workspace: workspace?.name, query: schedulesUrlQuery });

  const schedulesQuery = useQuery<PaginatedSchedulesResponse, string>({
    queryKey: getSchedulesUrl,
    queryFn: resolver.query(getSchedulesUrl),
  });

  const { fromDate = defaultFromDate, toDate = defaultToDate } = queryString.parse(location.search, queryStringOptions);

  let userScheduleIds = [];
  if (schedulesQuery.data?.content) {
    for (const schedule of schedulesQuery.data?.content) {
      userScheduleIds.push(schedule.id);
    }
  }
  const hasScheduleData = Boolean(userScheduleIds.length > 0);

  const calendarUrlQuery = queryString.stringify(
    {
      schedules: userScheduleIds,
      fromDate,
      toDate,
    },
    queryStringOptions,
  );
  const getCalendarUrl = serviceUrl.workspace.schedule.getSchedulesCalendars({ workspace: workspace?.name, query: calendarUrlQuery });

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
    history.push({ search: queryStr });
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
    if (workflowsQuery.data && !workflow) {
      const foundWorkflow = workflowsQuery.data?.content.find(workflowFindPredicate);
      if (foundWorkflow) {
        workflow = foundWorkflow;
      }
    }

    setActiveSchedule({ ...schedule, workflow });
  }

  function getWorkflowFilter() {
    let workflowsList: Array<Workflow> = [];
    if (workflowsQuery.data?.content) {
      workflowsList = workflowsQuery.data.content;
    }
    return sortByProp(workflowsList, "name", "ASC");
  }

  if (workspace && workflowsQuery.data) {
    const { workflows = "", statuses = "" } = queryString.parse(location.search, queryStringOptions);
    const selectedWorkflowRefs = typeof workflows === "string" ? [workflows] : workflows;
    const selectedStatuses = typeof statuses === "string" ? [statuses] : statuses;
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
              getCalendarUrl={getCalendarUrl}
              getSchedulesUrl={getSchedulesUrl}
              includeStatusFilter={false}
              schedulesIsLoading={schedulesQuery.isLoading}
              schedulesData={schedulesQuery.data}
              setActiveSchedule={handleSetActiveSchedule}
              setIsCreatorOpen={setIsCreatorOpen}
              setIsEditorOpen={setIsEditorOpen}
            />
            <CalendarView
              handleDateRangeChange={handleDateRangeChange}
              hasScheduleData={hasScheduleData}
              getCalendarUrl={getCalendarUrl}
              schedules={schedulesQuery.data?.content}
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
              getCalendarUrl={getCalendarUrl}
              getSchedulesUrl={getSchedulesUrl}
              includeWorkflowDropdown={true}
              isModalOpen={isCreatorOpen}
              onCloseModal={() => setIsCreatorOpen(false)}
              schedule={newSchedule}
              workflowOptions={getWorkflowFilter()}
            />
            <ScheduleEditor
              getCalendarUrl={getCalendarUrl}
              getSchedulesUrl={getSchedulesUrl}
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
  getCalendarUrl: string;
  handleDateRangeChange: (dateInfo: CalendarDateRange) => void;
  hasScheduleData: boolean;
  schedules?: Array<ScheduleUnion>;
  setActiveSchedule: (schedule: ScheduleUnion) => void;
  setIsCreatorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsEditorOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setIsPanelOpen: React.Dispatch<React.SetStateAction<boolean>>;
  setNewSchedule: React.Dispatch<React.SetStateAction<Pick<ScheduleDate, "dateSchedule" | "type"> | undefined>>;
  updateHistorySearch: (props: any) => void;
}

function CalendarView(props: CalendarViewProps) {
  const calendarQuery = useQuery<Array<CalendarEntry>, string>({
    queryKey: props.getCalendarUrl,
    queryFn: resolver.query(props.getCalendarUrl),
    enabled: props.hasScheduleData,
  });

  const calendarEvents: Array<CalendarEvent> = [];
  if (calendarQuery.data && props.schedules) {
    for (let calendarEntry of calendarQuery.data) {
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
    <section className={styles.calendarContainer} data-is-loading={calendarQuery.isLoading}>
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
