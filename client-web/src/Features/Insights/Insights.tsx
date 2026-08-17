import React from "react";
import {
  DatePicker,
  DatePickerInput,
  FilterableMultiSelect,
  SkeletonPlaceholder,
  Breadcrumb,
  BreadcrumbItem,
} from "@carbon/react";
import {
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { sortByProp } from "@boomerang-io/utils";
import cx from "classnames";
import moment from "moment";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { useQuery } from "react-query";
import { useHistory, useLocation, Link } from "react-router-dom";
import ErrorDragon from "Components/ErrorDragon";
import { useTeamContext } from "Hooks";
import { timeSecondsToTimeUnit } from "Utils/timeSecondsToTimeUnit";
import { executionOptions, statusOptions } from "Constants/filterOptions";
import { queryStringOptions, appLink } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
import type {
  RunStatus,
  PaginatedWorkflowResponse,
  MultiSelectItem,
  MultiSelectItems,
  Workflow,
  FlowTeam,
} from "Types";
import CarbonDonutChart from "./CarbonDonutChart";
import CarbonLineChart from "./CarbonLineChart";
import CarbonScatterChart from "./CarbonScatterChart";
import ChartsTile from "./ChartsTile";
import styles from "./Insights.module.scss";
import InsightsTile from "./InsightsTile";
import { parseChartsData } from "./utils/formatData";

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

export default function Insights() {
  const { team } = useTeamContext();
  const history = useHistory();
  const location = useLocation();

  /**
   * Get insights data
   */
  const {
    statuses,
    workflows,
    fromDate = defaultFromDate,
    toDate = defaultToDate,
  } = queryString.parse(location.search, queryStringOptions);

  const insightsSearchParams = queryString.stringify(
    {
      statuses,
      workflows,
      fromDate,
      toDate,
    },
    queryStringOptions,
  );

  const insightsUrl = serviceUrl.team.getInsights({ team: team?.name, query: insightsSearchParams });
  const insightsQuery = useQuery<WorkflowInsightsRes>({
    queryKey: insightsUrl,
    queryFn: resolver.query(insightsUrl),
  });

  function updateHistorySearch({ ...props }) {
    const queryStr = `?${queryString.stringify({ ...props }, queryStringOptions)}`;
    history.push({ search: queryStr });
    return;
  }

  /** Retrieve Workflows */
  const getWorkflowsUrl = serviceUrl.team.workflow.getWorkflows({ team: team?.name });
  const {
    data: workflowsData,
    isLoading: workflowsIsLoading,
    isError: workflowsIsError,
  } = useQuery<PaginatedWorkflowResponse, string>({
    queryKey: getWorkflowsUrl,
    queryFn: resolver.query(getWorkflowsUrl),
  });

  if (insightsQuery.error || workflowsIsError) {
    return (
      <InsightsContainer team={team}>
        <Selects workflowsData={workflowsData?.content} updateHistorySearch={updateHistorySearch} />
        <ErrorDragon />
      </InsightsContainer>
    );
  }

  if (insightsQuery.isLoading || workflowsIsLoading) {
    return (
      <InsightsContainer team={team}>
        <Selects workflowsData={workflowsData?.content} updateHistorySearch={updateHistorySearch} />
        <div className={styles.cardPlaceholderContainer}>
          <SkeletonPlaceholder className={styles.cardPlaceholder} />
          <SkeletonPlaceholder className={styles.cardPlaceholder} />
          <SkeletonPlaceholder className={cx(styles.cardPlaceholder, styles.wide)} />
        </div>
        <SkeletonPlaceholder className={styles.graphPlaceholder} />
        <SkeletonPlaceholder className={styles.graphPlaceholder} />
      </InsightsContainer>
    );
  }

  if (insightsQuery.data) {
    return (
      <InsightsContainer team={team}>
        <Selects workflowsData={workflowsData?.content} updateHistorySearch={updateHistorySearch} />
        <Graphs data={insightsQuery.data} statuses={statuses as RunStatus | Array<RunStatus> | null} />
      </InsightsContainer>
    );
  }

  return null;
}
interface InsightsContainerProps {
  team: FlowTeam;
  children: React.ReactNode;
}

function InsightsContainer({ team, children }: InsightsContainerProps) {
  const NavigationComponent = () => {
    return (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.home()}>Home</Link>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <p>{team.displayName}</p>
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

  function handleSelectWorkflows({ selectedItems }: MultiSelectItems<Workflow>) {
    const workflowRefs = selectedItems.length > 0 ? selectedItems.map((worflow) => worflow.name) : undefined;
    props.updateHistorySearch({
      ...queryString.parse(location.search, queryStringOptions),
      workflows: workflowRefs,
      page: 0,
    });
    return;
  }

  function handleSelectStatuses({ selectedItems }: MultiSelectItems) {
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

  return (
    <div className={styles.dataFilters}>
      <FilterableMultiSelect
        id="insights-workflows-select"
        label="Choose workflow(s)"
        placeholder="Choose workflow(s)"
        invalid={false}
        onChange={handleSelectWorkflows}
        items={getWorkflowOptions()}
        itemToString={(workflow: Workflow) => {
          return workflow.displayName;
        }}
        initialSelectedItems={getWorkflowOptions().filter((workflow: Workflow) =>
          Boolean(selectedWorkflowRefs ? selectedWorkflowRefs.find((ref) => ref === workflow.name) : false),
        )}
        titleText="Filter by Workflow"
      />
      <FilterableMultiSelect
        id="insights-statuses-select"
        label="Choose status(es)"
        placeholder="Choose status(es)"
        invalid={false}
        onChange={handleSelectStatuses}
        items={statusOptions}
        itemToString={(item: MultiSelectItem) => (item ? item.label : "")}
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
