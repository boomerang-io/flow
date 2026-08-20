import React from "react";
import {
  DataTable,
  Table,
  TableHead,
  TableRow,
  TableHeader,
  TableBody,
  TableCell,
  TableContainer,
  TableExpandHeader,
  TableExpandRow,
  TableExpandedRow,
  StructuredListWrapper,
  StructuredListHead,
  StructuredListBody,
  StructuredListRow,
  StructuredListCell,
} from "@carbon/react";
import { notify, ToastNotification, ErrorMessage } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment";
import { useFetcher, useRevalidator } from "react-router-dom";
import CreateToken from "Components/CreateToken";
import DeleteToken from "Components/DeleteToken";
import { TokenActorKind } from "Constants";
import type { Token, TokenScopeType } from "Types";
import styles from "./TokenSection.module.scss";
import { useTokenSectionData } from "./tokenRouteData";
import type { TokenActionResult } from "./tokenRoute";

/*
 * Loader/action-driven (see tokenRoute.ts): the token list comes from the matched route's
 * loader via useTokenSectionData(), and the delete goes through that route's action via
 * useFetcher() - no useQuery/useMutation/queryClient here any more. The two routes that render
 * this component (/profile and /:workspace/editor/:workflow/*) both export the shared
 * loader/action pair, so the bare `fetcher.submit(..., { method: "post" })` below resolves
 * correctly from either - including from inside the editor's descendant <Routes>, as
 * TaskTemplateOverview.tsx already does.
 */

type TokenActorKindType = (typeof TokenActorKind)[keyof typeof TokenActorKind];

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

interface TokenProps {
  type: TokenScopeType;
  principal?: string;
  actorKind?: TokenActorKindType;
}

const TokenSection: React.FC<TokenProps> = ({ type, principal, actorKind }) => {
  const routeData = useTokenSectionData();
  const fetcher = useFetcher<TokenActionResult>();
  const revalidator = useRevalidator();

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "delete") {
      return;
    }
    const result = fetcher.data;
    if (result.ok) {
      // The list is loader-driven, so there is no react-query cache entry to invalidate -
      // re-run the route's loader instead.
      revalidator.revalidate();
      notify(<ToastNotification kind="success" title="Delete Token" subtitle={`Token successfully deleted`} />);
    } else {
      notify(
        <ToastNotification
          kind="error"
          title={result.errorMessage?.title ?? "Something's Wrong"}
          subtitle={result.errorMessage?.message ?? "Request to delete token failed"}
        />,
      );
    }
    // revalidator identity changes on every state transition; depending on it here would re-fire
    // this effect (and re-notify) after its own revalidate.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

  // No loading branch any more - the loader has resolved before this renders. A route that
  // renders <TokenSection> without the shared loader (see tokenRoute.ts) lands here too, which
  // is the same "we have no tokens to show" outcome a failed fetch produces.
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

  return (
    <div className={styles.dataTable}>
      <CreateToken principal={principal} type={type} actorKind={actorKind} />
      {tokens.length > 0 && (
        <DataTable<Token, any[]>
          rows={tokens}
          headers={HEADERS}
          pageSize={tokens.length}
          render={({ rows, headers, getHeaderProps, getRowProps, getTableProps, getTableContainerProps }) => (
            <TableContainer title="" description="" {...getTableContainerProps()}>
              <Table {...getTableProps()}>
                <TableHead>
                  <TableRow>
                    <TableExpandHeader aria-label="Expand row" />
                    {headers.map((header) => (
                      <TableHeader {...getHeaderProps({ header })}>{header.header}</TableHeader>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((row: any) => {
                    const tokenPermissions = tokens.find((t) => t.id === row.id)?.permissions ?? [];
                    return (
                      <>
                        <TableExpandRow {...getRowProps({ row })}>
                          {row.cells.map((cell: any, cellIndex: number) => (
                            <TableCell key={cell.id} style={{ padding: "0" }}>
                              <div className={styles.tableCell}>{renderCell(row.id, cellIndex, cell.value)}</div>
                            </TableCell>
                          ))}
                        </TableExpandRow>
                        <TableExpandedRow colSpan={headers.length + 1}>
                          {tokenPermissions.length > 0 ? (
                            <TokenPermissions
                              permissions={tokenPermissions.map((p, i) => ({ id: `${row.id}-${i}`, ...p }))}
                            />
                          ) : (
                            "Permissions detail unavailable"
                          )}
                        </TableExpandedRow>
                      </>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        />
      )}
    </div>
  );
};

interface TokenPermissionsProps {
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
                {/* Defensive, same as GlobalTokens.tsx: TableExpandedRow keeps this mounted
                    (aria-hidden, not unmounted) while collapsed, so a malformed/missing actions
                    array on any row would otherwise throw during the initial render. */}
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

export default TokenSection;
