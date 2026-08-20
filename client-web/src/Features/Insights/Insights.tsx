import React from "react";
import {
  DatePicker,
  DatePickerInput,
  FilterableMultiSelect,
  Breadcrumb,
  BreadcrumbItem,
} from "@carbon/react";
import {
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { sortByProp } from "@boomerang-io/utils";
import moment from "moment";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { useLoaderData, useNavigate, useLocation, Link } from "react-router-dom";
import ErrorDragon from "Components/ErrorDragon";
import { useWorkspaceContext } from "Hooks";
import { timeSecondsToTimeUnit } from "Utils/timeSecondsToTimeUnit";
import { executionOptions, statusOptions } from "Constants/filterOptions";
import { queryStringOptions, appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import type { RunStatus, MultiSelectItem, MultiSelectItems, Workflow, FlowWorkspace } from "Types";
import CarbonDonutChart from "./CarbonDonutChart";
import CarbonLineChart from "./CarbonLineChart";
import CarbonScatterChart from "./CarbonScatterChart";
import ChartsTile from "./ChartsTile";
import styles from "./Insights.module.scss";
import InsightsTile from "./InsightsTile";
import { parseChartsData } from "./utils/formatData";

// Route module: this file's `loader` is attached to the route in app/routes/insights.tsx
// (path "/:workspace/insights"). See Features/Activity/Activity.tsx's module doc for the same
// split this follows: `params.workspace` (the URL slug) drives every server fetch below, while
// the full workspace object (for the header's displayName/breadcrumb) stays a client-side
// concern, resolved by `WorkspaceContainer` same as it always has been.

export interface InsightsRuns {
  creationDate: string;
  duration: number;
  status: RunStatus;
  workflowRef: string;
  workflowName: string;
}
interface WorkflowInsightsRes {
  concurrentRun: number;
  totalRuns: number;
  totalDuration: number;
  medianDuration: number;
  runs: Array<InsightsRuns>;
}

const maxDate = moment().format("MM/DD/YYYY");
const defaultFromDate = moment().subtract(3, "months").valueOf();
const defaultToDate = moment().endOf("day").valueOf();

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

const EMPTY_INSIGHTS: WorkflowInsightsRes = { concurrentRun: 0, totalRuns: 0, totalDuration: 0, medianDuration: 0, runs: [] };

type LoaderData = {
  insights: WorkflowInsightsRes;
  errorLoadingInsights: boolean;
  workflowOptions: Array<Workflow>;
  errorLoadingWorkflows: boolean;
};

// Server loader (ssr:true - see CLAUDE.md client-web SSR direction). Runs in Node, so it uses
// serverFetch(request) rather than the browser `resolver`/axios instance in
// Config/servicesConfig.ts. Every filter value (statuses/workflows/fromDate/toDate) is read off
// the request URL itself, preserving the exact param names the component already navigates with.
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
    fromDate = defaultFromDate,
    toDate = defaultToDate,
  } = queryString.parse(new URL(request.url).search, queryStringOptions);

  let insights: WorkflowInsightsRes = EMPTY_INSIGHTS;
  let errorLoadingInsights = false;
  try {
    const insightsSearchParams = queryString.stringify({ statuses, workflows, fromDate, toDate }, queryStringOptions);
    const response = await serverFetch(request).get(
      serviceUrl.workspace.getInsights({ workspace, query: insightsSearchParams }),
    );
    insights = response.data;
  } catch (error) {
    errorLoadingInsights = true;
  }

  let workflowOptions: Array<Workflow> = [];
  let errorLoadingWorkflows = false;
  try {
    const response = await serverFetch(request).get(serviceUrl.workspace.workflow.getWorkflows({ workspace }));
    workflowOptions = response.data.content;
  } catch (error) {
    errorLoadingWorkflows = true;
  }

  return { insights, errorLoadingInsights, workflowOptions, errorLoadingWorkflows };
}

export default function Insights() {
  const { workspace } = useWorkspaceContext();
  const navigate = useNavigate();
  const location = useLocation();
  const { insights, errorLoadingInsights, workflowOptions, errorLoadingWorkflows } = useLoaderData() as LoaderData;

  function updateHistorySearch({ ...props }) {
    const queryStr = `?${queryString.stringify({ ...props }, queryStringOptions)}`;
    navigate({ search: queryStr });
    return;
  }

  // The workspace object itself is a client-side concern (see the module doc above) - until
  // WorkspaceContainer resolves it, there's nothing to render yet.
  if (!workspace) {
    return null;
  }

  if (errorLoadingInsights || errorLoadingWorkflows) {
    return (
      <InsightsContainer workspace={workspace}>
        <Selects workflowsData={workflowOptions} updateHistorySearch={updateHistorySearch} />
        <ErrorDragon />
      </InsightsContainer>
    );
  }

  const { statuses } = queryString.parse(location.search, queryStringOptions);

  return (
    <InsightsContainer workspace={workspace}>
      <Selects workflowsData={workflowOptions} updateHistorySearch={updateHistorySearch} />
      <Graphs data={insights} statuses={statuses as RunStatus | Array<RunStatus> | null} />
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
        className={styles.header}
        includeBorder={false}
        nav={<NavigationComponent />}
        header={
          <>
            <HeaderTitle>Insights</HeaderTitle>
            <HeaderSubtitle>Gain valuable insight by digging deeper into the Workflow runs</HeaderSubtitle>
          </>
        }
      />
      <div className={styles.container}>{children}</div>
    </>
  );
}

