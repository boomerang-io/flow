import React from "react";
import {
  DataTable,
  DataTableSkeleton,
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
import { notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import cx from "classnames";
import moment from "moment";
import queryString from "query-string";
import { useMutation, useQuery, useQueryClient } from "react-query";
import CreateToken from "Components/CreateToken";
import DeleteToken from "Components/DeleteToken";
import { resolver, serviceUrl } from "Config/servicesConfig";
import type { Token, TokenScopeType } from "Types";
import styles from "./TokenSection.module.scss";

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
    header: "",
    key: "delete",
    sortable: false,
  },
];

interface TokenProps {
  type: TokenScopeType;
  principal?: string;
}

const TokenSection: React.FC<TokenProps> = ({ type, principal }) => {
  const queryClient = useQueryClient();
  const getTokensUrl = serviceUrl.getTokens({
    query: queryString.stringify({ types: type, principals: principal }),
  });
  const getTokensQuery = useQuery({
    queryKey: getTokensUrl,
    queryFn: resolver.query(getTokensUrl),
  });

  const deleteTokenMutator = useMutation(resolver.deleteToken);

  if (getTokensQuery.isLoading) {
    return (
      <DataTableSkeleton
        data-testid="token-loading-skeleton"
        className={cx(`cds--skeleton`, `cds--data-table`, styles.tableSkeleton)}
        rowCount={3}
        columnCount={HEADERS.length}
        headers={HEADERS.map((header) => header.header)}
      />
    );
  }

  const deleteToken = async (tokenId: string) => {
    try {
      await deleteTokenMutator.mutateAsync({ tokenId });
      queryClient.invalidateQueries([getTokensUrl]);
      notify(<ToastNotification kind="success" title="Delete Token" subtitle={`Token successfully deleted`} />);
    } catch (error) {
      notify(<ToastNotification kind="error" title="Something's Wrong" subtitle="Request to delete token failed" />);
    }
  };

  const renderCell = (tokenItemId: string, cellIndex: number, value: string) => {
    const tokenDetails = getTokensQuery.data.content.find((token: Token) => token.id === tokenItemId);
    const column = HEADERS[cellIndex];
    switch (column.key) {
      case "permissions":
        return <p className={styles.tableTextarea}>{value ? tokenDetails.permissions.join(", ") : "---"}</p>;
      case "valid":
        return <p className={styles.tableTextarea}>{value ? "Active" : "Inactive"}</p>;
      case "creationDate":
      case "expirationDate":
        return (
          <p className={styles.tableTextarea}>
            {value ? moment(value).utc().startOf("day").format("MMMM DD, YYYY") : "---"}
          </p>
        );
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
      <CreateToken getTokensUrl={getTokensUrl} principal={principal} type={type} />
      {getTokensQuery.data.content.length > 0 && (
        <DataTable
          rows={getTokensQuery.data.content}
          headers={HEADERS}
          pageSize={getTokensQuery.data.content.length}
          render={({ rows, headers, getHeaderProps, getRowProps, getTableProps, getTableContainerProps }) => (
            <TableContainer title="" description="" {...getTableContainerProps()}>
              <Table {...getTableProps()}>
                <TableHead>
                  <TableRow>
                    <TableExpandHeader />
                    {headers.map((header) => (
                      <TableHeader key={header.key} {...getHeaderProps({ header })}>
                        {header.header}
                      </TableHeader>
                    ))}
                  </TableRow>
                </TableHead>
                <TableBody>
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
                        {getTokensQuery.data.content.find((t) => t.id === row.id).permissions.length > 0 ? (
                          <TokenPermissions
                            permissions={getTokensQuery.data.content
                              .find((t) => t.id === row.id)
                              .permissions.map((p, i) => ({ id: `${row.id}-${i}`, ...p }))}
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
      )}
    </div>
  );
};

interface TokenPermissionsProps {
  permissions: [{ scope: string; principal: string; actions: string[] }];
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
                {actions.map((action) => (
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
