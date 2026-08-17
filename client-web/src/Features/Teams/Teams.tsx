import {
  ComposedModal,
  ErrorMessage,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, DataTable, DataTableSkeleton, Pagination, Search } from "@carbon/react";
import { CheckmarkFilled, Misuse } from "@carbon/react/icons";
import React from "react";
import { Helmet } from "react-helmet";
import { useHistory, useLocation } from "react-router-dom";
import { isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import { useFeature } from "flagged";
import debounce from "lodash/debounce";
import moment from "moment";
import queryString from "query-string";
import { Box } from "reflexbox";
import EmptyState from "Components/EmptyState";
import TeamCreateContent from "Components/TeamCardCreate/TeamCreateContent";
import { useQuery } from "Hooks";
import styles from "./Teams.module.scss";
import { appLink, queryStringOptions, FeatureFlag } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { FlowTeam, ModalTriggerProps, PaginatedTeamResponse } from "Types";

interface FeatureLayoutProps {
  handleSearchChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
}

const FeatureLayout: React.FC<FeatureLayoutProps> = ({ children, handleSearchChange }) => {
  return (
    <>
      <Helmet>
        <title>Teams</title>
      </Helmet>
      <Header
        includeBorder={false}
        header={
          <>
            <HeaderTitle style={{ margin: "0" }}>Teams</HeaderTitle>
            <HeaderSubtitle>View and manage teams</HeaderSubtitle>
          </>
        }
      />
      <Box p="2rem" className={styles.content}>
        <>
          <Box mb="1rem" maxWidth="20rem">
            <Search id="flow-teams" labelText="Search teams" placeholder="Search teams" onChange={handleSearchChange} />
          </Box>
          {children}
        </>
      </Box>
    </>
  );
};

const DEFAULT_ORDER = "DESC";
const DEFAULT_PAGE = 0;
const DEFAULT_LIMIT = 10;
const DEFAULT_SORT = "name";
const PAGE_SIZES = [DEFAULT_LIMIT, 20, 50, 100];

const TeamList: React.FC = () => {
  const history = useHistory();
  const location = useLocation();
  // TODO - make this read only
  const teamManagementEnabled = useFeature(FeatureFlag.TeamManagementEnabled);

  /**
   * Prepare queries and get some data
   */
  const {
    order = DEFAULT_ORDER,
    page = DEFAULT_PAGE,
    limit = DEFAULT_LIMIT,
    sort = DEFAULT_SORT,
  } = queryString.parse(location.search, queryStringOptions);

  const teamsUrlQuery = queryString.stringify({
    order,
    page,
    limit,
    sort,
  });

  const teamsUrl = serviceUrl.getTeams({ query: teamsUrlQuery });

  const {
    data: teamsData,
    error: teamsIsError,
    isLoading: teamsIsLoading,
  } = useQuery<PaginatedTeamResponse, string>(teamsUrl);

  /**
   * Function that updates url search history to persist state
   * @param {object} query - all of the query params
   *
   */
  function updateHistorySearch({
    order = DEFAULT_ORDER,
    page = DEFAULT_PAGE,
    limit = DEFAULT_LIMIT,
    sort = DEFAULT_SORT,
    ...props
  }) {
    const queryStr = `?${queryString.stringify({ order, page, limit, sort, ...props })}`;
    history.push({ search: queryStr });
    return;
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

  function handleNavigateToTeam(team: string) {
    history.push({
      pathname: appLink.manageTeam({ team }),
      state: {
        navList: [
          {
            to: location.pathname,
            text: "Teams",
          },
        ],
      },
    });
  }

  if (teamsIsLoading) {
    return (
      <FeatureLayout handleSearchChange={handleSearchChange}>
        <DataTableSkeleton />
      </FeatureLayout>
    );
  }

  if (teamsIsError) {
    return (
      <FeatureLayout handleSearchChange={handleSearchChange}>
        <ErrorMessage />
      </FeatureLayout>
    );
  }
  return (
    <FeatureLayout handleSearchChange={handleSearchChange}>
      {teamsData && teamManagementEnabled && (
        <ComposedModal
          composedModalProps={{ shouldCloseOnOverlayClick: true }}
          modalHeaderProps={{
            title: "Create Team",
            subtitle: `Scope your workflows and parameters to a team`,
          }}
          modalTrigger={({ openModal }: ModalTriggerProps) => (
            <Button
              iconDescription="Create new version"
              onClick={openModal}
              size="md"
              disabled={teamsIsError || teamsIsLoading}
              className={styles.createTeamTrigger}
            >
              Create Team
            </Button>
          )}
        >
          {({ closeModal }) => {
            return <TeamCreateContent closeModal={closeModal} />;
          }}
        </ComposedModal>
      )}
      <TeamListTable
        handleNavigateToTeam={handleNavigateToTeam}
        location={location}
        sort={sort}
        order={order}
        tableData={teamsData}
        updateHistorySearch={updateHistorySearch}
      />
    </FeatureLayout>
  );
};

const headers = [
  {
    header: "Name",
    key: "displayName",
    sortable: true,
  },
  {
    header: "Date Created",
    key: "creationDate",
    sortable: true,
  },
  {
    header: "# of Users",
    key: "members",
  },
  { header: "# of Workflows", key: "quotas" },
  { header: "Status", key: "status" },
];

interface TeamListTableProps {
  handleNavigateToTeam: Function;
  location: any;
  sort: string;
  order: string;
  tableData: {
    number: number;
    size: number;
    totalElements: number;
    content: any;
  };
  updateHistorySearch: Function;
}

function TeamListTable(props: TeamListTableProps) {
  const { number, size, totalElements, content } = props.tableData;
  const { TableContainer, Table, TableHead, TableRow, TableBody, TableCell, TableHeader } = DataTable;

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

  return content.length > 0 ? (
    <>
      <DataTable
        rows={content.map((t: FlowTeam) => ({ ...t, id: t.name }))}
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
                    data-testid="team-list-table-row"
                    onClick={() => props.handleNavigateToTeam(row.id)}
                    onKeyDown={(e: React.SyntheticEvent) =>
                      isAccessibleKeyboardEvent(e) && props.handleNavigateToTeam(row.id)
                    }
                    tabIndex={-1}
                  >
                    {row.cells.map((cell: any, cellIndex: any) => {
                      if (cell.info.header === "status") {
                        return (
                          <TableCell key={cell.id} id={cell.id}>
                            {cell.value === "active" ? (
                              <CheckmarkFilled aria-label="Active" fill="green" />
                            ) : (
                              <Misuse aria-label="Inactive" fill="red" />
                            )}
                          </TableCell>
                        );
                      } else if (cell.info.header === "creationDate") {
                        return (
                          <TableCell key={cell.id}>
                            <time>{moment(cell.value).format("YYYY-MM-DD hh:mm A")}</time>
                          </TableCell>
                        );
                      } else if (cell.info.header === "quotas") {
                        return <TableCell key={cell.id}>{cell.value.currentWorkflowCount}</TableCell>;
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
    <EmptyState message={null} title="No teams found" />
  );
}

export default TeamList;
