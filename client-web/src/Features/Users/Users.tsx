import React from "react";
import { DataTable, Pagination, Search } from "@carbon/react";
import { CheckmarkFilled, Misuse } from "@carbon/react/icons";
import {
  ErrorMessage,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import debounce from "lodash/debounce";
import moment from "moment";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { useLoaderData, useNavigate, useLocation } from "react-router-dom";
import { Box } from "reflexbox";
import EmptyState from "Components/EmptyState";
import { CREATED_DATE_FORMAT } from "Constants";
import { appLink, queryStringOptions } from "Config/appConfig";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import { PaginatedUserResponse } from "Types";
import styles from "./Users.module.scss";

// This used to also route ":userId/*" to UserDetailed via its own internal <Routes> - the list
// and the detail view are now separate top-level routes (AppPath.UserList / AppPath.User in
// AppRoutes.tsx) so UserDetailed's read can be a loader (loaders only attach to routes declared
// in the router config, not to routes matched by a <Routes> rendered from inside a component).
//
// Route module: this file's `loader` is re-exported from app/routes/userList.tsx, the same split
// GlobalParameters.tsx and UserDetailed.tsx use (see those files for the fuller rationale
// comments on serverFetch/errorLoading/ssr:true). This route has no writes of its own (role
// changes live in UserDetailed, a separate route not touched here), so there's no `action`.

const DEFAULT_ORDER = "DESC";
const DEFAULT_PAGE = 0;
const DEFAULT_LIMIT = 10;
const DEFAULT_SORT = "name";
const PAGE_SIZES = [DEFAULT_LIMIT, 20, 50, 100];

type LoaderData = {
  users: PaginatedUserResponse | null;
  errorLoading: boolean;
};

// Server loader (ssr:true). Mirrors the order/page/limit/sort parsing the component does off
// `location.search` for its own display concerns (sort-header state, pagination controls) - kept
// as parallel, not shared, logic since the loader only has `request.url` to work with. As with
// Workspaces.tsx, `query` (the search box's debounced param) is parsed into the URL but was never
// forwarded to the API by the pre-loader code either - preserved as-is.
export async function loader({ request }: { request: Request }): Promise<LoaderData> {
  const url = new URL(request.url);
  const parsedQuery = queryString.parse(url.search, queryStringOptions);
  const order = typeof parsedQuery.order === "string" ? parsedQuery.order : DEFAULT_ORDER;
  const page = parsedQuery.page ?? DEFAULT_PAGE;
  const limit = parsedQuery.limit ?? DEFAULT_LIMIT;
  const sort = typeof parsedQuery.sort === "string" ? parsedQuery.sort : DEFAULT_SORT;

  const usersUrlQuery = queryString.stringify({ order, page, limit, sort });

  try {
    const response = await serverFetch(request).get(serviceUrl.getUsers({ query: usersUrlQuery }));
    return { users: response.data, errorLoading: false };
  } catch (error) {
    return { users: null, errorLoading: true };
  }
}

interface FeatureLayoutProps {
  children?: React.ReactNode;
  handleSearchChange: (e: { target: HTMLInputElement; type: "change" }) => void;
}

const FeatureLayout: React.FC<FeatureLayoutProps> = ({ children, handleSearchChange }) => {
  return (
    <>
      <Helmet>
        <title>Users</title>
      </Helmet>
      <Header
        includeBorder={false}
        header={
          <>
            <HeaderTitle style={{ margin: "0" }}>Users</HeaderTitle>
            <HeaderSubtitle>View and manage users</HeaderSubtitle>
          </>
        }
      />
      <Box p="2rem" className={styles.content}>
        <>
          <Box mb="1rem" maxWidth="20rem">
            <Search id="flow-users" labelText="Search users" placeholder="Search users" onChange={handleSearchChange} />
          </Box>
          {children}
        </>
      </Box>
    </>
  );
};

function UserList() {
  const navigate = useNavigate();
  const location = useLocation();
  const { users: usersData, errorLoading } = useLoaderData() as LoaderData;

  const parsedQuery = queryString.parse(location.search, queryStringOptions);
  const order = typeof parsedQuery.order === "string" ? parsedQuery.order : DEFAULT_ORDER;
  const sort = typeof parsedQuery.sort === "string" ? parsedQuery.sort : DEFAULT_SORT;

  function handleNavigateToUser(userId: string) {
    navigate(appLink.user({ userId }));
  }

  /**
   * Function that updates url search history to persist state
   * @param {object} query - all of the query params
   *
   */
  function updateHistorySearch({
    order = DEFAULT_ORDER,
    page = DEFAULT_PAGE,
    size = DEFAULT_LIMIT,
    sort = DEFAULT_SORT,
    ...props
  }) {
    const queryStr = `?${queryString.stringify({ order, page, size, sort, ...props })}`;
    navigate({ search: queryStr });
  }
  // eslint-disable-next-line
  const debouncedSearch = React.useCallback(
    debounce((query: string) => {
      updateHistorySearch({ query, page: 0 });
    }, 300),
    [],
  );

  function handleSearchChange(e: { target: HTMLInputElement; type: "change" }) {
    const query = e.target.value;
    debouncedSearch(query);
  }

  if (errorLoading || !usersData) {
    return (
      <FeatureLayout handleSearchChange={handleSearchChange}>
        <ErrorMessage />
      </FeatureLayout>
    );
  }
  return (
    <FeatureLayout handleSearchChange={handleSearchChange}>
      <UsersTable
        handleNavigateToUser={handleNavigateToUser}
        location={location}
        sort={sort}
        order={order}
        tableData={usersData}
        updateHistorySearch={updateHistorySearch}
      />
    </FeatureLayout>
  );
}

const TableHeaderKey = {
  Name: "name",
  DisplayName: "displayName",
  Email: "email",
  Type: "type",
  Created: "creationDate",
  LastLogin: "lastLoginDate",
  Status: "status",
};

const headers = [
  {
    header: "Name",
    key: TableHeaderKey.Name,
    sortable: true,
  },
  {
    header: "Preferred Display Name",
    key: TableHeaderKey.DisplayName,
    sortable: false,
  },
  {
    header: "Email",
    key: TableHeaderKey.Email,
    sortable: true,
  },
  {
    header: "Type",
    key: TableHeaderKey.Type,
    sortable: true,
  },
  {
    header: "First Login",
    key: TableHeaderKey.Created,
    sortable: true,
  },
  {
    header: "Last Login",
    key: TableHeaderKey.LastLogin,
    sortable: true,
  },
  {
    header: "Status",
    key: TableHeaderKey.Status,
    sortable: true,
  },
];

interface UsersTableProps {
  handleNavigateToUser: (userId: string) => void;
  updateHistorySearch: Function;
  location: any;
  sort: string;
  order: string;
  tableData: {
    number: number;
    size: number;
    totalElements: number;
    content: any;
  };
}

function UsersTable(props: UsersTableProps) {
  const { TableContainer, Table, TableHead, TableRow, TableBody, TableCell, TableHeader } = DataTable;
  const { number, size, totalElements, content } = props.tableData;

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

  return content?.length > 0 ? (
    <>
      <DataTable
        rows={content}
        headers={headers}
        render={({ rows, headers, getHeaderProps }: any) => (
          <TableContainer>
            <Table isSortable>
              <TableHead>
                <TableRow>
                  {headers.map((header: any) => (
                    <TableHeader
                      id={header.key}
                      {...getHeaderProps({
                        header,
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
              <TableBody>
                {rows.map((row: any) => (
                  <TableRow
                    className={styles.tableRow}
                    key={row.id}
                    onClick={() => props.handleNavigateToUser(row.id)}
                    onKeyDown={(e: React.SyntheticEvent) =>
                      isAccessibleKeyboardEvent(e) && props.handleNavigateToUser(row.id)
                    }
                    tabIndex={-1}
                  >
                    {row.cells.map((cell: any) => {
                      if (
                        cell.info.header === TableHeaderKey.Created ||
                        cell.info.header === TableHeaderKey.LastLogin
                      ) {
                        return <TableCell key={cell.id}>{moment(cell.value).format(CREATED_DATE_FORMAT)}</TableCell>;
                      } else if (cell.info.header === TableHeaderKey.Status) {
                        return (
                          <TableCell key={cell.id} id={cell.id}>
                            {cell.value === "active" ? (
                              <CheckmarkFilled aria-label="Active" fill="green" />
                            ) : (
                              <Misuse aria-label="Inactive" fill="red" />
                            )}
                          </TableCell>
                        );
                      }

                      return (
                        <TableCell key={cell.id}>
                          {Array.isArray(cell.value) ? cell.value.length : cell?.value ?? "---"}
                        </TableCell>
                      );
                    })}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      />
      <Pagination
        onChange={handlePaginationChange}
        page={number + 1}
        pageSize={size}
        pageSizes={PAGE_SIZES}
        totalItems={totalElements}
      />
    </>
  ) : (
    <EmptyState message="No users found" />
  );
}

export default UserList;
