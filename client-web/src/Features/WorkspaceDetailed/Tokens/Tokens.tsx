import React, { useState } from "react";
import {
  DataTable,
  Pagination,
  InlineNotification,
  TableExpandHeader,
  TableExpandRow,
  TableExpandedRow,
  StructuredListWrapper,
  StructuredListHead,
  StructuredListBody,
  StructuredListRow,
  StructuredListCell,
} from "@carbon/react";
import { Help } from "@carbon/react/icons";
import { notify, ToastNotification, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { ErrorMessage } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment";
import { Helmet } from "react-helmet";
import { useFetcher } from "react-router-dom";
import { Box } from "reflexbox";
import CreateToken from "Components/CreateToken";
import DeleteToken from "Components/DeleteToken";
import EmptyState from "Components/EmptyState";
import { useTokenSectionData } from "Components/TokenSection/tokenRouteData";
import type { TokenActionResult } from "Components/TokenSection/tokenRoute";
import { arrayPagination } from "Utils/arrayHelper";
import { isActionError } from "Utils/actionResult";
import { TokenType } from "Constants";
import type { Token } from "Types";
import { useWorkspaceDetailedContext } from "../WorkspaceDetailed";
import styles from "./tokens.module.scss";

const DEFAULT_PAGE_SIZE = 10;
const PAGE_SIZES = [DEFAULT_PAGE_SIZE, 20, 50, 100];
const HEADERS = [
  {
    header: "Name",
    key: "name",
    sortable: true,
  },
  {
    header: "Status",
    key: "valid",
    sortable: true,
  },
  {
    header: "Description",
    key: "description",
    sortable: true,
  },
  {
    header: "Actor",
    key: "actorKind",
    sortable: true,
  },
  {
    header: "Created By",
    key: "createdBy",
    sortable: true,
  },
  {
    header: "Creation Date",
    key: "creationDate",
    sortable: true,
  },
  {
    header: "Expiration Date",
    key: "expirationDate",
    sortable: true,
  },
  {
    header: "Last Used",
    key: "lastUsedAt",
    sortable: true,
  },
  {
    header: "",
    key: "delete",
    sortable: false,
  },
];

// Tokens tab of /:workspace/manage (app/routes/manageWorkspaceTokens.tsx). The workspace and
// `canEdit` arrive from the parent layout route's <Outlet context> rather than as props; the
// token list itself comes from this route's own loader (Components/TokenSection/tokenRoute.ts),
// replacing the useQuery/useMutation pair - which also gives CreateToken/Form the action it now
// submits to and PermissionSelector the server-driven catalog it now reads.
function Tokens() {
  const { workspace, canEdit } = useWorkspaceDetailedContext();
  const routeData = useTokenSectionData();
  const fetcher = useFetcher<TokenActionResult>();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [sortKey, setSortKey] = useState("creationDate");
  const [sortDirection, setSortDirection] = useState("DESC");

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "delete") {
      return;
    }
    if (!isActionError(fetcher.data)) {
      notify(<ToastNotification kind="success" title="Delete Workspace Token" subtitle={`Token successfully deleted`} />);
    } else {
      notify(<ToastNotification kind="error" title="Something's Wrong" subtitle="Request to delete token failed" />);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

  // No loading branch: the loader has resolved before this renders (the DataTableSkeleton and its
  // `token-loading-skeleton` testid go with it). A missing `tokenSection` means this route was
  // rendered without the shared loader, which is the same "nothing safe to show" case as a
  // failed fetch.
  if (!routeData || routeData.errorLoading) {
    return <ErrorMessage />;
  }

  const tokens = routeData.tokens;

  const deleteToken = async (tokenId: string) => {
    fetcher.submit({ intent: "delete", tokenId }, { method: "post" });
  };

  const renderCell = (tokenItemId: string, cellIndex: number, value: string) => {
    const tokenDetails = tokens.find((token: Token) => token.id === tokenItemId);
    const column = HEADERS[cellIndex];
    switch (column.key) {
      case "valid":
        return <p className={styles.tableTextarea}>{value ? "Active" : "Inactive"}</p>;
      case "creationDate":
      case "expirationDate":
        return (
          <p className={styles.tableTextarea}>
            {value ? moment(value).utc().startOf("day").format("MMMM DD, YYYY") : "---"}
          </p>
        );
      case "lastUsedAt":
        return <p className={styles.tableTextarea}>{value ? moment(value).utc().format("MMMM DD, YYYY") : "Never"}</p>;
      case "delete":
        return tokenDetails && tokenDetails.id ? (
          <DeleteToken tokenItem={tokenDetails} deleteToken={deleteToken} />
        ) : (
          ""
        );
      default:
        return <p className={styles.tableTextarea}>{value || "---"}</p>;
    }
  };

  const handlePaginationChange = ({ page, pageSize }: { page: number; pageSize: number }) => {
    setPage(page);
    setPageSize(pageSize);
  };

  function handleSort(e: any, { sortHeaderKey }: { sortHeaderKey: string }) {
    const order = sortDirection === "ASC" ? "DESC" : "ASC";
    setSortKey(sortHeaderKey);
    setSortDirection(order);
  }

  const { TableContainer, Table, TableHead, TableRow, TableBody, TableCell, TableHeader } = DataTable;

  return (
    <section aria-label={`${workspace.displayName} Workspace Tokens`} className={styles.container}>
      <Helmet>
        <title>{`Tokens - ${workspace.displayName}`}</title>
      </Helmet>
      <>
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
        <div className={styles.buttonContainer}>
          {workspace && <CreateToken type={TokenType.Key} principal={workspace.name} disabled={!canEdit} />}
        </div>
        {tokens.length > 0 ? (
          <>
            <DataTable
              rows={arrayPagination(tokens, page, pageSize, sortKey, sortDirection)}
              // rows above are already sorted/paginated externally; keep Carbon's own comparator a no-op
              sortRow={() => 0}
              headers={HEADERS}
              render={({ rows, headers, getHeaderProps, getRowProps }: any) => (
                <TableContainer>
                  <Table isSortable>
                    <TableHead>
                      <TableRow className={styles.tableHeadRow}>
                        <TableExpandHeader aria-label="Expand row" />
                        {headers.map((header: { header: string; key: string; sortable: boolean }) => (
                          <TableHeader
                            id={header.key}
                            {...getHeaderProps({
                              header,
                              className: `${styles.tableHeadHeader} ${styles[header.key]}`,
                              isSortable: header.sortable,
                              onClick: handleSort,
                            })}
                            isSortHeader={sortKey === header.key}
                            sortDirection={sortDirection}
                          >
                            {header.header === "Permissions" ? (
                              <div className={styles.headerWithIcon}>
                                {header.header}
                                <TooltipHover
                                  direction="top"
                                  tooltipText="Read more about permissions in the documentation to understand the assigned scope and actions"
                                >
                                  <Help className={styles.headerHoverIcon} />
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
                      {rows.map((row: any) => (
                        <>
                          <TableExpandRow key={row.id} {...getRowProps({ row })}>
                            {row.cells.map((cell: any, cellIndex: number) => (
                              <TableCell key={cell.id} style={{ padding: "0" }}>
                                <div className={styles.tableCell}>{renderCell(row.id, cellIndex, cell.value)}</div>
                              </TableCell>
                            ))}
                          </TableExpandRow>
                          <TableExpandedRow colSpan={headers.length + 1}>
                            {(tokens.find((t: Token) => t.id === row.id)?.permissions ?? []).length > 0 ? (
                              <TokenPermissions
                                permissions={(tokens.find((t: Token) => t.id === row.id)?.permissions ?? []).map(
                                  (p: Token["permissions"][number], i: number) => ({ id: `${row.id}-${i}`, ...p }),
                                )}
                              />
                            ) : (
                              "Permissions detail unavailable"
                            )}
                          </TableExpandedRow>
                        </>
                      ))}
                    </TableBody>
                  </Table>
                </TableContainer>
              )}
            />
            <Pagination
              onChange={handlePaginationChange}
              page={page}
              pageSize={pageSize}
              pageSizes={PAGE_SIZES}
              totalItems={tokens.length}
            />
          </>
        ) : (
          <Box maxWidth="20rem" margin="0 auto">
            <EmptyState title="No tokens found" />
          </Box>
        )}
      </>
    </section>
  );
}

interface TokenPermissionsProps {
  // Array, not a 1-tuple - the previous annotation only typechecked because this data flowed
  // through an untyped react-query cache; now that it comes from the typed loader (Token[]), a
  // real multi-element permissions array no longer satisfies a 1-tuple. Same fix as
  // Features/GlobalTokens/GlobalTokens.tsx.
  permissions: Array<{ scope: string; principal: string; actions: string[] }>;
}

const TokenPermissions: React.FC<TokenPermissionsProps> = ({ permissions }) => {
  return (
    <StructuredListWrapper className={styles.structuredListWrapper} ariaLabel="Token list" isCondensed={true}>
      <StructuredListHead>
        <StructuredListRow head>
          <StructuredListCell head>Scope</StructuredListCell>
          <StructuredListCell head>Resource</StructuredListCell>
          <StructuredListCell head>Allowed Actions</StructuredListCell>
        </StructuredListRow>
      </StructuredListHead>
      <StructuredListBody>
        {permissions.map(({ scope, principal, actions }) => (
          <StructuredListRow>
            <StructuredListCell>{scope}</StructuredListCell>
            <StructuredListCell>{principal}</StructuredListCell>
            <StructuredListCell>
              <ul>
                {/* Defensive: TableExpandedRow keeps this mounted (aria-hidden, not unmounted)
                    even while collapsed, so a malformed/missing actions array on any row - not
                    just the expanded one - would otherwise throw during the initial render.
                    Same fix as Features/GlobalTokens/GlobalTokens.tsx; this tab had no test
                    that ever visited it, so the crash went unnoticed. */}
                {(actions ?? []).map((action) => (
                  <li>{action}</li>
                ))}
              </ul>
            </StructuredListCell>
          </StructuredListRow>
        ))}
      </StructuredListBody>
    </StructuredListWrapper>
  );
};

export default Tokens;
