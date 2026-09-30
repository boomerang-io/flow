import React from "react";
import {
  ActionableNotification,
  Button,
  CodeSnippet,
  DataTable,
  DatePicker,
  DatePickerInput,
  FilterableMultiSelect,
  Pagination,
  Search,
  TableExpandHeader,
  TableExpandRow,
  TableExpandedRow,
} from "@carbon/react";
import { CheckmarkFilled, Locked, Misuse, type CarbonIconType } from "@carbon/react/icons";
import { ErrorMessage } from "@boomerang-io/carbon-addons-boomerang-react";
import cx from "classnames";
import debounce from "lodash/debounce";
import moment from "moment";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { Link, useLoaderData, useLocation, useNavigate } from "react-router-dom";
import EmptyState from "Components/EmptyState";
import StatTile from "Components/StatTile";
import { filterItemsByLabel, makeCompareItems, sortItemsBySelection } from "Utils/multiSelectHelper";
import { auditActionOptions, auditLevelOptions, auditOutcomeOptions } from "Constants/filterOptions";
import { appLink, queryStringOptions } from "Config/appConfig";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import { AuditEvent, AuditOutcome, AuditStats, PaginatedAuditResponse } from "Types";
import styles from "./Audit.module.scss";

// Route module: this file's `loader` is re-exported from app/routes/audit.tsx (the Audit tab of
// the Manage area). The URL is the whole state - filters and page live in the search params - so
// a filtered view is linkable and the browser's back button walks the filter history.

const DEFAULT_ORDER = "DESC";
const DEFAULT_PAGE = 0;
const DEFAULT_LIMIT = 25;
const DEFAULT_WINDOW_DAYS = 30;
const PAGE_SIZES = [DEFAULT_LIMIT, 50, 100];
// FilterableMultiSelect's item type requires an (optional) `disabled` field.
type FilterOption = { label: string; value: string; disabled?: boolean };

/** query-string yields a bare string for one comma-separated value and an array for several. */
const asArray = (value: unknown): Array<string> =>
  Array.isArray(value) ? (value as Array<string>) : typeof value === "string" && value ? [value] : [];

const FILTER_KEYS = ["actor", "action", "outcome", "level", "resourceType", "workspaceId", "fromDate", "toDate"];

/*
 * Computed per call, not hoisted to module constants: this module is imported ONCE into a
 * long-lived Node server (ssr:true), so a module-level `moment()` would freeze the default window
 * at process boot and every later request would reuse it. Features/Activity/Activity.tsx
 * documents the same hazard.
 */
const defaultMaxDate = () => moment().format("MM/DD/YYYY");
const defaultFromDate = () => moment().subtract(DEFAULT_WINDOW_DAYS, "days").startOf("day").valueOf();

type LoaderData = {
  events: PaginatedAuditResponse | null;
  stats: AuditStats | null;
  errorLoading: boolean;
};

/**
 * Server loader (ssr:true). The screen's `fromDate`/`toDate` are epoch milliseconds, matching
 * every other filtered list in the app; the API takes ISO-8601 instants, so they are converted
 * here. `from` is always sent - an unbounded listing would count and scan the whole retention
 * window rather than riding the time index.
 */
export async function loader({ request }: { request: Request }): Promise<LoaderData> {
  const parsedQuery = queryString.parse(new URL(request.url).search, queryStringOptions);
  const {
    order = DEFAULT_ORDER,
    page = DEFAULT_PAGE,
    limit = DEFAULT_LIMIT,
    actor,
    action,
    outcome,
    level,
    resourceType,
    workspaceId,
    fromDate = defaultFromDate(),
    toDate,
  } = parsedQuery;

  const filters = {
    actor,
    action,
    outcome,
    level,
    resourceType,
    workspaceId,
    from: moment(Number(fromDate)).toISOString(),
    to: toDate ? moment(Number(toDate)).toISOString() : undefined,
  };
  const eventsQuery = queryString.stringify({ ...filters, order, page, limit }, queryStringOptions);
  const statsQuery = queryString.stringify(filters, queryStringOptions);

  const api = serverFetch(request);
  const [eventsResult, statsResult] = await Promise.allSettled([
    api.get(serviceUrl.getAuditEvents({ query: eventsQuery })),
    api.get(serviceUrl.getAuditStats({ query: statsQuery })),
  ]);

  return {
    events: eventsResult.status === "fulfilled" ? eventsResult.value.data : null,
    stats: statsResult.status === "fulfilled" ? statsResult.value.data : null,
    errorLoading: eventsResult.status === "rejected",
  };
}

