import {
  ComposedModal,
  ErrorMessage,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
  notify,
  ToastNotification,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, DataTable, Pagination, Search } from "@carbon/react";
import { CheckmarkFilled, Misuse } from "@carbon/react/icons";
import React, { useEffect, useRef } from "react";
import { Helmet } from "react-helmet";
import { useFetcher, useLoaderData, useNavigate, useLocation, useRevalidator } from "react-router-dom";
import { formatErrorMessage, isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import { useFeature } from "flagged";
import debounce from "lodash/debounce";
import kebabcase from "lodash/kebabCase";
import moment from "moment";
import queryString from "query-string";
import { Box } from "reflexbox";
import EmptyState from "Components/EmptyState";
import WorkspaceCreateContent from "Components/WorkspaceCardCreate/WorkspaceCreateContent";
import { useAppContext } from "Hooks";
import styles from "./Workspaces.module.scss";
import { appLink, queryStringOptions, FeatureFlag } from "Config/appConfig";
import { serverFetch } from "Config/serverFetch";
import { serviceUrl } from "Config/servicesConfig";
import { FlowWorkspace, MemberRole, ModalTriggerProps, PaginatedWorkspaceResponse } from "Types";

// Route module: this file's `loader`/`action` are re-exported from app/routes/workspaceList.tsx,
// the same split GlobalParameters.tsx and UserDetailed.tsx use (see those files for the fuller
// rationale comments on serverFetch/errorLoading/ssr:true).

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

type LoaderData = {
  workspaces: PaginatedWorkspaceResponse | null;
  errorLoading: boolean;
};

// Server loader (ssr:true - see GlobalParameters.tsx/CLAUDE.md for the fuller rationale). Reads
// the same order/page/limit/sort search params the component below parses off `location.search`
// - kept as parallel, not shared, logic: the loader only has `request.url` to work with, and the
// component still needs order/sort for its own display concerns (sort-header state, pagination
// controls) independent of the fetch. Note `query` (the search box's debounced param) is parsed
// into the URL but was never actually forwarded to the API by the pre-loader code either - that
// existing behaviour (search box updates the URL but doesn't filter server-side) is preserved
// as-is rather than "fixed" as part of this conversion.
export async function loader({ request }: { request: Request }): Promise<LoaderData> {
  const url = new URL(request.url);
  const parsedQuery = queryString.parse(url.search, queryStringOptions);
  const order = typeof parsedQuery.order === "string" ? parsedQuery.order : DEFAULT_ORDER;
  const page = parsedQuery.page ?? DEFAULT_PAGE;
  const limit = parsedQuery.limit ?? DEFAULT_LIMIT;
  const sort = typeof parsedQuery.sort === "string" ? parsedQuery.sort : DEFAULT_SORT;

  const workspacesUrlQuery = queryString.stringify({ order, page, limit, sort });

  try {
    const response = await serverFetch(request).get(serviceUrl.getWorkspaces({ query: workspacesUrlQuery }));
    return { workspaces: response.data, errorLoading: false };
  } catch (error) {
    return { workspaces: null, errorLoading: true };
  }
}

type ActionResult = {
  ok: boolean;
  errorMessage?: { title: string; message: string };
};

// Only one intent today (create) - kept intent-keyed anyway to match the established
// GlobalParameters.tsx action shape/signature for any writes this route grows later.
export async function action({ request }: { request: Request }): Promise<ActionResult> {
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "create") {
    const body = JSON.parse(String(formData.get("body")));
    try {
      await serverFetch(request).post(serviceUrl.postWorkspace(), body);
      return { ok: true };
    } catch (error) {
      return { ok: false, errorMessage: formatErrorMessage({ error }) };
    }
  }

  return { ok: false };
}

const WorkspaceList: React.FC = () => {
  const navigate = useNavigate();
  const location = useLocation();
  const { user } = useAppContext();
  const revalidator = useRevalidator();
  // TODO - make this read only
  const workspaceManagementEnabled = useFeature(FeatureFlag.WorkspaceManagementEnabled);
  const { workspaces: workspacesData, errorLoading } = useLoaderData() as LoaderData;
  const fetcher = useFetcher<ActionResult>();
  // handleSubmit/Formik hand this component a `closeModal` at submit time (see
  // WorkspaceCreateContent); the fetcher settles asynchronously (fetcher.state -> "idle"), so the
  // callback is stashed here and invoked from the effect below only on success - the same
  // "stay open with a spinner, close only on success" behaviour the old mutateAsync/then chain
  // had (see GlobalParameters.tsx for the identical pattern).
  const closeModalRef = useRef<(() => void) | null>(null);

  /**
   * Prepare queries and get some data
   */
  const parsedQuery = queryString.parse(location.search, queryStringOptions);
  const order = typeof parsedQuery.order === "string" ? parsedQuery.order : DEFAULT_ORDER;
  const sort = typeof parsedQuery.sort === "string" ? parsedQuery.sort : DEFAULT_SORT;

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    const { ok, errorMessage } = fetcher.data;
    if (ok) {
      // Refresh the loader-driven list rather than react-query's queryClient.invalidateQueries -
      // once the read is loader-driven, invalidateQueries is an inert no-op (see CLAUDE.md).
      revalidator.revalidate();
      notify(<ToastNotification kind="success" title="Create Workspace" subtitle="Workspace created successfully" />);
      closeModalRef.current?.();
      closeModalRef.current = null;
    } else {
      notify(
        <ToastNotification kind="error" title="Something went wrong" subtitle={errorMessage?.message} />,
      );
    }
  }, [fetcher.state, fetcher.data]);

  const createWorkspace = (values: { name: string | undefined }, success_fn?: (...args: any) => any) => {
    closeModalRef.current = typeof success_fn === "function" ? success_fn : null;
    fetcher.submit(
      {
        intent: "create",
        body: JSON.stringify({
          name: kebabcase(values.name?.replace(`'`, "-")),
          displayName: values.name,
          members: [
            {
              email: user.email,
              role: MemberRole.Owner,
            },
          ],
        }),
      },
      { method: "post" },
    );
  };

  const isCreatingWorkspace = fetcher.state !== "idle";
  const isCreateWorkspaceError = Boolean(fetcher.data && !fetcher.data.ok);

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

  if (errorLoading || !workspacesData) {
    return (
      <FeatureLayout handleSearchChange={handleSearchChange}>
        <ErrorMessage />
      </FeatureLayout>
    );
  }
  return (
    <FeatureLayout handleSearchChange={handleSearchChange}>
      {workspaceManagementEnabled && (
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
                isError={isCreateWorkspaceError}
                isLoading={isCreatingWorkspace}
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
