import React from "react";
import { Button, DataTableSkeleton, DataTable, Pagination } from "@carbon/react";
import { CheckmarkOutline, CloseOutline, Help, Warning } from "@carbon/react/icons";
import { TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import cx from "classnames";
import queryString from "query-string";
import { Link } from "react-router-dom";
import EmptyState from "Components/EmptyState";
import { useAppContext, useTeamContext } from "Hooks";
import dateHelper from "Utils/dateHelper";
import { appLink } from "Config/appConfig";
import { Action, ApprovalStatus } from "Types";
import styles from "./ActionsTable.module.scss";
import ApproveRejectActions from "./ApproveRejectActions";
import ManualTask from "./ManualTask";

interface ActionsTableProps {
  actionsQueryToRefetch: string;
  history: any;
  isLoading: boolean;
  location: any;
  tableData: {
    number: number;
    size: number;
    totalElements: number;
    content: any;
  };
  sort: string;
  order: string;
  updateHistorySearch: Function;
}

const PAGE_SIZES = [10, 20, 25, 50, 100];

const HeadersHeader = {
  Workflow: "Workflow",
  Task: "Task",
  Approvals: "Approvals",
  TimeSubmitted: "Time submitted",
  Status: "Status",
};

const HeadersKey = {
  Workflow: "workflowName",
  Task: "taskName",
  Approvals: "numberOfApprovals",
  TimeSubmitted: "creationDate",
  Status: "status",
  ActivityLink: "activityLink",
};

const headers = [
  {
    header: HeadersHeader.Workflow,
    key: HeadersKey.Workflow,
    sortable: true,
  },
  {
    header: HeadersHeader.Task,
    key: HeadersKey.Task,
    sortable: true,
  },
  {
    header: HeadersHeader.TimeSubmitted,
    key: HeadersKey.TimeSubmitted,
    sortable: true,
  },
  {
    header: HeadersHeader.Status,
    key: HeadersKey.Status,
    sortable: true,
  },
  {
    header: "",
    key: HeadersKey.ActivityLink,
    sortable: false,
  },
];

function ActionsTable(props: ActionsTableProps) {
  const { user } = useAppContext();
  const { team } = useTeamContext();
  const isManual = props.location.pathname.includes("/manual");
  const [selectedActions, setSelectedActions] = React.useState<string[]>([]);
  const noSelectedActions = selectedActions.length === 0;

  let headerList = [...headers];
  if (!isManual) {
    headerList.splice(4, 0, {
      header: HeadersHeader.Approvals,
      key: HeadersKey.Approvals,
      sortable: true,
    });
  }

  const handleOnClickCheckbox = (rowId: any) => {
    if (Array.isArray(rowId)) {
      // checking if all the page is already selected. If YES empty the actions list, if NOT push the values to the list.
      const isAllPageSelected = rowId.every((item) => {
        return selectedActions.findIndex((index) => index === item) > -1;
      });
      if (isAllPageSelected) {
        return setSelectedActions([]);
      }
      // removing duplicates while also pushing new data into array
      return setSelectedActions([...new Set([...selectedActions, ...rowId])]);
    }

    const actions = [...selectedActions];
    const requestToUpdateIndex = actions.findIndex((request) => request === rowId);

    if (requestToUpdateIndex > -1) {
      actions.splice(requestToUpdateIndex, 1);
    } else {
      actions.push(rowId);
    }

    setSelectedActions([...actions]);
  };

  function handlePaginationChange({ page, pageSize }: { page: number; pageSize: number }) {
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

  /**
   * Prevent opening modal when clicking the checkboxes in the table
   */
  const handleApprovalRowClick = ({ event, openModal }: { event: any; openModal: () => void }) => {
    if (!event.target.classList[0]?.includes("checkbox")) {
      openModal();
    }
  };

  if (props.isLoading) {
    return (
      <div style={{ marginTop: "1rem" }}>
        <DataTableSkeleton
          className={cx(`cds--skeleton`, `cds--data-table`, styles.tableSkeleton)}
          rowCount={5}
          columnCount={headerList.length}
          headers={headerList.map((header) => header.header)}
        />
      </div>
    );
  }

  const { number, size, totalElements, content } = props.tableData;

  const {
    TableContainer,
    Table,
    TableHead,
    TableRow,
    TableBody,
    TableCell,
    TableHeader,
    TableSelectAll,
    TableSelectRow,
  } = DataTable;

  function renderCell(headerList: any, cellIndex: number, value: any, currentAction: any) {
    const column = headerList[cellIndex];

    switch (column?.key) {
      case HeadersKey.Status:
        return (
          <p className={styles.tableTextarea} style={{ textTransform: "capitalize" }}>
            {value || "---"}
          </p>
        );
      case HeadersKey.Approvals:
        if (value >= currentAction?.approvalsRequired && currentAction?.status === ApprovalStatus.Submitted) {
          return (
            <TooltipHover
              direction="top"
              tooltipText="This gate still requires a decision by one or more required approvers in order for this component to proceed"
            >
              <div className={styles.tableCellInputApprovals}>
                <p className={styles.tableCellSmallGray}>{`${value}/${currentAction?.approvalsRequired} approvals`}</p>
                <Warning className={styles.tableCellInputRequiredIcon} />
              </div>
            </TooltipHover>
          );
        } else {
          return (
            <p className={styles.tableCellSmallGray}>{`${value}/${currentAction?.approvalsRequired} approvals`}</p>
          );
        }
      case HeadersKey.TimeSubmitted:
        return <time className={styles.tableTextarea}>{value ? dateHelper.humanizedSimpleTimeAgo(value) : "---"}</time>;
      case HeadersKey.ActivityLink:
        return (
          <Link
            to={appLink.execution({
              team: team.name,
              runId: currentAction?.workflowRunRef,
            })}
          >
            View Activity
          </Link>
        );
      default:
        return <p className={styles.tableTextarea}>{value || "---"}</p>;
    }
  }

  const getSelectedActions = (actionId?: string) => {
    return actionId
      ? content.filter((action: Action) => action.id === actionId)
      : content.filter((action: Action) =>
          selectedActions.some((selectedAction: string) => selectedAction === action.id),
        );
  };

  return (
    <section>
      <div className={styles.tableContainer}>
        {totalElements > 0 ? (
          <>
            <DataTable
              rows={content}
              headers={headerList}
              render={({ rows, headers, getHeaderProps, getSelectionProps, selectRow, selectedRows }: any) => {
                const onSuccessfulApprovalRejection = async () => {
                  setSelectedActions([]);
                };
                if (!isManual) {
                  rows.forEach((rowItem: any) => {
                    const actionIsSelectedInState =
                      selectedActions.findIndex((actionId) => actionId === rowItem.id) > -1;
                    const actionIsSelectedInTable =
                      selectedRows.findIndex((selectedRowItem: any) => selectedRowItem.id === rowItem.id) > -1;

                    if (actionIsSelectedInState !== actionIsSelectedInTable) {
                      selectRow(rowItem.id);
                    }
                  });
                }

                return (
                  <TableContainer>
                    {!isManual && (
                      <div className={styles.actions}>
                        <ApproveRejectActions
                          actions={getSelectedActions()}
                          modalTrigger={({ openModal }) => (
                            <Button
                              className={styles.actionButton}
                              disabled={noSelectedActions}
                              kind="danger--ghost"
                              iconDescription="reject-actions"
                              onClick={openModal}
                              renderIcon={CloseOutline}
                              size="md"
                            >
                              Reject selected
                            </Button>
                          )}
                          onSuccessfulApprovalRejection={onSuccessfulApprovalRejection}
                          queryToRefetch={props.actionsQueryToRefetch}
                          type="reject"
                        />
                        <ApproveRejectActions
                          actions={getSelectedActions()}
                          modalTrigger={({ openModal }) => (
                            <Button
                              className={styles.actionButton}
                              disabled={noSelectedActions}
                              kind="ghost"
                              iconDescription="approve-actions"
                              onClick={openModal}
                              renderIcon={CheckmarkOutline}
                              size="md"
                            >
                              Approve selected
                            </Button>
                          )}
                          onSuccessfulApprovalRejection={onSuccessfulApprovalRejection}
                          queryToRefetch={props.actionsQueryToRefetch}
                          type="approve"
                        />
                      </div>
                    )}
                    <Table isSortable>
                      <TableHead>
                        <TableRow>
                          {!isManual && (
                            <TableSelectAll
                              {...getSelectionProps({
                                onClick: () => {
                                  handleOnClickCheckbox(
                                    content
                                      .filter(
                                        (item: Action) =>
                                          item.status === ApprovalStatus.Submitted &&
                                          !item?.actioners?.map((user) => user.approverId).includes(user?.id),
                                      )
                                      .map((item: Action) => item.id),
                                  );
                                },
                              })}
                            />
                          )}
                          {headers.map((header: any) => (
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
                              {header.header === HeadersHeader.Approvals ? (
                                <div className={styles.tableHeaderApprovals}>
                                  {header.header}
                                  <TooltipHover
                                    direction="top"
                                    tooltipText="Number of required approvals that have been received in order for this component to proceed."
                                  >
                                    <Help className={styles.tableHeaderApprovalsIcon} />
                                  </TooltipHover>
                                </div>
                              ) : (
                                header.header
                              )}
                            </TableHeader>
                          ))}
                        </TableRow>
                      </TableHead>
                      <TableBody className={styles.tableBody}>
                        {rows.map((row: any) => {
                          const currentAction: Action = content.find((action: Action) => action.id === row.id);
                          const isAlreadyApproved =
                            (user?.id && currentAction?.actioners?.map((user) => user.approverId).includes(user.id)) ||
                            currentAction?.status !== ApprovalStatus.Submitted;
                          return isManual ? (
                            <ManualTask
                              action={currentAction}
                              modalTrigger={({ openModal }: any) => (
                                <TableRow
                                  key={row.id}
                                  className={`${styles.tableRow} ${styles[row.cells[row.cells.length - 1].value]}`}
                                  data-testid="configuration-property-table-row"
                                  onClick={openModal}
                                  onKeyDown={(event: any) => isAccessibleKeyboardEvent(event) && openModal()}
                                  tabIndex={0}
                                >
                                  {row.cells.map((cell: any, cellIndex: number) => (
                                    <TableCell key={cell.id}>
                                      <div className={styles.tableCell}>
                                        {renderCell(headerList, cellIndex, cell.value, currentAction)}
                                      </div>
                                    </TableCell>
                                  ))}
                                </TableRow>
                              )}
                              queryToRefetch={props.actionsQueryToRefetch}
                            />
                          ) : (
                            <ApproveRejectActions
                              actions={getSelectedActions(row.id)}
                              modalTrigger={({ openModal }: any) => (
                                <TableRow
                                  key={row.id}
                                  className={`${styles.tableRow} ${styles[row.cells[row.cells.length - 1].value]}`}
                                  data-testid="configuration-property-table-row"
                                  onClick={(event: any) => handleApprovalRowClick({ event, openModal })}
                                  onKeyDown={(event: any) =>
                                    isAccessibleKeyboardEvent(event) && handleApprovalRowClick({ event, openModal })
                                  }
                                  tabIndex={0}
                                >
                                  {!isManual && (
                                    <TableSelectRow
                                      {...getSelectionProps({
                                        row,
                                        onClick: (e: any) => {
                                          e.stopPropagation();
                                          handleOnClickCheckbox(row.id);
                                        },
                                      })}
                                      disabled={isAlreadyApproved}
                                    />
                                  )}
                                  {row.cells.map((cell: any, cellIndex: number) => (
                                    <TableCell key={cell.id}>
                                      <div className={styles.tableCell}>
                                        {renderCell(headerList, cellIndex, cell.value, currentAction)}
                                      </div>
                                    </TableCell>
                                  ))}
                                </TableRow>
                              )}
                              onSuccessfulApprovalRejection={onSuccessfulApprovalRejection}
                              queryToRefetch={props.actionsQueryToRefetch}
                              type="single"
                              isAlreadyApproved={isAlreadyApproved}
                            />
                          );
                        })}
                      </TableBody>
                    </Table>
                  </TableContainer>
                );
              }}
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
              render={({ headers }: any) => (
                <TableContainer>
                  <Table>
                    <TableHead>
                      <TableRow className={styles.tableHeadRow}>
                        {headers.map((header: any) => (
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
            <EmptyState message={null} title={isManual ? "No manual tasks found" : "No approvals found"} />
          </>
        )}
      </div>
    </section>
  );
}

export default ActionsTable;