const HEADERS = [
  { header: "Time", key: "time", sortable: true },
  { header: "Actor", key: "actor", sortable: false },
  { header: "Action", key: "action", sortable: false },
  { header: "Resource", key: "resource", sortable: false },
  { header: "Outcome", key: "outcome", sortable: false },
  { header: "Level", key: "level", sortable: false },
];

/** The outcome as an icon plus coloured text, the way every list in Flow shows a status. */
const OUTCOME: Record<AuditOutcome, { Icon: CarbonIconType; label: string; className: string }> = {
  [AuditOutcome.Success]: { Icon: CheckmarkFilled, label: "Success", className: styles.outcomeSuccess },
  [AuditOutcome.Failed]: { Icon: Misuse, label: "Failed", className: styles.outcomeFailed },
  [AuditOutcome.Denied]: { Icon: Locked, label: "Denied", className: styles.outcomeDenied },
};

/** "TOKEN_CREATE" reads "Token create"; the filter options carry the nicer label when one exists. */
function humanise(value: string | undefined, options: Array<FilterOption>): string {
  if (!value) {
    return "---";
  }
  const option = options.find((candidate) => candidate.value === value);
  if (option) {
    return option.label;
  }
  const words = value.toLowerCase().replace(/_/g, " ");
  return words.charAt(0).toUpperCase() + words.slice(1);
}

export default function Audit() {
  const navigate = useNavigate();
  const location = useLocation();
  const { events, stats, errorLoading } = useLoaderData() as LoaderData;

  const parsedQuery = queryString.parse(location.search, queryStringOptions);
  const order = typeof parsedQuery.order === "string" ? parsedQuery.order : DEFAULT_ORDER;
  const hasFilters = FILTER_KEYS.some((key) => Boolean(parsedQuery[key]));

  function updateSearch(next: Record<string, unknown>) {
    navigate({ search: `?${queryString.stringify(next, queryStringOptions)}` });
  }

  function setFilter(key: string, value: Array<string> | string | undefined) {
    const next = Array.isArray(value) ? (value.length > 0 ? value : undefined) : value || undefined;
    updateSearch({ ...queryString.parse(location.search, queryStringOptions), [key]: next, page: 0 });
  }

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const debouncedActorSearch = React.useCallback(
    debounce((value: string) => setFilter("actor", value), 300),
    [location.search],
  );

  function handleSelectDate(dates: Array<Date>) {
    const [from, to] = dates;
    if (!to) {
      return;
    }
    updateSearch({
      ...queryString.parse(location.search, queryStringOptions),
      fromDate: moment(from).startOf("day").valueOf(),
      toDate: moment(to).endOf("day").valueOf(),
      page: 0,
    });
  }

  function handlePaginationChange({ page, pageSize }: { page: number; pageSize: number }) {
    updateSearch({
      ...queryString.parse(location.search, queryStringOptions),
      page: page - 1, // Carbon's Pagination is 1-based, the API is 0-based
      limit: pageSize,
    });
  }

  function handleSort() {
    updateSearch({
      ...queryString.parse(location.search, queryStringOptions),
      order: order === "ASC" ? "DESC" : "ASC",
    });
  }

  const captureDisabled = Boolean(stats) && !stats!.captureEnabled;

  return (
    <div className={styles.content}>
      <Helmet>
        <title>Audit</title>
      </Helmet>
      {errorLoading ? (
        <ErrorMessage />
      ) : (
        <>
          {captureDisabled && (
            <ActionableNotification
              lowContrast
              hideCloseButton
              inline
              kind="warning"
              title="Audit capture is off"
              subtitle="No new events are being recorded."
              actionButtonLabel="Go to Settings"
              onActionButtonClick={() => navigate(appLink.settings())}
            />
          )}
          {stats && <StatTiles stats={stats} />}
          <Filters
            parsedQuery={parsedQuery}
            hasFilters={hasFilters}
            setFilter={setFilter}
            onSearchActor={debouncedActorSearch}
            onSelectDate={handleSelectDate}
            onClear={() => navigate({ search: "" })}
          />
          <AuditTable
            events={events}
            captureEnabled={!captureDisabled}
            hasFilters={hasFilters}
            order={order}
            onSort={handleSort}
            onPaginationChange={handlePaginationChange}
          />
        </>
      )}
    </div>
  );
}

