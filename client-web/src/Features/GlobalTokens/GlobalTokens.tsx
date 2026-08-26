import React, { useEffect, useState } from "react";
import {
  DataTable,
  Pagination,
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
import {
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
  ErrorMessage,
} from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment";
import { useFetcher, useLoaderData } from "react-router-dom";
import { Box } from "reflexbox";
import CreateToken from "Components/CreateToken";
import DeleteToken from "Components/DeleteToken";
import EmptyState from "Components/EmptyState";
import { globalTokensLoader, tokenAction, type TokenActionResult } from "Components/TokenSection/tokenRoute";
import type { TokenSectionRouteData } from "Components/TokenSection/tokenRouteData";
import { arrayPagination } from "Utils/arrayHelper";
import { TokenType } from "Constants";
import { Token } from "Types";
import styles from "./GlobalTokens.module.scss";

// Route module: loader/action attached at app/routes/tokens.tsx (path=/admin/tokens), following
// the GlobalParameters.tsx reference conversion (see that file for the fuller rationale comment).
//
// Both are now the shared pair in Components/TokenSection/tokenRoute.ts rather than local copies:
// this route's <CreateToken> renders the same PermissionSelector as the other two token surfaces,
// which reads its server-driven catalog off `tokenSection.catalog`, and submits the same "create"
// intent. That also retires the `onSuccess` callback this file used to pass CreateToken - the
// Form revalidates the route itself now.
export const loader = globalTokensLoader;
export const action = tokenAction;

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

interface FeatureLayoutProps {
  children: React.ReactNode;
}

const FeatureLayout = ({ children }: FeatureLayoutProps) => {
  return (
    <div className={styles.container}>
      <Header
        className={styles.header}
        includeBorder={false}
        header={
          <>
            <HeaderTitle className={styles.headerTitle}>Global Tokens</HeaderTitle>
            <HeaderSubtitle className={styles.headerTitle}>Create tokens that can be used globally</HeaderSubtitle>
          </>
        }
      />
      <div className={styles.content}>{children}</div>
    </div>
  );
};

function Tokens() {
  const { tokenSection } = useLoaderData() as TokenSectionRouteData;
  const { tokens, errorLoading } = tokenSection;
  const fetcher = useFetcher<TokenActionResult>();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [sortKey, setSortKey] = useState("creationDate");
  const [sortDirection, setSortDirection] = useState("DESC");

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (fetcher.data.intent !== "delete") {
      return;
    }
    const ok = fetcher.data.ok;
    const errorMessage = fetcher.data.ok ? undefined : fetcher.data.errorMessage;
    notify(
      ok ? (
        <ToastNotification kind="success" title="Delete Token" subtitle={`Token successfully deleted`} />
      ) : (
        <ToastNotification
          kind="error"
          title={errorMessage?.title ?? "Something's Wrong"}
          subtitle={errorMessage?.message ?? "Request to delete token failed"}
        />
      ),
    );
  }, [fetcher.state, fetcher.data]);

  if (errorLoading) {
    return (
      <FeatureLayout>
        <ErrorMessage />
      </FeatureLayout>
    );
  }

  const deleteToken = (tokenId: string) => {
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
    <FeatureLayout>
      <div className={styles.buttonContainer}>
        <CreateToken type={TokenType.Global} principal="**" />
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
                          {tokens.find((t: Token) => t.id === row.id)!.permissions.length > 0 ? (
                            <TokenPermissions
                              permissions={tokens
                                .find((t: Token) => t.id === row.id)!
                                .permissions.map((p: Token["permissions"][number], i: number) => ({ id: `${row.id}-${i}`, ...p }))}
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
    </FeatureLayout>
  );
}

interface TokenPermissionsProps {
  // Array, not a 1-tuple - the previous `[{ ... }]` annotation only ever typechecked because
  // this data flowed through an untyped react-query cache; now that it comes from the typed
  // loader (Token[]), a real multi-element permissions array no longer satisfies a 1-tuple.
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
                    just the expanded one - would otherwise throw during the initial render. */}
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