interface SelectsProps {
  workflowsData: Array<Workflow> | undefined;
  updateHistorySearch: any;
}

function Selects(props: SelectsProps) {
  const location = useLocation();

  const { statuses, workflows, fromDate, toDate } = queryString.parse(location.search, queryStringOptions);
  const selectedWorkflowRefs = typeof workflows === "string" ? [workflows] : workflows;
  const selectedStatuses = typeof statuses === "string" ? [statuses] : statuses;
  const selectedFromDate = Array.isArray(fromDate)
    ? Number.parseInt(fromDate[0])
    : typeof fromDate === "string"
    ? Number.parseInt(fromDate)
    : defaultFromDate;
  const selectedToDate = Array.isArray(toDate)
    ? Number.parseInt(toDate[0])
    : typeof toDate === "string"
    ? Number.parseInt(toDate)
    : defaultToDate;

  function handleSelectWorkflows({ selectedItems }: MultiSelectItems<SelectableWorkflow>) {
    const workflowRefs = selectedItems.length > 0 ? selectedItems.map((worflow) => worflow.name) : undefined;
    props.updateHistorySearch({
      ...queryString.parse(location.search, queryStringOptions),
      workflows: workflowRefs,
      page: 0,
    });
    return;
  }

  function handleSelectStatuses({ selectedItems }: MultiSelectItems<SelectableStatus>) {
    //@ts-ignore next-line
    const statuses = selectedItems.length > 0 ? selectedItems.map((status) => status.value) : undefined;
    props.updateHistorySearch({ ...queryString.parse(location.search, queryStringOptions), statuses: statuses });
    return;
  }

  function handleSelectDate(dates: any) {
    let [fromDateObj, toDateObj] = dates as [Date, Date];
    if (!toDateObj) {
      return;
    }
    const fromDate = moment(fromDateObj).startOf("day").valueOf();
    const toDate = moment(toDateObj).endOf("day").valueOf();
    props.updateHistorySearch({ ...queryString.parse(location.search, queryStringOptions), fromDate, toDate });
    return;
  }

  function getWorkflowOptions() {
    let workflowsList: Array<Workflow> = [];
    if (props.workflowsData) {
      workflowsList = props.workflowsData;
    }
    return sortByProp(workflowsList, "name", "ASC");
  }

  const itemToStringWorkflow = (workflow: SelectableWorkflow | null) => (workflow ? workflow.displayName : "");
  const itemToStringStatus = (item: SelectableStatus | null) => (item ? item.label : "");

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
        itemToString={itemToStringStatus}
        filterItems={filterItemsByLabel}
        compareItems={makeCompareItems(itemToStringStatus)}
        sortItems={sortItemsBySelection}
        initialSelectedItems={statusOptions.filter((option) =>
          Boolean(selectedStatuses?.find((status: string) => status === option.value)),
        )}
        titleText="Filter by status"
      />
      <div className={styles.timeFilters}>
        <DatePicker id="insights-date-picker" datePickerType="range" maxDate={maxDate} onChange={handleSelectDate}>
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

interface GraphsProps {
  data: WorkflowInsightsRes;
  statuses: RunStatus | RunStatus[] | null;
}

function Graphs(props: GraphsProps) {
  const { data, statuses } = props;
  const { donutData, durationData, lineChartData, scatterPlotData, executionsCountList } = React.useMemo(
    () => parseChartsData(data.runs, statuses),
    [data.runs, statuses],
  );

  const totalRuns = data.totalRuns;
  const medianExecutionTime = Math.round(data.medianDuration / 1000);
  return (
    <>
      <div className={styles.statsWidgets} data-testid="completed-insights">
        <InsightsTile title="Runs" type="runs" totalCount={totalRuns} infoList={executionsCountList.slice(0, 5)} />
        <InsightsTile
          title="Duration (median)"
          type=""
          totalCount={timeSecondsToTimeUnit(medianExecutionTime)}
          infoList={durationData}
          valueWidth="7rem"
        />
        <div className={styles.donut}>
          {totalRuns === 0 ? (
            <p className={`${styles.statsLabel} --no-data`}>No Data</p>
          ) : (
            <CarbonDonutChart data={donutData} title="Status" />
          )}
        </div>
      </div>
      <div className={styles.graphsWidgets}>
        <ChartsTile>
          {totalRuns === 0 ? (
            <p className={`${styles.graphsLabel} --no-data`}>No Data</p>
          ) : (
            <CarbonLineChart data={lineChartData} title="Runs" />
          )}
        </ChartsTile>
        <ChartsTile>
          {totalRuns === 0 ? (
            <p className={`${styles.graphsLabel} --no-data`}>No Data</p>
          ) : (
            <CarbonScatterChart data={scatterPlotData} title="Run Time" />
          )}
        </ChartsTile>
      </div>
    </>
  );
}
