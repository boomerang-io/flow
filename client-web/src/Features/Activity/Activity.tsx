// @ts-nocheck
import React from "react";
import { DatePicker, DatePickerInput, FilterableMultiSelect } from "@carbon/react";
import { Error } from "@boomerang-io/carbon-addons-boomerang-react";
import { sortByProp } from "@boomerang-io/utils";
import moment from "moment";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { useLoaderData, useNavigate, useLocation } from "react-router-dom";
import { useWorkspaceContext } from "Hooks";
import { executionOptions, statusOptions } from "Constants/filterOptions";
import { queryStringOptions } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import styles from "./Activity.module.scss";
import ActivityHeader from "./ActivityHeader";
import ActivityTable from "./ActivityTable";

// Route module: this file's `loader` is attached to the route in app/routes/activity.tsx
// (path "/:workspace/activity") rather than being defined inline there, so the data-fetching
// code stays next to the component that consumes it - the same place the three `useQuery` calls
// this replaces used to live. See Features/Parameters/GlobalParameters/GlobalParameters.tsx for
// the pattern this follows.
//
// The active `workspace` object (needed for the header's displayName/breadcrumb) still comes
// from `useWorkspaceContext()` - it's resolved client-side by `WorkspaceContainer`
// (Features/App/App.tsx), which wraps this route same as it always has. That's unrelated to this
// loader/data-fetching migration (see Features/TaskManager/WorkspaceTasks/WorkspaceTasks.tsx for
// the same split: `params.workspace` - the URL slug - drives every server fetch below, while the
// full workspace object stays a client-side concern).

const DEFAULT_ORDER = "DESC";
const DEFAULT_PAGE = 0;
const DEFAULT_LIMIT = 10;
const DEFAULT_SORT = "creationDate";

const DEFAULT_MAX_DATE = moment().format("MM/DD/YYYY");
const DEFAULT_FROM_DATE = moment().subtract(3, "months").valueOf();
const DEFAULT_TO_DATE = moment().endOf("day").valueOf();

type LoaderData = {
  workflowOptions: Array<{ name: string; displayName: string }>;
  errorLoadingWorkflows: boolean;
  runSummary: {
    status: {
      all: number;
      running: number;
      waiting: number;
      failed: number;
      succeeded: number;
    };
  };
  runs: {
    number: number;
    size: number;
    totalElements: number;
    content: Array<Record<string, unknown>>;
  };
  errorLoadingRuns: boolean;
};

const EMPTY_RUN_SUMMARY: LoaderData["runSummary"] = {
  status: { all: 0, running: 0, waiting: 0, failed: 0, succeeded: 0 },
};