function StatTiles({ stats }: { stats: AuditStats }) {
  const since = moment(stats.from).format("MMM D, YYYY");
  const until = stats.to ? moment(stats.to).format("MMM D, YYYY") : "now";
  return (
    <>
      <div className={styles.stats} data-testid="audit-stats">
        <StatTile label="Events" value={stats.total} detail={`${since} to ${until}`} testId="audit-stat-events" />
        <StatTile label="Success" value={stats.outcomes?.[AuditOutcome.Success] ?? 0} />
        <StatTile label="Failed" value={stats.outcomes?.[AuditOutcome.Failed] ?? 0} />
        <StatTile label="Denied" value={stats.outcomes?.[AuditOutcome.Denied] ?? 0} detail="Attempts a permission refused" />
      </div>
      <p className={styles.capture}>
        {stats.captureEnabled ? `Capturing ${humanise(stats.level, auditLevelOptions).toLowerCase()}-level events` : "Capture is off"},
        kept {stats.retentionDays} days. <Link to={appLink.settings()}>Change in Settings</Link>
      </p>
    </>
  );
}

interface FiltersProps {
  parsedQuery: Record<string, unknown>;
  hasFilters: boolean;
  setFilter: (key: string, value: Array<string> | string | undefined) => void;
  onSearchActor: (value: string) => void;
  onSelectDate: (dates: Array<Date>) => void;
  onClear: () => void;
}

function Filters({ parsedQuery, hasFilters, setFilter, onSearchActor, onSelectDate, onClear }: FiltersProps) {
  const selected = (key: string, options: FilterOption[]) =>
    options.filter((option) => asArray(parsedQuery[key]).includes(option.value));

  return (
    <div className={styles.dataFilters}>
      <div className={styles.searchField}>
        <span className={styles.searchLabel} id="audit-actor-search-label">
          Filter by actor
        </span>
        <Search
          id="audit-actor-search"
          labelText="Filter by actor"
          placeholder="Filter by actor name or id"
          defaultValue={typeof parsedQuery.actor === "string" ? parsedQuery.actor : ""}
          onChange={(e: { target: HTMLInputElement }) => onSearchActor(e.target.value)}
        />
      </div>
      <AuditMultiSelect
        id="audit-outcome-filter"
        title="Filter by outcome"
        options={auditOutcomeOptions}
        selected={selected("outcome", auditOutcomeOptions)}
        onChange={(values) => setFilter("outcome", values)}
      />
      <AuditMultiSelect
        id="audit-action-filter"
        title="Filter by action"
        options={auditActionOptions}
        selected={selected("action", auditActionOptions)}
        onChange={(values) => setFilter("action", values)}
      />
      <AuditMultiSelect
        id="audit-level-filter"
        title="Filter by level"
        options={auditLevelOptions}
        selected={selected("level", auditLevelOptions)}
        onChange={(values) => setFilter("level", values)}
      />
      <div className={styles.timeFilters}>
        {hasFilters && (
          <Button kind="ghost" size="md" onClick={onClear}>
            Clear filters
          </Button>
        )}
        <DatePicker datePickerType="range" maxDate={defaultMaxDate()} onChange={onSelectDate}>
          <DatePickerInput id="audit-date-picker-start" labelText="Start date" placeholder="mm/dd/yyyy" />
          <DatePickerInput id="audit-date-picker-end" labelText="End date" placeholder="mm/dd/yyyy" />
        </DatePicker>
      </div>
    </div>
  );
}

function AuditMultiSelect({
  id,
  title,
  options,
  selected,
  onChange,
}: {
  id: string;
  title: string;
  options: FilterOption[];
  selected: FilterOption[];
  onChange: (values: Array<string>) => void;
}) {
  const itemToString = (item: FilterOption | null) => item?.label ?? "";
  return (
    <FilterableMultiSelect<FilterOption>
      id={id}
      titleText={title}
      placeholder={title}
      invalid={false}
      items={options}
      itemToString={itemToString}
      filterItems={filterItemsByLabel}
      compareItems={makeCompareItems(itemToString)}
      sortItems={sortItemsBySelection}
      initialSelectedItems={selected}
      onChange={({ selectedItems }) => onChange((selectedItems ?? []).map((item) => item.value))}
    />
  );
}

