//@ts-nocheck
import React from "react";
import { DatePicker, DatePickerInput, FilterableMultiSelect, Breadcrumb, BreadcrumbItem } from "@carbon/react";
import { ArrowUpRight } from "@carbon/react/icons";
import {
  ErrorMessage,
  ErrorDragon,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
  FeatureNavTab as Tab,
  FeatureNavTabs as Tabs,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage, sortByProp } from "@boomerang-io/utils";
import moment from "moment";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { Navigate, Route, Routes, useLoaderData, useNavigate, useLocation, Link } from "react-router-dom";
import HeaderWidget from "Components/HeaderWidget";
import { useWorkspaceContext } from "Hooks";
import { ActionType, HttpMethod } from "Constants";
import { approvalStatusOptions } from "Constants/filterOptions";
import { appLink, queryStringOptions } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { Action, PaginatedWorkflowResponse } from "Types";
import { actionError, type ActionError } from "Utils/actionResult";
import styles from "./Actions.module.scss";
import ActionsTable from "./ActionsTable";

// Route module: this file's `loader`/`action` are re-exported from app/routes/actions.tsx
// (path="/:workspace/actions/*") - see GlobalParameters.tsx for the serverFetch/errorLoading/
// ssr:true contract this follows.
//
// The internal <Routes> below (approvals/manual) are NOT lifted into app/routes.ts as real nested
// routes - the batch instructions call restructuring the shared route config a stop-and-report
// decision, and it isn't needed here anyway: the loader can tell which tab is active from the
// splat (`params["*"]`) react-router hands a "/*" route, the same way AdminTasks.tsx/
// WorkspaceTasks.tsx do. Because react-router re-runs a route's loader whenever the resolved
// Location changes - splat AND search params both included - switching tabs or changing a filter
// both trigger a fresh fetch even though the route pattern itself never changes.
const DEFAULT_ORDER = "DESC";
const DEFAULT_PAGE = 0;
const DEFAULT_LIMIT = 10;
const DEFAULT_SORT = "creationDate";
/*
 * Computed per call, not hoisted to module constants: this module is imported ONCE into a
 * long-lived Node server (ssr:true), so a module-level `moment()` freezes the default window at
 * process boot and every later request reuses it - the page would silently omit newer records
 * while the client-rendered date picker showed today, and a refresh would not help.
 * Features/WorkflowEditor/editorRoute.ts documents the same hazard.
 */
const defaultFromDate = () => moment(new Date()).subtract("24", "hours").unix();
const defaultToDate = () => moment(new Date()).unix();

type ActionsSummary = { approvals: number; manual: number; approvalsRate: number };
type ActionsTableData = { number: number; size: number; totalElements: number; content: Action[] };

type LoaderData = {
  actionType: string;
  actionsSummary: ActionsSummary | null;
  errorLoadingActionsSummary: boolean;
  filterSummary: ActionsSummary | null;
  actionsTable: ActionsTableData | null;
  errorLoadingActionsTable: boolean;
  workflowsData: PaginatedWorkflowResponse | null;
  errorLoadingWorkflows: boolean;
};

