// @ts-nocheck
import React from "react";
import { DatePicker, DatePickerInput, FilterableMultiSelect } from "@carbon/react";
import { Error, ErrorMessage } from "@boomerang-io/carbon-addons-boomerang-react";
import { sortByProp } from "@boomerang-io/utils";
import moment from "moment";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { useHistory, useLocation, useRouteMatch } from "react-router-dom";
import { useTeamContext, useQuery } from "Hooks";
import { executionOptions, statusOptions } from "Constants/filterOptions";
import { queryStringOptions } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { PaginatedWorkflowResponse } from "Types";
import styles from "./Activity.module.scss";
import ActivityHeader from "./ActivityHeader";
import ActivityTable from "./ActivityTable";

const DEFAULT_ORDER = "DESC";
const DEFAULT_PAGE = 0;
const DEFAULT_LIMIT = 10;
const DEFAULT_SORT = "creationDate";

const DEFAULT_MAX_DATE = moment().format("MM/DD/YYYY");
const DEFAULT_FROM_DATE = moment().subtract(3, "months").valueOf();
const DEFAULT_TO_DATE = moment().endOf("day").valueOf();

function WorkflowActivity() {
  const { team } = useTeamContext();
  const history = useHistory();
  const location = useLocation();
  const match = useRouteMatch();

  const workflowRunCountQuery = queryString.stringify({
    fromDate: moment().startOf("day").valueOf(),
    toDate: moment().endOf("day").valueOf(),
  });

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
  } = queryString.parse(location.search, queryStringOptions);

  /** Retrieve Workflows */
  const getWorkflowsUrl = serviceUrl.team.workflow.getWorkflows({ team: team?.name });
  const workflowsQuery = useQuery<PaginatedWorkflowResponse, string>(getWorkflowsUrl);

  /**** Start get some data ****/
  const wfRunsURLQuery = queryString.stringify(
    {
      order,
      page,
      limit,
      sort,
      statuses,
      triggers,
      workflows,
      fromDate,
      toDate,
    },
    queryStringOptions,
  );

  const wfRunSummaryUrl = serviceUrl.team.workflowrun.getWorkflowRunCount({
    team: team?.name,
    query: workflowRunCountQuery,
  });
  const wfRunUrl = serviceUrl.team.workflowrun.getWorkflowRuns({ team: team?.name, query: wfRunsURLQuery });

  const wfRunSummaryQuery = useQuery(wfRunSummaryUrl);
  const wfRunQuery = useQuery(wfRunUrl);

  /**** End get some data ****/

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
    history.push({ search: queryStr });
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
    let workflowsList = [];
    if (workflowsQuery?.data?.content) {
      workflowsList = workflowsQuery.data.content;
    }
    return sortByProp(workflowsList, "name", "ASC");
  }
  /** End input handlers */

  /** Start Render Logic */
  if (wfRunQuery.error || workflowsQuery.error) {
    return (
      <>
        <ActivityHeader
          team={team}
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

  if (team && workflowsQuery.data) {
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

    const workflowNameMap = Array.isArray(workflowsQuery.data?.content)
      ? workflowsQuery.data.content.reduce(
          (acc, workflow) => {
            acc[workflow.name] = workflow.displayName;
            return acc;
          },
          {} as Record<string, string>,
        )
      : {};

    return (
      <>
        <Helmet>
          <title>Activity</title>
        </Helmet>
        <ActivityHeader
          team={team}
          isLoading={wfRunSummaryQuery.isLoading}
          inProgressActivities={
            (wfRunSummaryQuery.data?.status.running ?? 0) + (wfRunSummaryQuery.data?.status.waiting ?? 0)
          }
          failedActivities={wfRunSummaryQuery.data?.status.failed ?? 0}
          runActivities={wfRunSummaryQuery.data?.status.all ?? 0}
          succeededActivities={wfRunSummaryQuery.data?.status.succeeded ?? 0}
        />
        {wfRunQuery.isError ? (
          <section aria-label="Activity" className={styles.content}>
            <ErrorMessage />
          </section>
        ) : (
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
              history={history}
              isLoading={wfRunQuery.isLoading}
              location={location}
              match={match}
              tableData={wfRunQuery.data}
              sort={sort}
              order={order}
              updateHistorySearch={updateHistorySearch}
              workflowNameMap={workflowNameMap}
            />
          </section>
        )}
      </>
    );
  }
  return null;
}

export default WorkflowActivity;