interface AuditTableProps {
  events: PaginatedAuditResponse | null;
  captureEnabled: boolean;
  hasFilters: boolean;
  order: string;
  onSort: () => void;
  onPaginationChange: (pagination: { page: number; pageSize: number }) => void;
}

function AuditTable({ events, captureEnabled, hasFilters, order, onSort, onPaginationChange }: AuditTableProps) {
  const { TableContainer, Table, TableHead, TableRow, TableBody, TableCell, TableHeader } = DataTable;
  const content = events?.content ?? [];

  if (content.length === 0) {
    if (hasFilters) {
      return (
        <EmptyState title="No events match the current filters" message="Widen the date range or clear a filter." />
      );
    }
    if (!captureEnabled) {
      return (
        <EmptyState
          title="Auditing is not enabled"
          message="No events have been recorded. Turn audit capture on in Settings."
        />
      );
    }
    return (
      <EmptyState title="No audit events recorded yet" message="Audited actions will appear here as they happen." />
    );
  }

  return (
    <div className={styles.panel}>
      <DataTable
        rows={content.map((event) => ({ ...event, id: event.id }))}
        headers={HEADERS}
        render={({ rows, headers, getHeaderProps, getRowProps }: any) => (
          <TableContainer>
            <Table isSortable>
              <TableHead>
                <TableRow>
                  <TableExpandHeader aria-label="Expand row" />
                  {headers.map((header: { header: string; key: string; sortable: boolean }) => (
                    <TableHeader
                      id={header.key}
                      {...getHeaderProps({ header, isSortable: header.sortable, onClick: onSort })}
                      isSortHeader={header.key === "time"}
                      sortDirection={order}
                      key={header.key}
                    >
                      {header.header}
                    </TableHeader>
                  ))}
                </TableRow>
              </TableHead>
              <TableBody>
                {rows.map((row: { id: string }) => {
                  const event = content.find((candidate) => candidate.id === row.id) as AuditEvent;
                  return (
                    <React.Fragment key={row.id}>
                      <TableExpandRow {...getRowProps({ row })}>
                        <TableCell>
                          <time dateTime={event.time}>{moment(event.time).format("MMM D, YYYY h:mm:ss a")}</time>
                          <span className={styles.muted}>{moment(event.time).fromNow()}</span>
                        </TableCell>
                        <TableCell>
                          <span>{event.actorName ?? event.actorId ?? "---"}</span>
                          {event.actorId && event.actorId !== event.actorName && (
                            <span className={styles.muted}>{event.actorId}</span>
                          )}
                        </TableCell>
                        <TableCell>{humanise(event.action, auditActionOptions)}</TableCell>
                        <TableCell>
                          <span>{event.resourceName ?? event.resourceId ?? "---"}</span>
                          {event.resourceType && <span className={styles.muted}>{event.resourceType}</span>}
                        </TableCell>
                        <TableCell>
                          <Outcome outcome={event.outcome} />
                        </TableCell>
                        <TableCell>{humanise(event.level, auditLevelOptions)}</TableCell>
                      </TableExpandRow>
                      <TableExpandedRow colSpan={headers.length + 1}>
                        <CodeSnippet type="multi" hideCopyButton wrapText>
                          {JSON.stringify(event.payload ?? {}, null, 2)}
                        </CodeSnippet>
                      </TableExpandedRow>
                    </React.Fragment>
                  );
                })}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      />
      <Pagination
        onChange={onPaginationChange}
        page={(events?.number ?? 0) + 1}
        pageSize={events?.size ?? DEFAULT_LIMIT}
        pageSizes={PAGE_SIZES}
        totalItems={events?.totalElements ?? 0}
      />
    </div>
  );
}

function Outcome({ outcome }: { outcome?: AuditOutcome }) {
  const shown = outcome ? OUTCOME[outcome] : undefined;
  if (!shown) {
    return <span>{outcome ?? "---"}</span>;
  }
  const { Icon, label, className } = shown;
  return (
    <span className={cx(styles.outcome, className)}>
      <Icon size={16} aria-hidden="true" />
      {label}
    </span>
  );
}