export async function loader({
  params,
  request,
}: {
  params: { workspace?: string; "*"?: string };
  request: Request;
}): Promise<LoaderData> {
  const workspace = String(params.workspace);
  const url = new URL(request.url);
  const parsedQuery = queryString.parse(url.search, queryStringOptions);

  const [tab] = (params["*"] ?? "").split("/").filter(Boolean);
  const actionType = tab === "manual" ? ActionType.Manual : ActionType.Approval;

  const order = typeof parsedQuery.order === "string" ? parsedQuery.order : DEFAULT_ORDER;
  const page = parsedQuery.page ?? DEFAULT_PAGE;
  const limit = parsedQuery.limit ?? DEFAULT_LIMIT;
  const sort = typeof parsedQuery.sort === "string" ? parsedQuery.sort : DEFAULT_SORT;
  const { workflows, statuses, fromDate, toDate } = parsedQuery;

  /*
   * One wave, not four. None of these reads depends on another - they were four independent
   * useQuery calls firing on mount before this route moved onto the router - and a loader blocks
   * first paint with no pending UI behind it (useNavigation() is used nowhere in the app), so
   * awaiting them in sequence turns a cold load into four round trips of blank screen. See
   * Features/WorkflowEditor/editorRoute.ts for the same shape.
   *
   * `allSettled` rather than `all`: each read's failure has to stay its own, exactly as the
   * per-call try/catch below did - one rejection must not blank out the other three.
   */
  const api = serverFetch(request);

  /** Today's numbers, independent of the filters above */
  const summaryQuery = queryString.stringify({ fromDate: defaultFromDate(), toDate: defaultToDate() });
  /** Table data */
  const actionsUrlQuery = queryString.stringify(
    { order, page, limit, sort, statuses, workspaces: workspace, types: actionType, workflows, fromDate, toDate },
    queryStringOptions,
  );
  /** Number of approvals/manual tasks under the current filters, for the tab labels */
  const actionsUrlSummaryQuery = queryString.stringify({ workflows, fromDate, toDate }, queryStringOptions);

  const [summaryResult, tableResult, filterSummaryResult, workflowsResult] = await Promise.allSettled([
    api.get(serviceUrl.workspace.action.getActionsSummary({ workspace, query: summaryQuery })),
    api.get(serviceUrl.workspace.action.getActions({ workspace, query: actionsUrlQuery })),
    api.get(serviceUrl.workspace.action.getActionsSummary({ workspace, query: actionsUrlSummaryQuery })),
    /** Workflows, for the "Choose workflow(s)" filter */
    api.get(serviceUrl.workspace.workflow.getWorkflows({ workspace })),
  ]);

  const actionsSummary: ActionsSummary | null = summaryResult.status === "fulfilled" ? summaryResult.value.data : null;
  const errorLoadingActionsSummary = summaryResult.status === "rejected";

  const actionsTable: ActionsTableData | null = tableResult.status === "fulfilled" ? tableResult.value.data : null;
  const errorLoadingActionsTable = tableResult.status === "rejected";

  // No error flag, matching the previous useQuery's un-branched error handling: filterSummary
  // stays null and the tab-label counts default to 0.
  const filterSummary: ActionsSummary | null =
    filterSummaryResult.status === "fulfilled" ? filterSummaryResult.value.data : null;

  const workflowsData: PaginatedWorkflowResponse | null =
    workflowsResult.status === "fulfilled" ? workflowsResult.value.data : null;
  const errorLoadingWorkflows = workflowsResult.status === "rejected";

  return {
    actionType,
    actionsSummary,
    errorLoadingActionsSummary,
    filterSummary,
    actionsTable,
    errorLoadingActionsTable,
    workflowsData,
    errorLoadingWorkflows,
  };
}

export type ActionResult = { intent: "putAction" } | ({ intent: "putAction" } & ActionError);

export async function action({ params, request }: { params: { workspace?: string }; request: Request }) {
  const workspace = String(params.workspace);
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "putAction") {
    const body = JSON.parse(String(formData.get("body")));
    try {
      await serverFetch(request)({
        url: serviceUrl.workspace.action.putAction({ workspace }),
        data: body,
        method: HttpMethod.Put,
      });
      return { intent: "putAction" as const };
    } catch (error) {
      return actionError({
        intent: "putAction" as const,
        error: formatErrorMessage({ error, defaultMessage: "Request to action failed" }),
      });
    }
  }

  return actionError({ intent: "putAction" as const, error: { title: "Something's Wrong", message: "Unknown action" } });
}

