import {
  ComposedModal,
  ErrorMessage,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
  notify,
  ToastNotification,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, DataTable, DataTableSkeleton, Pagination, Search } from "@carbon/react";
import { CheckmarkFilled, Misuse } from "@carbon/react/icons";
import React from "react";
import { Helmet } from "react-helmet";
import { useNavigate, useLocation } from "react-router-dom";
import { formatErrorMessage, isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import { useFeature } from "flagged";
import debounce from "lodash/debounce";
import kebabcase from "lodash/kebabCase";
import moment from "moment";
import queryString from "query-string";
import { useMutation, useQueryClient } from "react-query";
import { Box } from "reflexbox";
import EmptyState from "Components/EmptyState";
import WorkspaceCreateContent from "Components/WorkspaceCardCreate/WorkspaceCreateContent";
import { useAppContext, useQuery } from "Hooks";
import styles from "./Workspaces.module.scss";
import { appLink, queryStringOptions, FeatureFlag } from "Config/appConfig";
import { resolver, serviceUrl } from "Config/servicesConfig";
import { FlowWorkspace, MemberRole, ModalTriggerProps, PaginatedWorkspaceResponse } from "Types";

interface FeatureLayoutProps {
  children?: React.ReactNode;
  handleSearchChange: (e: { target: HTMLInputElement; type: "change" }) => void;
}

const FeatureLayout: React.FC<FeatureLayoutProps> = ({ children, handleSearchChange }) => {
  return (
    <>
      <Helmet>
        <title>Workspaces</title>
      </Helmet>
      <Header
        includeBorder={false}
        header={
          <>
            <HeaderTitle style={{ margin: "0" }}>Workspaces</HeaderTitle>
            <HeaderSubtitle>View and manage workspaces</HeaderSubtitle>
          </>
        }
      />
      <Box p="2rem" className={styles.content}>
        <>
          <Box mb="1rem" maxWidth="20rem">
            <Search id="flow-workspaces" labelText="Search workspaces" placeholder="Search workspaces" onChange={handleSearchChange} />
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

const WorkspaceList: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useAppContext();
  const queryClient = useQueryClient();
  // TODO - make this read only
  const workspaceManagementEnabled = useFeature(FeatureFlag.WorkspaceManagementEnabled);

  /**
   * Prepare queries and get some data
   */
  const parsedQuery = queryString.parse(location.search, queryStringOptions);
  const order = typeof parsedQuery.order === "string" ? parsedQuery.order : DEFAULT_ORDER;
  const page = parsedQuery.page ?? DEFAULT_PAGE;
  const limit = parsedQuery.limit ?? DEFAULT_LIMIT;
  const sort = typeof parsedQuery.sort === "string" ? parsedQuery.sort : DEFAULT_SORT;

  const workspacesUrlQuery = queryString.stringify({
    order,
    page,
    limit,
    sort,
  });

  const workspacesUrl = serviceUrl.getWorkspaces({ query: workspacesUrlQuery });

  const createWorkspaceMutator = useMutation(resolver.postWorkspace);

  const createWorkspace = async (values: { name: string | undefined }, success_fn?: (...args: any) => any) => {
    try {
      await createWorkspaceMutator.mutateAsync({
        body: {
          name: kebabcase(values.name?.replace(`'`, "-")),
          displayName: values.name,
          members: [
            {
              email: user.email,
              role: MemberRole.Owner,
            },
          ],
        },
      });
      queryClient.invalidateQueries(workspacesUrl);
      notify(<ToastNotification kind="success" title="Create Workspace" subtitle="Workspace created successfully" />);
      if (typeof success_fn === "function") {
        success_fn();
      }
    } catch (error) {
      const errorMessages = formatErrorMessage({ error });
      notify(<ToastNotification kind="error" title="Something went wrong" subtitle={errorMessages.message} />);
    }
  };

  const {
    data: workspacesData,
    error: workspacesIsError,
    isLoading: workspacesIsLoading,
  } = useQuery<PaginatedWorkspaceResponse, string>(workspacesUrl);

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
    navigate({ search: queryStr });
    return;
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

  function handleNavigateToWorkspace(workspace: string) {
    navigate(appLink.manageWorkspace({ workspace }), {
      state: {
        navList: [
          {
            to: location.pathname,
            text: "Workspaces",
          },
        ],
      },
    });
  }

  if (workspacesIsLoading) {
    return (
      <FeatureLayout handleSearchChange={handleSearchChange}>
        <DataTableSkeleton />
      </FeatureLayout>
    );
  }

  if (workspacesIsError || !workspacesData) {
    return (
      <FeatureLayout handleSearchChange={handleSearchChange}>
        <ErrorMessage />
      </FeatureLayout>
    );
  }
  return (
    <FeatureLayout handleSearchChange={handleSearchChange}>
      {workspacesData && workspaceManagementEnabled && (
        <ComposedModal
          composedModalProps={{ shouldCloseOnOverlayClick: true }}
          modalHeaderProps={{
            title: "Create Workspace",
            subtitle: `Scope your workflows and parameters to a workspace`,
          }}
          modalTrigger={({ openModal }: ModalTriggerProps) => (
            <Button
              iconDescription="Create new version"
              onClick={openModal}
              size="md"
              disabled={Boolean(workspacesIsError) || workspacesIsLoading}
              className={styles.createWorkspaceTrigger}
            >
              Create Workspace
            </Button>
          )}
        >
          {({ closeModal }) => {
            return (
              <WorkspaceCreateContent
                closeModal={closeModal}
                createWorkspace={createWorkspace}
                isError={createWorkspaceMutator.isError}
                isLoading={createWorkspaceMutator.isLoading}
              />
            );
          }}
        </ComposedModal>
      )}
      <WorkspaceListTable
        handleNavigateToWorkspace={handleNavigateToWorkspace}
        location={location}
        sort={sort}
        order={order}
        tableData={workspacesData}
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

interface WorkspaceListTableProps {
  handleNavigateToWorkspace: Function;
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

function WorkspaceListTable(props: WorkspaceListTableProps) {
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
        rows={content.map((t: FlowWorkspace) => ({ ...t, id: t.name }))}
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
                    data-testid="workspace-list-table-row"
                    onClick={() => props.handleNavigateToWorkspace(row.id)}
                    onKeyDown={(e: React.SyntheticEvent) =>
                      isAccessibleKeyboardEvent(e) && props.handleNavigateToWorkspace(row.id)
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
                        return <TableCell key={cell.id}>{cell.value?.currentWorkflowCount ?? "---"}</TableCell>;
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
    <EmptyState message={null} title="No workspaces found" />
  );
}

export default WorkspaceList;
