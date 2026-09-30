import React from "react";
import { Breadcrumb, BreadcrumbItem, DatePicker, DatePickerInput, FilterableMultiSelect } from "@carbon/react";
import {
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
  Toggle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { sortByProp } from "@boomerang-io/utils";
import moment from "moment";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { Link, useLoaderData, useLocation, useNavigate } from "react-router-dom";
import ErrorDragon from "Components/ErrorDragon";
import { useWorkspaceContext } from "Hooks";
import { filterItemsByLabel, makeCompareItems, sortItemsBySelection } from "Utils/multiSelectHelper";
import { statusOptions } from "Constants/filterOptions";
import { appLink, queryStringOptions } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import type { FlowWorkspace, InsightsSummary, InsightsWorkflowDetail, MultiSelectItem, MultiSelectItems, Workflow } from "Types";
import DurationSpread from "./DurationSpread";
import HeadlineTiles from "./HeadlineTiles";
import RunsPerDayChart from "./RunsPerDayChart";
import WorkflowDetail from "./WorkflowDetail";
import WorkflowTable from "./WorkflowTable";
import styles from "./Insights.module.scss";

// Route module: this file's `loader` is attached to the route in app/routes/insights.tsx
// (path "/:workspace/insights"). `params.workspace` (the URL slug) drives every server fetch
// below, while the full workspace object (for the header's displayName/breadcrumb) stays a
// client-side concern, supplied by app/routes/workspaceLayout.tsx's loader through
// WorkspaceContextProvider - the same split Features/Activity/Activity.tsx follows.
//
// Insights are computed server-side from the runs the workspace still holds (see
// WorkflowRunInsightService). A deleted workflow's runs are gone with it; the audit trail keeps
// serving the monthly quotas, which must count them, but never this page.

/*
 * Computed per call, not hoisted to module constants: this module is imported ONCE into a
 * long-lived Node server (ssr:true), so a module-level `moment()` freezes the default window at
 * process boot and every later request reuses it. Features/WorkflowEditor/editorRoute.ts
 * documents the same hazard.
 */
const defaultMaxDate = () => moment().format("MM/DD/YYYY");
const defaultFromDate = () => moment().subtract(3, "months").valueOf();
const defaultToDate = () => moment().endOf("day").valueOf();

// FilterableMultiSelect's item type requires an (optional) `disabled` field;
// carry it alongside our domain types rather than widening them.
type SelectableWorkflow = Workflow & { disabled?: boolean };
type SelectableStatus = MultiSelectItem & { disabled?: boolean };
type SelectableTrigger = MultiSelectItem & { disabled?: boolean };

// The engine's TriggerEnum values, as stored on a run.
const triggerOptions: Array<SelectableTrigger> = [
  { label: "Manual", value: "manual" },
  { label: "Schedule", value: "schedule" },
  { label: "Webhook", value: "webhook" },
  { label: "Event", value: "event" },
  { label: "GitHub", value: "github" },
  { label: "Engine", value: "engine" },
  { label: "Task", value: "task" },
  { label: "Retry", value: "retry" },
];

type LoaderData = {
  summary: InsightsSummary | null;
  errorLoadingInsights: boolean;
  /** The selected workflow's detail, when `?workflow=` names one and its read succeeded. */
  detail: InsightsWorkflowDetail | null;
  errorLoadingDetail: boolean;
  workflowOptions: Array<Workflow>;
  errorLoadingWorkflows: boolean;
};

function asString(value: string | Array<string> | null | undefined): string | null {
  const first = Array.isArray(value) ? value[0] : value;
  return typeof first === "string" && first ? first : null;
}

// Server loader (ssr:true). Runs in Node, so it uses serverFetch(request) rather than the browser
// axios instance. Every filter (statuses/workflows/triggers/fromDate/toDate) and the selected
// workflow are read off the request URL, the same names the component navigates with.
export async function loader({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<LoaderData> {
  const workspace = String(params.workspace);
  const {
    statuses,
    workflows,
    triggers,
    workflow,
    fromDate = defaultFromDate(),
    toDate = defaultToDate(),
  } = queryString.parse(new URL(request.url).search, queryStringOptions);
  const selected = asString(workflow as string | Array<string> | null);

  // One wave: the summary, the workflow filter options and the selected workflow's detail are
  // independent reads, and a loader blocks first paint. `allSettled` keeps each failure its own.
  const api = serverFetch(request);
  const summaryQuery = queryString.stringify({ statuses, workflows, triggers, fromDate, toDate }, queryStringOptions);
  const detailQuery = queryString.stringify({ fromDate, toDate }, queryStringOptions);
  const [summaryResult, workflowsResult, detailResult] = await Promise.allSettled([
    api.get(serviceUrl.workspace.getInsights({ workspace, query: summaryQuery })),
    api.get(serviceUrl.workspace.workflow.getWorkflows({ workspace })),
    selected
      ? api.get(serviceUrl.workspace.getInsightsWorkflow({ workspace, workflow: selected, query: detailQuery }))
      : Promise.resolve(null),
  ]);

  return {
    summary: summaryResult.status === "fulfilled" ? summaryResult.value.data : null,
    errorLoadingInsights: summaryResult.status === "rejected",
    detail: detailResult.status === "fulfilled" && detailResult.value ? detailResult.value.data : null,
    errorLoadingDetail: detailResult.status === "rejected",
    workflowOptions: workflowsResult.status === "fulfilled" ? workflowsResult.value.data.content : [],
    errorLoadingWorkflows: workflowsResult.status === "rejected",
  };
}

export default function Insights() {
  const { workspace } = useWorkspaceContext();
  const navigate = useNavigate();
  const location = useLocation();
  const { summary, errorLoadingInsights, detail, errorLoadingDetail, workflowOptions, errorLoadingWorkflows } =
    useLoaderData() as LoaderData;
  const search = queryString.parse(location.search, queryStringOptions);
  const compare = search.compare !== "off";
  const selected = asString(search.workflow as string | Array<string> | null);

  function updateHistorySearch(props: Record<string, unknown>) {
    navigate({ search: `?${queryString.stringify(props, queryStringOptions)}` });
  }

  // Selecting the selected row again clears the selection.
  const selectHref = (workflowName: string) =>
    `?${queryString.stringify({ ...search, workflow: workflowName === selected ? undefined : workflowName }, queryStringOptions)}`;

  // The workspace object comes from the workspace layout route's context - until it resolves,
  // there's nothing to render yet.
  if (!workspace) {
    return null;
  }

  let body: React.ReactNode;
  if (errorLoadingInsights || errorLoadingWorkflows || !summary) {
    body = <ErrorDragon />;
  } else if (summary.totals.runs === 0) {
    body = (
      <p className={styles.noRuns} data-testid="completed-insights">
        No runs in this period. Widen the date range, or run a workflow and come back.
      </p>
    );
  } else {
    body = (
      <div className={styles.container} data-testid="completed-insights">
        <HeadlineTiles summary={summary} compare={compare} />
        <div className={styles.chart}>
          <RunsPerDayChart daily={summary.daily} />
        </div>
        <WorkflowTable workspace={workspace.name} rows={summary.workflows} selected={selected} selectHref={selectHref} />
        {selected && detail ? <WorkflowDetail workspace={workspace.name} detail={detail} /> : null}
        {selected && errorLoadingDetail ? (
          <p className={styles.noRuns}>The detail for {selected} could not be loaded.</p>
        ) : null}
        <DurationSpread rows={summary.workflows} />
      </div>
    );
  }

  return (
    <InsightsContainer workspace={workspace}>
      <Selects workflowsData={workflowOptions} updateHistorySearch={updateHistorySearch} compare={compare} />
      {body}
    </InsightsContainer>
  );
}

interface InsightsContainerProps {
  workspace: FlowWorkspace;
  children: React.ReactNode;
}

function InsightsContainer({ workspace, children }: InsightsContainerProps) {
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

  return (
    <>
      <Helmet>
        <title>Insights</title>
      </Helmet>
      <Header
        includeBorder={false}
        nav={<NavigationComponent />}
        header={
          <>
            <HeaderTitle>Insights</HeaderTitle>
            <HeaderSubtitle>
              How reliable and how fast your workflows are, computed from the runs this workspace still holds.
              Runs of deleted workflows are not included; usage quotas keep counting them.
            </HeaderSubtitle>
          </>
        }
      />
      <div className={styles.container}>{children}</div>
    </>
  );
}

interface SelectsProps {
  workflowsData: Array<Workflow> | undefined;
  updateHistorySearch: (props: Record<string, unknown>) => void;
  compare: boolean;
}

function Selects(props: SelectsProps) {
  const location = useLocation();
  const search = queryString.parse(location.search, queryStringOptions);
  const { statuses, workflows, triggers, fromDate, toDate } = search;
  const selectedWorkflowRefs = typeof workflows === "string" ? [workflows] : workflows;
  const selectedStatuses = typeof statuses === "string" ? [statuses] : statuses;
  const selectedTriggers = typeof triggers === "string" ? [triggers] : triggers;
  const selectedFromDate = Array.isArray(fromDate)
    ? Number.parseInt(fromDate[0])
    : typeof fromDate === "string"
    ? Number.parseInt(fromDate)
    : defaultFromDate();
  const selectedToDate = Array.isArray(toDate)
    ? Number.parseInt(toDate[0])
    : typeof toDate === "string"
    ? Number.parseInt(toDate)
    : defaultToDate();

  function handleSelectWorkflows({ selectedItems }: MultiSelectItems<SelectableWorkflow>) {
    const workflowRefs = selectedItems.length > 0 ? selectedItems.map((worflow) => worflow.name) : undefined;
    props.updateHistorySearch({ ...search, workflows: workflowRefs });
  }

  function handleSelectStatuses({ selectedItems }: MultiSelectItems<SelectableStatus>) {
    const values = selectedItems.length > 0 ? selectedItems.map((status) => status.value) : undefined;
    props.updateHistorySearch({ ...search, statuses: values });
  }

  function handleSelectTriggers({ selectedItems }: MultiSelectItems<SelectableTrigger>) {
    const values = selectedItems.length > 0 ? selectedItems.map((trigger) => trigger.value) : undefined;
    props.updateHistorySearch({ ...search, triggers: values });
  }

  function handleSelectDate(dates: any) {
    const [fromDateObj, toDateObj] = dates as [Date, Date];
    if (!toDateObj) {
      return;
    }
    props.updateHistorySearch({
      ...search,
      fromDate: moment(fromDateObj).startOf("day").valueOf(),
      toDate: moment(toDateObj).endOf("day").valueOf(),
    });
  }

  function getWorkflowOptions() {
    return sortByProp(props.workflowsData ?? [], "name", "ASC") as Array<SelectableWorkflow>;
  }

  const itemToStringWorkflow = (workflow: SelectableWorkflow | null) => (workflow ? workflow.displayName : "");
  const itemToStringItem = (item: MultiSelectItem | null) => (item ? item.label : "");

  return (
    <div className={styles.dataFilters}>
      <FilterableMultiSelect<SelectableWorkflow>
        id="insights-workflows-select"
        placeholder="Choose workflow(s)"
        invalid={false}
        onChange={handleSelectWorkflows}
        items={getWorkflowOptions()}
        itemToString={itemToStringWorkflow}
        filterItems={filterItemsByLabel}
        compareItems={makeCompareItems(itemToStringWorkflow)}
        sortItems={sortItemsBySelection}
        initialSelectedItems={getWorkflowOptions().filter((workflow: Workflow) =>
          Boolean(selectedWorkflowRefs ? selectedWorkflowRefs.find((ref) => ref === workflow.name) : false),
        )}
        titleText="Filter by Workflow"
      />
      <FilterableMultiSelect<SelectableStatus>
        id="insights-statuses-select"
        placeholder="Choose status(es)"
        invalid={false}
        onChange={handleSelectStatuses}
        items={statusOptions}
        itemToString={itemToStringItem}
        filterItems={filterItemsByLabel}
        compareItems={makeCompareItems(itemToStringItem)}
        sortItems={sortItemsBySelection}
        initialSelectedItems={statusOptions.filter((option) =>
          Boolean(selectedStatuses?.find((status: string) => status === option.value)),
        )}
        titleText="Filter by status"
      />
      <FilterableMultiSelect<SelectableTrigger>
        id="insights-triggers-select"
        placeholder="Any trigger"
        invalid={false}
        onChange={handleSelectTriggers}
        items={triggerOptions}
        itemToString={itemToStringItem}
        filterItems={filterItemsByLabel}
        compareItems={makeCompareItems(itemToStringItem)}
        sortItems={sortItemsBySelection}
        initialSelectedItems={triggerOptions.filter((option) =>
          Boolean(selectedTriggers?.find((trigger: string) => trigger === option.value)),
        )}
        titleText="Filter by trigger"
      />
      <div className={styles.timeFilters}>
        <div className={styles.compare}>
          <Toggle
            id="insights-compare-toggle"
            label="Compare with previous period"
            onToggle={(checked: boolean) => props.updateHistorySearch({ ...search, compare: checked ? undefined : "off" })}
            toggled={props.compare}
          />
        </div>
        <DatePicker id="insights-date-picker" datePickerType="range" maxDate={defaultMaxDate()} onChange={handleSelectDate}>
          <DatePickerInput
            autoComplete="off"
            id="insights-date-picker-start"
            labelText="Start date"
            value={moment(selectedFromDate).format("MM/DD/YYYY")}
          />
          <DatePickerInput
            autoComplete="off"
            id="insights-date-picker-end"
            labelText="End date"
            value={moment(selectedToDate).format("MM/DD/YYYY")}
          />
        </DatePicker>
      </div>
    </div>
  );
}
