import React, { Component } from "react";
import { DataTable, Pagination, Search } from "@carbon/react";
import { matchSorter } from "match-sorter";
import moment from "moment";
import PropTypes from "prop-types";
import EmptyState from "Components/EmptyState";
import { ChangeLog } from "Types";
import styles from "./changeLogTable.module.scss";

const DEFAULT_PAGE_SIZE = 10;
const PAGE_SIZES = [DEFAULT_PAGE_SIZE, 25, 50];

interface ChangeLogTableProps {
  changeLog: ChangeLog;
}

class ChangeLogTable extends Component<ChangeLogTableProps> {
  static propTypes = {
    changeLog: PropTypes.array,
  };

  state = {
    page: 1,
    pageSize: DEFAULT_PAGE_SIZE,
    changeLog: this.props.changeLog ? this.props.changeLog.map((log) => ({ ...log, id: log.version })) : [],
    sort: {
      key: "version",
      sortDirection: "DESC",
    },
  };

  headers = [
    {
      header: "Version",
      key: "version",
    },
    {
      header: "User",
      key: "userName",
    },
    {
      header: "Reason",
      key: "reason",
    },
    {
      header: "Date",
      key: "date",
    },
  ];

  /* Standard table configuration after search or service call */
  resetTableWithNewLogs = (changeLog: ChangeLog) => {
    const { page, pageSize } = this.state;
    const newPage = page !== 1 && changeLog.length < pageSize * (page - 1) + 1 ? page - 1 : page;
    this.setState({ page: newPage, changeLog });
  };

  handleSearchChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const searchQuery = e.target.value;
    const { changeLog } = this.props;
    const changeLogList = changeLog.length !== 0 ? changeLog.map((log) => ({ ...log, id: log.version })) : [];

    const newLogs = searchQuery
      ? matchSorter(changeLogList, searchQuery, { keys: ["version", "userName", "reason"] })
      : changeLogList;

    this.resetTableWithNewLogs(newLogs);
  };

  handlePaginationChange = ({ page, pageSize }: { page: number; pageSize: number }) => {
    this.setState({ page, pageSize });
  };

  renderCell = (cellIndex: number, value: any): JSX.Element => {
    const column = this.headers[cellIndex];

    switch (column.header) {
      case "Date":
        return <p className={styles.tableText}>{moment(value).format("YYYY-MM-DD hh:mm A")}</p>;
      default:
        return <p className={styles.tableText}>{value || "---"}</p>;
    }
  };

  render() {
    const { page, pageSize, changeLog } = this.state;
    const { TableContainer, Table, TableHead, TableRow, TableBody, TableCell, TableHeader } = DataTable;

    const totalItems = changeLog.length;

    return (
      <div className={styles.tableContainer}>
        <Search
          className={styles.search}
          data-testid="change-log-search"
          id="change-log-table-search"
          labelText="Search"
          onChange={this.handleSearchChange}
          placeholder="Search"
        />
        {totalItems > 0 ? (
          <>
            <DataTable
              sortable
              rows={changeLog}
              headers={this.headers}
              render={({ rows, headers, getHeaderProps }: { rows: any; headers: any; getHeaderProps: any }) => (
                <TableContainer>
                  <Table isSortable>
                    <TableHead>
                      <TableRow>
                        {headers.map((header: any) => (
                          <TableHeader
                            id={header.key}
                            {...getHeaderProps({
                              header,
                              isSortable: true,
                            })}
                          >
                            {header.header}
                          </TableHeader>
                        ))}
                      </TableRow>
                    </TableHead>
                    <TableBody className={styles.tableBody}>
                      {rows.map((row: any) => (
                        <TableRow key={row.id} data-testid="change-log-table-row">
                          {row.cells.map((cell: any, cellIndex: number) => (
                            <TableCell key={cell.id}>
                              <div className={styles.tableCell}>{this.renderCell(cellIndex, cell.value)}</div>
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
              onChange={this.handlePaginationChange}
              page={page}
              pageSize={pageSize}
              pageSizes={PAGE_SIZES}
              totalItems={totalItems}
            />
          </>
        ) : (
          <EmptyState message={null} title="No change logs found" />
        )}
      </div>
    );
  }
}

export default ChangeLogTable;