function Actions() {
  const { workspace } = useWorkspaceContext();
  const navigate = useNavigate();
  const location = useLocation();
  const {
    actionsSummary,
    errorLoadingActionsSummary,
    filterSummary,
    actionsTable,
    errorLoadingActionsTable,
    workflowsData,
    errorLoadingWorkflows,
  } = useLoaderData() as LoaderData;

  const approvalsSummaryNumber = actionsSummary ? actionsSummary.approvals : 0;
  const manualTasksSummaryNumber = actionsSummary ? actionsSummary.manual : 0;
  const approvalsRatePercentage = actionsSummary ? actionsSummary.approvalsRate : 0;
  const emoji = approvalsRatePercentage > 79 ? "🙌" : approvalsRatePercentage > 49 ? "😮" : "😨";

  const {
    order = DEFAULT_ORDER,
    page = DEFAULT_PAGE,
    limit = DEFAULT_LIMIT,
    sort = DEFAULT_SORT,
    fromDate,
    toDate,
  } = queryString.parse(location.search, queryStringOptions);

  const approvalsNumber = filterSummary ? filterSummary.approvals : 0;
  const manualTasksNumber = filterSummary ? filterSummary.manual : 0;

  if (errorLoadingWorkflows) {
    return <ErrorDragon />;
  }

  /**
   * Filters handlers
   */

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

  function handleSelectStatuses({ selectedItems }) {
    const statuses = selectedItems.length > 0 ? selectedItems.map((status) => status.value) : undefined;
    updateHistorySearch({ ...queryString.parse(location.search, queryStringOptions), statuses: statuses, page: 0 });
    return;
  }

  function handleSelectDate(dates) {
    let [fromDateObj, toDateObj] = dates;
    const fromDate = moment(fromDateObj).unix();
    const toDate = moment(toDateObj).unix();
    updateHistorySearch({ ...queryString.parse(location.search, queryStringOptions), fromDate, toDate, page: 0 });
    return;
  }

  const handleCloseSelectDate = (dates) => {
    let [fromDateObj, toDateObj] = dates;
    const selectedFromDate = moment(fromDateObj).unix();
    const selectedToDate = moment(toDateObj).unix();
    updateHistorySearch({
      ...queryString.parse(location.search, queryStringOptions),
      fromDate: selectedFromDate === selectedToDate ? fromDate : selectedFromDate,
      toDate: selectedToDate,
      page: 0,
    });
    return;
  };

  function getWorkflowFilter() {
    let workflowsList = [];
    if (workflowsData?.content) {
      workflowsList = workflowsData.content;
    }
    return sortByProp(workflowsList, "name", "ASC");
  }

  if (workspace && workflowsData?.content) {
    const { workflows = "", statuses = "" } = queryString.parse(location.search, queryStringOptions);
    const selectedWorkflowRefs = typeof workflows === "string" ? [workflows] : workflows;
    const selectedStatuses = typeof statuses === "string" ? [statuses] : statuses;
    const maxDate = moment().format("MM/DD/YYYY");

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
        <Routes>
          <Route
            path="approvals"
            element={
              <Helmet>
                <title>Approval - Actions</title>
              </Helmet>
            }
          />
          <Route
            path="manual"
            element={
              <Helmet>
                <title>Manual - Actions</title>
              </Helmet>
            }
          />
          {/*
            * `appLink.*` (a builder that takes the params), never `AppPath.*` (the route PATTERN):
            * v5's <Redirect from to> interpolated ":workspace" from the current match, v7's
            * <Navigate> does not, so an AppPath here navigates to the literal "/:workspace/..."
            * URL and the loader fetches a workspace by that name.
            */}
          <Route path="" element={<Navigate to={appLink.actionsApprovals({ workspace: workspace.name })} replace />} />
        </Routes>
        <Header
          className={styles.header}
          includeBorder={false}
          nav={<NavigationComponent />}
          header={
            <>
              <HeaderTitle className={styles.headerTitle}>Actions</HeaderTitle>
              <HeaderSubtitle className={styles.headerMessage}>
                View and manage your approvals and manual tasks.
              </HeaderSubtitle>
            </>
          }
          actions={
            <section className={styles.headerSummary}>
              <p className={styles.headerSummaryText}>Today's numbers</p>
              {errorLoadingActionsSummary ? (
                <>
                  <HeaderWidget text="Manual" value="--" />
                  <HeaderWidget text="Approval" value="--" />
                  <HeaderWidget text="Approval rate" value="--" />
                </>
              ) : (
                <>
                  <HeaderWidget icon={ArrowUpRight} text="Manual" value={manualTasksSummaryNumber} />
                  <HeaderWidget icon={ArrowUpRight} text="Approval" value={approvalsSummaryNumber} />
                  <HeaderWidget icon={emoji} text="Approval rate" value={`${approvalsRatePercentage}%`} />
                </>
              )}
            </section>
          }
          footer={
            <Tabs ariaLabel="Action types">
              <Tab
                end
                label={`Approvals (${approvalsNumber})`}
                to={{
                  pathname: appLink.actionsApprovals({ workspace: workspace.name }),
                  search: location.search,
                }}
              />
              <Tab
                end
                label={`Manual Tasks (${manualTasksNumber})`}
                to={{
                  pathname: appLink.actionsManual({ workspace: workspace.name }),
                  search: location.search,
                }}
              />
            </Tabs>
          }
        />
        {errorLoadingActionsTable || !actionsTable ? (
          <section aria-label="Actions" className={styles.content}>
            <ErrorMessage />
          </section>
        ) : (
          <section aria-label="Actions" className={styles.content}>
            <div className={styles.filtersContainer}>
              <div className={styles.dataFilters}>
                <div className={styles.dataFilter}>
                  <FilterableMultiSelect
                    id="actions-workflows-select"
                    label="Choose workflow(s)"
                    placeholder="Choose workflow(s)"
                    invalid={false}
                    onChange={handleSelectWorkflows}
                    items={getWorkflowFilter()}
                    itemToString={(workflow) => {
                      return workflow.displayName;
                    }}
                    initialSelectedItems={getWorkflowFilter().filter((workflow) =>
                      Boolean(selectedWorkflowRefs.find((ref) => ref === workflow.name)),
                    )}
                    titleText="Filter by Workflow"
                  />
                </div>
                <div className={styles.dataFilter}>
                  <FilterableMultiSelect
                    id="actions-statuses-select"
                    label="Choose status(es)"
                    placeholder="Choose status(es)"
                    invalid={false}
                    onChange={handleSelectStatuses}
                    items={approvalStatusOptions}
                    itemToString={(item) => (item ? item.label : "")}
                    initialSelectedItems={approvalStatusOptions.filter((option) =>
                      Boolean(selectedStatuses.find((status) => status === option.value)),
                    )}
                    titleText="Filter by status"
                  />
                </div>
              </div>
              <DatePicker
                id="actions-date-picker"
                className={styles.timeFilters}
                datePickerType="range"
                maxDate={maxDate}
                onChange={handleSelectDate}
                onClose={handleCloseSelectDate}
              >
                <DatePickerInput
                  autoComplete="off"
                  id="actions-date-picker-start"
                  labelText="Start date"
                  placeholder="mm/dd/yyyy"
                  value={fromDate && moment.unix(fromDate).format("MM/DD/YYYY")}
                />
                <DatePickerInput
                  autoComplete="off"
                  id="actions-date-picker-end"
                  labelText="End date"
                  placeholder="mm/dd/yyyy"
                  value={toDate && moment.unix(toDate).format("MM/DD/YYYY")}
                />
              </DatePicker>
            </div>
            <ActionsTable
              isLoading={false}
              location={location}
              tableData={actionsTable}
              sort={sort}
              order={order}
              updateHistorySearch={updateHistorySearch}
            />
          </section>
        )}
      </>
    );
  }

  return null;
}

export default Actions;
