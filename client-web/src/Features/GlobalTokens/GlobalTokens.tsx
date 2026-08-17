import React, { useState } from "react";
import {
  DataTable,
  DataTableSkeleton,
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
import cx from "classnames";
import moment from "moment";
import queryString from "query-string";
import { useMutation, useQuery, useQueryClient } from "react-query";
import { Box } from "reflexbox";
import CreateToken from "Components/CreateToken";
import DeleteToken from "Components/DeleteToken";
import EmptyState from "Components/EmptyState";
import { arrayPagination, sortByProp } from "Utils/arrayHelper";
import { TokenType } from "Constants";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { Token } from "Types";
import styles from "./GlobalTokens.module.scss";

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
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [sortKey, setSortKey] = useState("creationDate");
  const [sortDirection, setSortDirection] = useState("DESC");

  const getTokensUrl = serviceUrl.getTokens({
    query: queryString.stringify({ types: "global" }),
  });

  const {
    data: tokensData,
    error: tokensError,
    isLoading: tokensIsLoading,
  } = useQuery({
    queryKey: getTokensUrl,
    queryFn: resolver.query(getTokensUrl),
  });

  const { mutateAsync: deleteTokenMutator } = useMutation(resolver.deleteToken, {
    onSuccess: () => queryClient.invalidateQueries([getTokensUrl]),
  });

  if (tokensIsLoading) {
    return (
      <FeatureLayout>
        <DataTableSkeleton
          data-testid="token-loading-skeleton"
          className={cx(`cds--skeleton`, `cds--data-table`, styles.tableSkeleton)}
          rowCount={DEFAULT_PAGE_SIZE}
          columnCount={HEADERS.length}
          headers={HEADERS.map((header) => header.header)}
        />
      </FeatureLayout>
    );
  }

  if (tokensError) {
    return (
      <FeatureLayout>
        <ErrorMessage />
      </FeatureLayout>
    );
  }

  const deleteToken = async (tokenId: string) => {
    try {
      await deleteTokenMutator({ tokenId });
      notify(<ToastNotification kind="success" title="Delete Token" subtitle={`Token successfully deleted`} />);
    } catch (error) {
      notify(<ToastNotification kind="error" title="Something's Wrong" subtitle="Request to delete token failed" />);
    }
  };

  const renderCell = (tokenItemId: string, cellIndex: number, value: string) => {
    const tokenDetails = tokensData?.content.find((token: Token) => token.id === tokenItemId);
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
        <CreateToken type={TokenType.Global} getTokensUrl={getTokensUrl} principal="**" />
      </div>
      {tokensData?.content?.length > 0 ? (
        <>
          <DataTable
            rows={arrayPagination(tokensData?.content, page, pageSize, sortKey, sortDirection)}
            sortRow={(rows: any) => sortByProp(rows, sortKey, sortDirection.toLowerCase())}
            headers={HEADERS}
            render={({
              rows,
              headers,
              getHeaderProps,
              getRowProps,
            }: {
              rows: any;
              headers: Array<{ header: string; key: string; sortable: boolean }>;
              getHeaderProps: any;
              getRowProps: any;
            }) => (
              <TableContainer>
                <Table isSortable>
                  <TableHead>
                    <TableRow className={styles.tableHeadRow}>
                      <TableExpandHeader />
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
                          {tokensData.content.find((t) => t.id === row.id).permissions.length > 0 ? (
                            <TokenPermissions
                              permissions={tokensData.content
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
          <Pagination
            onChange={handlePaginationChange}
            page={page}
            pageSize={pageSize}
            pageSizes={PAGE_SIZES}
            totalItems={tokensData?.content?.length}
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

export default Tokens;
