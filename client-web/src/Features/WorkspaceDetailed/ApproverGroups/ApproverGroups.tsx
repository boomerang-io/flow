import React from "react";
import { Helmet } from "react-helmet";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import sortBy from "lodash/sortBy";
import { Button, DataTable, InlineNotification } from "@carbon/react";
import { ConfirmModal, Error404, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import CreateEditGroupModal from "./CreateEditGroupModal";
import moment from "moment";
import { formatErrorMessage } from "@boomerang-io/utils";
import { sortKeyDirection } from "Utils/arrayHelper";
import { ApproverGroup, Approver } from "Types";
import { TrashCan } from "@carbon/react/icons";
import { useFetcher } from "react-router-dom";
import { useWorkspaceDetailedContext } from "../WorkspaceDetailed";
import styles from "./approverGroups.module.scss";

// Route module for the Approver Groups tab (app/routes/manageWorkspaceApproverGroups.tsx). Both
// writes on this tab - the delete below and the create/update in CreateEditGroupModalContent -
// post to this one action, keyed by an `intent` form field; a fetcher with no explicit action
// path resolves to the nearest matched route, which is this tab. Settling the fetcher
// revalidates the parent layout route's loader, which is where the group list comes from.
//
// Intent names are approver-group-scoped, not bare verbs: every tab under the Manage Workspace
// layout route submits to the same matched route tree, and that route's shouldRevalidate
// (../WorkspaceDetailed) keys off the Settings tab's workspace-level intents. A bare "delete"
// here collided with the workspace delete and had its revalidation suppressed, so a deleted
// group stayed on screen.
export const ApproverGroupIntent = {
  Delete: "deleteApproverGroup",
  Save: "saveApproverGroup",
} as const;

export type ApproverGroupsActionResult = {
  ok: boolean;
  intent: (typeof ApproverGroupIntent)[keyof typeof ApproverGroupIntent];
  /** Present for "save": distinguishes the created/updated toast. */
  isEdit?: boolean;
  /** The name shown in the resulting toast. */
  name: string;
  errorMessage?: { title: string; message: string };
};

export async function action({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<ApproverGroupsActionResult> {
  const workspace = String(params.workspace);
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === ApproverGroupIntent.Delete) {
    const groupId = String(formData.get("groupId"));
    const name = String(formData.get("name"));
    try {
      // DELETE with a request body, matching the previous resolver.deleteApproverGroup.
      await serverFetch(request).delete(serviceUrl.resourceApproverGroups({ workspace }), { data: [groupId] });
      return { ok: true, intent: ApproverGroupIntent.Delete, name };
    } catch (error) {
      return {
        ok: false,
        intent: ApproverGroupIntent.Delete,
        name,
        errorMessage: formatErrorMessage({ error, defaultMessage: "Delete Approver Group Failed" }),
      };
    }
  }

  const isEdit = formData.get("isEdit") === "true";
  const groupId = formData.get("groupId") ? String(formData.get("groupId")) : null;
  const approverGroup = {
    name: String(formData.get("name")),
    groupId,
    approvers: JSON.parse(String(formData.get("approvers"))),
  };
  try {
    const response = await serverFetch(request).patch(serviceUrl.resourceWorkspace({ workspace }), {
      approverGroups: [approverGroup],
    });
    // Pre-existing quirk preserved: patchWorkspace returns the *workspace*, so the toast has
    // always echoed the workspace name here rather than the group name.
    return { ok: true, intent: ApproverGroupIntent.Save, isEdit, name: response.data.name };
  } catch (error) {
    return {
      ok: false,
      intent: ApproverGroupIntent.Save,
      isEdit,
      name: approverGroup.name,
      errorMessage: formatErrorMessage({
        error,
        defaultMessage: `${!isEdit ? "Create" : "Update"} Approver Group Failed`,
      }),
    };
  }
}

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

// Approver Groups tab of /:workspace/manage (app/routes/manageWorkspaceApproverGroups.tsx). The
// workspace and `canEdit` arrive from the parent layout route's <Outlet context> rather than as
// props, and the groups themselves are read off that loader-supplied workspace record.
function ApproverGroups() {
  const { workspace, canEdit } = useWorkspaceDetailedContext();
  const [sortKey, setSortKey] = React.useState("name");
  const [sortDirection, setSortDirection] = React.useState("ASC");
  const approverGroups = workspace?.approverGroups ?? [];
  /** Delete Workspace Approver Group - see this file's `action`. */
  const fetcher = useFetcher<ApproverGroupsActionResult>();

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== ApproverGroupIntent.Delete) {
      return;
    }
    const { ok, name, errorMessage } = fetcher.data;
    notify(
      ok ? (
        <ToastNotification
          kind="success"
          title={"Approver Group Deleted"}
          subtitle={`Request to delete ${name} succeeded`}
          data-testid="delete-approver-group-notification"
        />
      ) : (
        <ToastNotification
          kind="error"
          title={errorMessage?.title ?? "Something's Wrong"}
          subtitle={errorMessage?.message}
          data-testid="delete-approver-group-notification"
        />
      ),
    );
  }, [fetcher.state, fetcher.data]);

  const deleteApproverGroup = (approverGroup: ApproverGroup) => {
    fetcher.submit(
      { intent: ApproverGroupIntent.Delete, groupId: approverGroup.id ?? "", name: approverGroup.name },
      { method: "post" },
    );
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
              workspace={workspace}
            />
            <ConfirmModal
              modalTrigger={({ openModal }: any) => (
                <Button
                  className={styles.deleteButton}
                  onClick={openModal}
                  kind="danger--ghost"
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
    <section aria-label={`${workspace.displayName} Workspace Approvers`} className={styles.container}>
      <Helmet>
        <title>Workspace Approvers</title>
      </Helmet>
      {!canEdit ? (
        <section className={styles.notificationsContainer}>
          <InlineNotification
            lowContrast
            hideCloseButton={true}
            kind="info"
            title="Read-only"
            subtitle="The workspace may be inactive or you don’t have the necessary permissions. You can still see what’s going on behind the
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
          <CreateEditGroupModal approverGroups={approverGroups} workspace={workspace} />
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
                    <TableExpandHeader aria-label="Expand row" />
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
                          key={`no-workspace-config-table-key-${key}`}
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
