import React from "react";
import { DataTable, DataTableSkeleton, Pagination, Search } from "@carbon/react";
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
import { useHistory, useLocation, Route, Switch } from "react-router-dom";
import { Box } from "reflexbox";
import UserDetailed from "Features/UserDetailed";
import EmptyState from "Components/EmptyState";
import { useQuery } from "Hooks";
import { CREATED_DATE_FORMAT } from "Constants";
import { AppPath, appLink, queryStringOptions } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { PaginatedUserResponse } from "Types";
import styles from "./Users.module.scss";

const DEFAULT_ORDER = "DESC";
const DEFAULT_PAGE = 0;
const DEFAULT_LIMIT = 10;
const DEFAULT_SORT = "name";
const PAGE_SIZES = [DEFAULT_LIMIT, 20, 50, 100];

const UsersContainer: React.FC = () => {
  return (
    <Switch>
      <Route path={AppPath.User}>
        <UserDetailed />
      </Route>
      <Route path={AppPath.UserList}>
        <UserList />
      </Route>
    </Switch>
  );
};

interface FeatureLayoutProps {
  handleSearchChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
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

const UserList: React.FC = () => {
  const history = useHistory();
  const location = useLocation();

  const {
    order = DEFAULT_ORDER,
    page = DEFAULT_PAGE,
    limit = DEFAULT_LIMIT,
    sort = DEFAULT_SORT,
  } = queryString.parse(location.search, queryStringOptions);

  const usersUrlQuery = queryString.stringify({
    order,
    page,
    limit,
    sort,
  });

  const usersUrl = serviceUrl.getUsers({ query: usersUrlQuery });

  const {
    data: usersData,
    error: usersIsError,
    isLoading: usersIsLoading,
  } = useQuery<PaginatedUserResponse, string>(usersUrl);

  function handleNavigateToUser(userId: string) {
    history.push(appLink.user({ userId }));
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
    history.push({ search: queryStr });
  }
  // eslint-disable-next-line
  const debouncedSearch = React.useCallback(
    debounce((query: string) => {
      updateHistorySearch({ query, page: 0 });
    }, 300),
    [],
  );

  function handleSearchChange(e: React.ChangeEvent<HTMLInputElement>) {
    const query = e.target.value;
    debouncedSearch(query);
  }

  if (usersIsLoading) {
    return (
      <FeatureLayout handleSearchChange={handleSearchChange}>
        <DataTableSkeleton />
      </FeatureLayout>
    );
  }

  if (usersIsError) {
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
};

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

export default UsersContainer;