// Server loader (ssr:true - see CLAUDE.md client-web SSR direction). Runs in Node, so it uses
// serverFetch(request) rather than the browser `resolver`/axios instance in
// Config/servicesConfig.ts. `params.workspace` is the `:workspace` URL segment - available to the
// loader directly, no need for `useWorkspaceContext` (client-only). Every filter/pagination value
// (order/page/limit/sort/statuses/triggers/workflows/fromDate/toDate) is read off the request URL
// itself, preserving the exact param names the component already navigates with.
export async function loader({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<LoaderData> {
  const workspace = String(params.workspace);
  const {
    order = DEFAULT_ORDER,
    page = DEFAULT_PAGE,
    limit = DEFAULT_LIMIT,
    sort = DEFAULT_SORT,
    workflows,
    triggers,
    statuses,
    fromDate = DEFAULT_FROM_DATE,
    toDate = DEFAULT_TO_DATE,
  } = queryString.parse(new URL(request.url).search, queryStringOptions);

  let workflowOptions: LoaderData["workflowOptions"] = [];
  let errorLoadingWorkflows = false;
  try {
    const response = await serverFetch(request).get(serviceUrl.workspace.workflow.getWorkflows({ workspace }));
    workflowOptions = response.data.content;
  } catch (error) {
    errorLoadingWorkflows = true;
  }

  // Today's numbers for the header widgets - a fixed "today" range, independent of the table's
  // filters/pagination above. A failure here is swallowed (mirrors the previous
  // wfRunSummaryQuery.data?.status.x ?? 0 behaviour, which never gated the page into an error
  // state on its own) - the header widgets just render zeros.
  let runSummary: LoaderData["runSummary"] = EMPTY_RUN_SUMMARY;
  try {
    const summaryQuery = queryString.stringify(
      { fromDate: moment().startOf("day").valueOf(), toDate: moment().endOf("day").valueOf() },
      queryStringOptions,
    );
    const response = await serverFetch(request).get(
      serviceUrl.workspace.workflowrun.getWorkflowRunCount({ workspace, query: summaryQuery }),
    );
    runSummary = response.data;
  } catch (error) {
    // intentionally swallowed - see comment above.
  }

  let runs: LoaderData["runs"] = { number: 0, size: Number(limit) || DEFAULT_LIMIT, totalElements: 0, content: [] };
  let errorLoadingRuns = false;
  try {
    const runsQuery = queryString.stringify(
      { order, page, limit, sort, statuses, triggers, workflows, fromDate, toDate },
      queryStringOptions,
    );
    const response = await serverFetch(request).get(
      serviceUrl.workspace.workflowrun.getWorkflowRuns({ workspace, query: runsQuery }),
    );
    runs = response.data;
  } catch (error) {
    errorLoadingRuns = true;
  }

  return { workflowOptions, errorLoadingWorkflows, runSummary, runs, errorLoadingRuns };
}

function WorkflowActivity() {
  const { workspace } = useWorkspaceContext();
  const navigate = useNavigate();
  const location = useLocation();
  const { workflowOptions, errorLoadingWorkflows, runSummary, runs, errorLoadingRuns } = useLoaderData() as LoaderData;

  const {
    order = DEFAULT_ORDER,
    page = DEFAULT_PAGE,
    limit = DEFAULT_LIMIT,
    sort = DEFAULT_SORT,
    fromDate = DEFAULT_FROM_DATE,
    toDate = DEFAULT_TO_DATE,
  } = queryString.parse(location.search, queryStringOptions);

  /** Start input handlers */

  /**
   * Function that updates url search history to persist state
   * @param {object} query - all of the query params
   *
   */
  const updateHistorySearch = ({
    order = DEFAULT_ORDER,
    page = DEFAULT_PAGE,
    limit = DEFAULT_LIMIT,
    sort = DEFAULT_SORT,
    ...props
  }) => {
    const queryStr = `?${queryString.stringify({ order, page, limit, sort, ...props }, queryStringOptions)}`;
    navigate({ search: queryStr });
    return;
  };

  function handleSelectWorkflows({ selectedItems }) {
    const workflowRefs = selectedItems.length > 0 ? selectedItems.map((worflow) => worflow.name) : undefined;
    updateHistorySearch({
      ...queryString.parse(location.search, queryStringOptions),
      workflows: workflowRefs,
      page: 0,
    });
    return;
  }

  function handleSelectTriggers({ selectedItems }) {
    const triggers = selectedItems.length > 0 ? selectedItems.map((trigger) => trigger.value) : undefined;
    updateHistorySearch({ ...queryString.parse(location.search, queryStringOptions), triggers: triggers, page: 0 });
    return;
  }

  function handleSelectStatuses({ selectedItems }) {
    const statuses = selectedItems.length > 0 ? selectedItems.map((status) => status.value) : undefined;
    updateHistorySearch({ ...queryString.parse(location.search, queryStringOptions), statuses: statuses, page: 0 });
    return;
  }

  function handleSelectDate(dates) {
    let [fromDateObj, toDateObj] = dates;
    if (!toDateObj) {
      return;
    }
    const fromDate = moment(fromDateObj).startOf("day").valueOf();
    const toDate = moment(toDateObj).endOf("day").valueOf();
    updateHistorySearch({ ...queryString.parse(location.search, queryStringOptions), fromDate, toDate, page: 0 });
    return;
  }

  function getWorkflowFilter() {
    return sortByProp(workflowOptions, "name", "ASC");
  }
  /** End input handlers */

  /** Start Render Logic */
  // The workspace object itself is a client-side concern (see the module doc above) - until
  // WorkspaceContainer resolves it, there's nothing to render yet.
  if (!workspace) {
    return null;
  }

  if (errorLoadingRuns || errorLoadingWorkflows) {
    return (
      <>
        <ActivityHeader
          workspace={workspace}
          failedActivities={"--"}
          inProgressActivities={"--"}
          isError={true}
          isLoading={false}
          runActivities={"--"}
          succeededActivities={"--"}
        />
        <section aria-label="Activity Error" className={styles.content}>
          <Error />
        </section>
      </>
    );
  }

  const { workflows = "", triggers = "", statuses = "" } = queryString.parse(location.search, queryStringOptions);
  const selectedWorkflowIds = typeof workflows === "string" ? [workflows] : workflows;
  const selectedTriggers = typeof triggers === "string" ? [triggers] : triggers;
  const selectedStatuses = typeof statuses === "string" ? [statuses] : statuses;
  const selectedFromDate = Array.isArray(fromDate)
    ? Number.parseInt(fromDate[0])
    : typeof fromDate === "string"
    ? Number.parseInt(fromDate)
    : DEFAULT_FROM_DATE;
  const selectedToDate = Array.isArray(toDate)
    ? Number.parseInt(toDate[0])
    : typeof toDate === "string"
    ? Number.parseInt(toDate)
    : DEFAULT_TO_DATE;

  const workflowNameMap = workflowOptions.reduce((acc, workflow) => {
    acc[workflow.name] = workflow.displayName;
    return acc;
  }, {} as Record<string, string>);

  return (
    <>
      <Helmet>
        <title>Activity</title>
      </Helmet>
      <ActivityHeader
        workspace={workspace}
        isLoading={false}
        inProgressActivities={(runSummary.status.running ?? 0) + (runSummary.status.waiting ?? 0)}
        failedActivities={runSummary.status.failed ?? 0}
        runActivities={runSummary.status.all ?? 0}
        succeededActivities={runSummary.status.succeeded ?? 0}
      />
      <section aria-label="Activity" className={styles.content}>
        <div className={styles.filtersContainer}>
          <div className={styles.dataFilters}>
            <div className={styles.dataFilter}>
              <FilterableMultiSelect
                id="activity-workflows-select"
                label="Choose workflow(s)"
                placeholder="Choose workflow(s)"
                invalid={false}
                onChange={handleSelectWorkflows}
                items={getWorkflowFilter()}
                itemToString={(workflow) => {
                  return workflow.displayName;
                }}
                initialSelectedItems={getWorkflowFilter().filter((workflow) =>
                  Boolean(selectedWorkflowIds.find((ref) => ref === workflow.name)),
                )}
                titleText="Filter by Workflow"
              />
            </div>
            <div className={styles.dataFilter}>
              <FilterableMultiSelect
                id="activity-status-select"
                label="Choose status(es)"
                placeholder="Choose status(es)"
                invalid={false}
                onChange={handleSelectStatuses}
                items={statusOptions}
                itemToString={(item) => (item ? item.label : "")}
                initialSelectedItems={statusOptions.filter((option) =>
                  Boolean(selectedStatuses.find((status) => status === option.value)),
                )}
                titleText="Filter by status"
              />
            </div>
            <div className={styles.dataFilter}>
              <FilterableMultiSelect
                id="activity-trigger-select"
                label="Choose trigger type(s)"
                placeholder="Choose trigger type(s)"
                invalid={false}
                onChange={handleSelectTriggers}
                items={executionOptions}
                itemToString={(item) => (item ? item.label : "")}
                initialSelectedItems={executionOptions.filter((option) =>
                  Boolean(selectedTriggers.find((trigger) => trigger === option.value)),
                )}
                titleText="Filter by trigger"
              />
            </div>
          </div>
          <DatePicker
            id="activity-date-picker"
            className={styles.timeFilters}
            datePickerType="range"
            maxDate={DEFAULT_MAX_DATE}
            onChange={handleSelectDate}
          >
            <DatePickerInput
              autoComplete="off"
              id="activity-date-picker-start"
              labelText="Start date"
              value={moment(selectedFromDate).format("MM/DD/YYYY")}
            />
            <DatePickerInput
              autoComplete="off"
              id="activity-date-picker-end"
              labelText="End date"
              value={moment(selectedToDate).format("MM/DD/YYYY")}
            />
          </DatePicker>
        </div>
        <ActivityTable
          navigate={navigate}
          isLoading={false}
          location={location}
          tableData={runs}
          sort={sort}
          order={order}
          updateHistorySearch={updateHistorySearch}
          workflowNameMap={workflowNameMap}
        />
      </section>
    </>
  );
}

export default WorkflowActivity;
