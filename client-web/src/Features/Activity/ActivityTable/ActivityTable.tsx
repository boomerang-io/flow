//@ts-nocheck
import React from "react";
import { DataTableSkeleton, DataTable, Pagination } from "@carbon/react";
import { getHumanizedDuration, isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import cx from "classnames";
import moment from "moment";
import queryString from "query-string";
import { useParams } from "react-router-dom";
import EmptyState from "Components/EmptyState";
import { ExecutionStatusCopy, executionStatusIcon } from "Constants";
import { appLink } from "Config/appConfig";
import styles from "./activityTable.module.scss";

interface ActivityTableProps {
  history: object;
  isLoading: boolean;
  location: object;
  match: object;
  tableData: {
    number: number;
    size: number;
    totalElements: number;
    content: object;
  };
  sort: string;
  order: string;
  updateHistorySearch: Function;
  workflowNameMap: {
    [key: string]: string;
  };
}

const PAGE_SIZES = [10, 20, 25, 50, 100];

const HeadersKey = {
  WorkflowName: "workflowName",
  Trigger: "trigger",
  InitiatedBy: "initiatedByUserName",
  CreationDate: "creationDate",
  Duration: "duration",
  Status: "status",
};

const headers = [
  {
    header: "Name",
    key: HeadersKey.WorkflowName,
    sortable: true,
  },
  {
    header: "Trigger",
    key: HeadersKey.Trigger,
  },
  {
    header: "Initiated By",
    key: HeadersKey.InitiatedBy,
  },
  {
    header: "Start Time",
    key: HeadersKey.CreationDate,
  },
  {
    header: "Duration",
    key: HeadersKey.Duration,
  },
  {
    header: "Status",
    key: HeadersKey.Status,
  },
];

function ActivityTable(props: ActivityTableProps) {
  const { team } = useParams<{ team: string }>();
  let headerList = headers;

  function handlePaginationChange({ page, pageSize }) {
    props.updateHistorySearch({
      ...queryString.parse(props.location.search),
      page: page - 1, // We have to decrement by one to offset the table pagination adjustment
      limit: pageSize,
    });
  }

  function handleSort(e: any, { sortHeaderKey }: { sortHeaderKey: string }) {
    let order = "ASC";
    if (props.order === "ASC") {
      order = "DESC";
    }
    props.updateHistorySearch({ ...queryString.parse(props.location.search), sort: sortHeaderKey, order });
  }

  function executionViewRedirect(activityId) {
    const activity = props.tableData.content.find((activity) => activity.id === activityId);
    props.history.push({
      pathname: appLink.execution({ team, runId: activity.id }),
      state: { fromUrl: `${props.match.url}${props.location.search}`, fromText: "Activity" },
    });
  }

  if (props.isLoading) {
    return (
      <div style={{ marginTop: "1rem" }}>
        <DataTableSkeleton
          className={cx(`cds--skeleton`, `cds--data-table`, styles.tableSkeleton)}
          rowCount={10}
          columnCount={headerList.length}
          headers={headerList.map((header) => header.header)}
        />
      </div>
    );
  }

  const { number, size, totalElements, content } = props.tableData;
  const { TableContainer, Table, TableHead, TableRow, TableBody, TableCell, TableHeader } = DataTable;

  function renderCell(headerList, cellIndex, value) {
    const column = headerList[cellIndex];

    switch (column?.key) {
      case HeadersKey.WorkflowName:
        return <p className={styles.tableTextarea}>{props.workflowNameMap[value] || value || "---"}</p>;
      case HeadersKey.Trigger:
        return (
          <p className={styles.tableTextarea} style={{ textTransform: "capitalize" }}>
            {value || "---"}
          </p>
        );
      case HeadersKey.CreationDate:
        return <time className={styles.tableTextarea}>{moment(value).format("YYYY-MM-DD hh:mm A")}</time>;
      case HeadersKey.Duration:
        return (
          <time className={styles.tableTextarea}>
            {value ? (value < 1000 ? "< 1 second" : getHumanizedDuration(parseInt(value / 1000, 10))) : "---"}
          </time>
        );
      case HeadersKey.Status:
        const Icon = executionStatusIcon[value ? value : "notstarted"];
        return (
          <div className={`${styles.status} ${styles[value]}`}>
            <Icon aria-label={value} className={styles.statusIcon} />
            <p className={styles.statusText}>{ExecutionStatusCopy[value ? value : "notstarted"]}</p>
          </div>
        );
      default:
        return <p className={styles.tableTextarea}>{value || "---"}</p>;
    }
  }

  return (
    <section>
      <div className={styles.tableContainer}>
        {totalElements > 0 ? (
          <>
            <DataTable
              rows={content}
              headers={headerList}
              render={({ rows, headers, getHeaderProps }) => (
                <TableContainer>
                  <Table isSortable>
                    <TableHead>
                      <TableRow>
                        {headers.map((header) => (
                          <TableHeader
                            id={header.key}
                            {...getHeaderProps({
                              header,
                              className: `${styles.tableHeadHeader} ${styles[header.key]}`,
                              isSortable: header.sortable,
                              onClick: handleSort,
                            })}
                            isSortHeader={props.sort === header.key}
                            sortDirection={props.order}
                          >
                            {header.header}
                          </TableHeader>
                        ))}
                      </TableRow>
                    </TableHead>
                    <TableBody className={styles.tableBody}>
                      {rows.map((row) => (
                        <TableRow
                          key={row.id}
                          className={`${styles.tableRow} ${styles[row.cells[row.cells.length - 1].value]}`}
                          data-testid="configuration-property-table-row"
                          onClick={() => executionViewRedirect(row.id)}
                          onKeyDown={(e) => isAccessibleKeyboardEvent(e) && executionViewRedirect(row.id)}
                          tabIndex={0}
                        >
                          {row.cells.map((cell, cellIndex) => (
                            <TableCell key={cell.id}>
                              <div className={styles.tableCell}>{renderCell(headerList, cellIndex, cell.value)}</div>
                            </TableCell>
                          ))}
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
            />
            <Pagination
              onChange={handlePaginationChange}
              page={number + 1} // We need to offset by one bc service is zero indexed
              pageSize={size}
              pageSizes={PAGE_SIZES}
              totalItems={totalElements}
            />
          </>
        ) : (
          <>
            <DataTable
              rows={[]}
              headers={headerList}
              render={({ headers }) => (
                <TableContainer>
                  <Table>
                    <TableHead>
                      <TableRow className={styles.tableHeadRow}>
                        {headers.map((header) => (
                          <TableHeader
                            key={header.key}
                            id={header.key}
                            className={`${styles.tableHeadHeader} ${styles[header.key]}`}
                          >
                            {header.header}
                          </TableHeader>
                        ))}
                      </TableRow>
                    </TableHead>
                  </Table>
                </TableContainer>
              )}
            />
            <EmptyState message={"Execute some workflows or try changing the filters"} title="No activity found" />
          </>
        )}
      </div>
    </section>
  );
}

export default ActivityTable;
