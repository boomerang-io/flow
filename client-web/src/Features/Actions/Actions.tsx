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
import { AppPath, appLink, queryStringOptions } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { Action, PaginatedWorkflowResponse } from "Types";
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
const DEFAULT_FROM_DATE = moment(new Date()).subtract("24", "hours").unix();
const DEFAULT_TO_DATE = moment(new Date()).unix();

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

  /** Today's numbers, independent of the filters above */
  const summaryQuery = queryString.stringify({ fromDate: DEFAULT_FROM_DATE, toDate: DEFAULT_TO_DATE });
  let actionsSummary: ActionsSummary | null = null;
  let errorLoadingActionsSummary = false;
  try {
    const response = await serverFetch(request).get(
      serviceUrl.workspace.action.getActionsSummary({ workspace, query: summaryQuery }),
    );
    actionsSummary = response.data;
  } catch (error) {
    errorLoadingActionsSummary = true;
  }

  /** Table data */
  const actionsUrlQuery = queryString.stringify(
    { order, page, limit, sort, statuses, workspaces: workspace, types: actionType, workflows, fromDate, toDate },
    queryStringOptions,
  );
  let actionsTable: ActionsTableData | null = null;
  let errorLoadingActionsTable = false;
  try {
    const response = await serverFetch(request).get(
      serviceUrl.workspace.action.getActions({ workspace, query: actionsUrlQuery }),
    );
    actionsTable = response.data;
  } catch (error) {
    errorLoadingActionsTable = true;
  }

  /** Number of approvals/manual tasks under the current filters, for the tab labels */
  const actionsUrlSummaryQuery = queryString.stringify({ workflows, fromDate, toDate }, queryStringOptions);
  let filterSummary: ActionsSummary | null = null;
  try {
    const response = await serverFetch(request).get(
      serviceUrl.workspace.action.getActionsSummary({ workspace, query: actionsUrlSummaryQuery }),
    );
    filterSummary = response.data;
  } catch (error) {
    // Matches the previous useQuery's un-branched error handling: filterSummary stays null and
    // the tab-label counts below default to 0.
  }

  /** Workflows, for the "Choose workflow(s)" filter */
  let workflowsData: PaginatedWorkflowResponse | null = null;
  let errorLoadingWorkflows = false;
  try {
    const response = await serverFetch(request).get(serviceUrl.workspace.workflow.getWorkflows({ workspace }));
    workflowsData = response.data;
  } catch (error) {
    errorLoadingWorkflows = true;
  }

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

export type ActionResult =
  | { ok: true; intent: "putAction" }
  | { ok: false; intent: "putAction"; errorMessage: { title: string; message: string } };

export async function action({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<ActionResult> {
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
      return { ok: true, intent: "putAction" };
    } catch (error) {
      return {
        ok: false,
        intent: "putAction",
        errorMessage: formatErrorMessage({ error, defaultMessage: "Request to action failed" }),
      };
    }
  }

  return { ok: false, intent: "putAction", errorMessage: { title: "Something's Wrong", message: "Unknown action" } };
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
          <Route path="" element={<Navigate to={AppPath.ActionsApprovals} replace />} />
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
