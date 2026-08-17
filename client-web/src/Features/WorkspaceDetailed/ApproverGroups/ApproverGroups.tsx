import React from "react";
import { Helmet } from "react-helmet";
import { resolver } from "Config/servicesConfig";
import { useMutation } from "react-query";
import sortBy from "lodash/sortBy";
import { Button, DataTable, InlineNotification } from "@carbon/react";
import { ConfirmModal, Error404, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import CreateEditGroupModal from "./CreateEditGroupModal";
import moment from "moment";
import { formatErrorMessage } from "@boomerang-io/utils";
import { sortKeyDirection } from "Utils/arrayHelper";
import { FlowTeam, ApproverGroup, Approver } from "Types";
import { TrashCan } from "@carbon/react/icons";
import styles from "./approverGroups.module.scss";

const HEADERS = [
  {
    header: "Name",
    key: "name",
    sortable: true,
  },
  {
    header: "Date Created",
    key: "creationDate",
    sortable: true,
  },
  {
    header: "# of users",
    key: "approvers",
    sortable: true,
  },
  {
    header: "",
    key: "actions",
    sortable: false,
  },
];

function ApproverGroups({
  team,
  canEdit,
  teamDetailsUrl,
}: {
  team: FlowTeam;
  canEdit: boolean;
  teamDetailsUrl: string;
}) {
  const [sortKey, setSortKey] = React.useState("name");
  const [sortDirection, setSortDirection] = React.useState("ASC");
  const approverGroups = team?.approverGroups ?? [];
  /** Delete Team Approver Group */
  const deleteApproverGroupMutation = useMutation(resolver.deleteApproverGroup);

  const deleteApproverGroup = async (approverGroup: ApproverGroup) => {
    try {
      await deleteApproverGroupMutation.mutateAsync({ team: team?.name, groupId: approverGroup.id });
      //TODO - replace with invalidate Team
      // queryClient.invalidateQueries(serviceUrl.resourceApproverGroups({ teamId: activeTeam?.id, groupId: undefined })),
      notify(
        <ToastNotification
          kind="success"
          title={"Approver Group Deleted"}
          subtitle={`Request to delete ${approverGroup.name} succeeded`}
          data-testid="delete-approver-group-notification"
        />,
      );
    } catch (err) {
      const errorMessages = formatErrorMessage({ error: err, defaultMessage: "Delete Approver Group Failed" });
      notify(
        <ToastNotification
          kind="error"
          title={errorMessages.title}
          subtitle={errorMessages.message}
          data-testid="delete-approver-group-notification"
        />,
      );
    }
  };

  const renderCell = (groupId: string, cellIndex: number, value: any) => {
    const approverGroup: ApproverGroup = approverGroups.find((group) => group.id === groupId) ?? {
      name: "",
      id: "",
      approvers: [],
      creationDate: "",
    };
    const column = HEADERS[cellIndex];
    switch (column.key) {
      case "name":
        return <p className={styles.text}>{value}</p>;
      case "creationDate":
        return <time>{moment(value).format("YYYY-MM-DD")}</time>;
      case "approvers":
        return <p className={styles.text}>{value?.length ?? "0"}</p>;
      case "actions":
        return canEdit ? (
          <div className={styles.tableActions}>
            <CreateEditGroupModal
              isEdit
              approverGroup={approverGroup}
              approverGroups={approverGroups}
              team={team}
              teamDetailsUrl={teamDetailsUrl}
            />
            <ConfirmModal
              modalTrigger={({ openModal }: any) => (
                <Button
                  className={styles.deleteButton}
                  onClick={openModal}
                  kind="danger-ghost"
                  renderIcon={TrashCan}
                  size="sm"
                  iconDescription="Delete approver group"
                  data-testid="delete-approver-group"
                />
              )}
              affirmativeAction={() => deleteApproverGroup(approverGroup)}
              negativeText={`Cancel`}
              affirmativeText={`Delete`}
              affirmativeButtonProps={{ kind: "danger" }}
              title={`Delete group`}
            >
              <div style={{ width: "calc(100% - 6.5rem)" }}>
                <p>
                  If this group is set as an approver for any gate, it will be removed, and the group members will no
                  longer be approvers for the gate.
                </p>
                <p style={{ marginTop: "2rem" }}>{`Are you sure you’d like to delete ${approverGroup.name}?`}</p>
              </div>
            </ConfirmModal>
          </div>
        ) : null;
      default:
        return value || "---";
    }
  };

  const renderSubRow = (row: any) => {
    const rowData: any = approverGroups.find((group) => group.id === row.id);
    return (
      <div className={styles.expanded}>
        {rowData?.approvers &&
          sortBy(rowData.approvers, ["userName"]).map((approver: Approver) => (
            <div className={styles.expandedSection}>
              <p className={styles.expandedUsername}>{approver.name}</p>
              <p className={styles.expandedEmail}>{approver.email}</p>
            </div>
          ))}
      </div>
    );
  };

  const handleSort = (e: any, { sortHeaderKey }: { sortHeaderKey: string }) => {
    const order = sortDirection === "ASC" ? "DESC" : "ASC";
    setSortKey(sortHeaderKey);
    setSortDirection(order);
  };

  const {
    TableContainer,
    Table,
    TableHead,
    TableRow,
    TableBody,
    TableCell,
    TableHeader,
    TableExpandHeader,
    TableExpandRow,
    TableExpandedRow,
  } = DataTable;
  const totalItems = approverGroups?.length;

  return (
    <section aria-label={`${team.displayName} Team Approvers`} className={styles.container}>
      <Helmet>
        <title>Team Approvers</title>
      </Helmet>
      {!canEdit ? (
        <section className={styles.notificationsContainer}>
          <InlineNotification
            lowContrast
            hideCloseButton={true}
            kind="info"
            title="Read-only"
            subtitle="The team may be inactive or you don’t have the necessary permissions. You can still see what’s going on behind the
            scenes."
          />
        </section>
      ) : null}
      <section className={styles.actionsContainer}>
        <div className={styles.leftActions}>
          <p className={styles.featureDescription}>
            Create groups of users to be able to set the entire group as an approver in an Action.
          </p>
          <p className={styles.memberCountText}>
            Showing {approverGroups?.length ?? 0} approver group{approverGroups?.length !== 1 ? "s" : ""}
          </p>
        </div>
        {canEdit && (
          <CreateEditGroupModal approverGroups={approverGroups} team={team} teamDetailsUrl={teamDetailsUrl} />
        )}
      </section>
      {totalItems > 0 ? (
        <DataTable
          rows={sortKeyDirection({
            array: approverGroups.map((group) => ({ ...group, id: group.id })),
            sortKey,
            sortDirection,
          })}
          sortRow={(rows: any) => rows}
          headers={HEADERS}
          render={({ rows, headers, getHeaderProps, getRowProps }: any) => (
            <TableContainer>
              <Table isSortable>
                <TableHead>
                  <TableRow className={styles.tableHeadRow}>
                    <TableExpandHeader />
                    {headers.map((header: any, key: any) => (
                      <TableHeader
                        id={header.key}
                        key={`mode-table-key-${key}`}
                        {...getHeaderProps({
                          header,
                          className: `${styles.tableHeadHeader} ${styles[header.key]}`,
                          onClick: handleSort,
                          isSortable: header.sortable,
                        })}
                      >
                        {header.header}
                      </TableHeader>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody className={styles.tableBody}>
                  {rows.map((row: any) => {
                    return (
                      <React.Fragment key={row.id}>
                        <TableExpandRow {...getRowProps({ row })} className={styles.tableRow}>
                          {row.cells.map((cell: any, cellIndex: any) => (
                            <TableCell key={cell.id} style={{ padding: "0" }}>
                              <div className={styles.tableCell}>{renderCell(row.id, cellIndex, cell.value)}</div>
                            </TableCell>
                          ))}
                        </TableExpandRow>
                        <TableExpandedRow colSpan={headers.length + 1}>{renderSubRow(row)}</TableExpandedRow>
                      </React.Fragment>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        />
      ) : (
        <>
          <DataTable
            rows={approverGroups}
            headers={HEADERS}
            render={({ headers }: any) => (
              <TableContainer>
                <Table>
                  <TableHead>
                    <TableRow className={styles.tableHeadRow}>
                      {headers.map((header: any, key: any) => (
                        <TableHeader
                          key={`no-team-config-table-key-${key}`}
                          className={`${styles.tableHeadHeader} ${styles[header.key]}`}
                        >
                          <span className="bx--table-header-label">{header.header}</span>
                        </TableHeader>
                      ))}
                    </TableRow>
                  </TableHead>
                </Table>
              </TableContainer>
            )}
          />
          <Error404 header={null} title="No approver groups" theme="boomerang" />
        </>
      )}
    </section>
  );
}

export default ApproverGroups;
