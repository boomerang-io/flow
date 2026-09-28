import React from "react";
import { Button, DataTable, InlineNotification, Pagination } from "@carbon/react";
import { ConfirmModal, ErrorMessage, notify, Toggle, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import moment from "moment";
import { Helmet } from "react-helmet";
import { Link, useFetcher, useLoaderData, useLocation, useNavigate } from "react-router-dom";
import ProgressBar from "Components/ProgressBar";
import { formatBytes } from "Utils/byteHelper";
import { actionError, isActionError, type ActionError } from "Utils/actionResult";
import { appLink, queryStringOptions } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { Artifact, ArtifactStatus, PaginatedResponse } from "Types";
import queryString from "query-string";
import { useWorkspaceDetailedContext } from "../WorkspaceDetailed";
import styles from "./Artifacts.module.scss";

// Route module for the Artifacts tab of /:workspace/manage (app/routes/manageWorkspaceArtifacts.tsx).
// Follows the Activity.tsx pattern: every filter/pagination value (page/limit/statuses) is read
// off the request URL server-side in the loader and off `location.search` client-side, rather than
// kept in component state - a link to a filtered/paginated view stays shareable and survives a
// refresh. The storage summary reuses the workspace record already on the parent layout route's
// <Outlet context> (its `quotas.currentArtifactStorage`/`maxArtifactStorage`) rather than a
// separate read, and the Workflow column resolves off `workspace.workflows` for the same reason.

const DEFAULT_PAGE = 0;
const DEFAULT_LIMIT = 10;
const PAGE_SIZES = [10, 20, 50, 100];
const AVAILABLE_ONLY = "available";
const AVAILABLE_AND_EXPIRED = "available,expired";

export type ArtifactsLoaderData = {
  artifacts: PaginatedResponse<Artifact> | null;
  errorLoading: boolean;
};

export async function loader({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<ArtifactsLoaderData> {
  const workspace = String(params.workspace);
  const {
    page = DEFAULT_PAGE,
    limit = DEFAULT_LIMIT,
    statuses = AVAILABLE_ONLY,
  } = queryString.parse(new URL(request.url).search, queryStringOptions);

  try {
    const query = queryString.stringify({ page, limit, statuses }, queryStringOptions);
    const response = await serverFetch(request).get(
      serviceUrl.workspace.artifact.getWorkspaceArtifacts({ workspace, query }),
    );
    return { artifacts: response.data, errorLoading: false };
  } catch (error) {
    return { artifacts: null, errorLoading: true };
  }
}

export type ArtifactsActionResult = { intent: "delete" } | ({ intent: "delete" } & ActionError);

export async function action({ params, request }: { params: { workspace?: string }; request: Request }) {
  const workspace = String(params.workspace);
  const formData = await request.formData();
  const artifactId = String(formData.get("artifactId"));

  try {
    await serverFetch(request).delete(
      serviceUrl.workspace.artifact.deleteWorkspaceArtifact({ workspace, artifactId }),
    );
    return { intent: "delete" as const };
  } catch (error) {
    return actionError({
      intent: "delete" as const,
      error: formatErrorMessage({ error, defaultMessage: "Failed to delete this artifact" }),
    });
  }
}

const HEADERS = [
  { header: "Name", key: "name" },
  { header: "Workflow", key: "workflowRef" },
  { header: "Run", key: "workflowRunRef" },
  { header: "Size", key: "size" },
  { header: "Expires", key: "expirationDate" },
  { header: "", key: "delete" },
];

function Artifacts() {
  const { workspace, canEdit } = useWorkspaceDetailedContext();
  const { artifacts, errorLoading } = useLoaderData() as ArtifactsLoaderData;
  const navigate = useNavigate();
  const location = useLocation();
  const fetcher = useFetcher<ArtifactsActionResult>();

  const { limit = DEFAULT_LIMIT, statuses = AVAILABLE_ONLY } = queryString.parse(location.search, queryStringOptions);
  const showExpired = statuses === AVAILABLE_AND_EXPIRED;

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (isActionError(fetcher.data)) {
      notify(<ToastNotification kind="error" title="Something's Wrong" subtitle="Failed to delete this artifact" />);
    } else {
      notify(<ToastNotification kind="success" title="Delete Artifact" subtitle="Artifact successfully deleted" />);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

  function updateSearch(next: Record<string, unknown>) {
    const query = queryString.stringify(
      { limit, statuses, ...queryString.parse(location.search, queryStringOptions), ...next },
      queryStringOptions,
    );
    navigate({ search: `?${query}` });
  }

  function handleToggleExpired(checked: boolean) {
    updateSearch({ statuses: checked ? AVAILABLE_AND_EXPIRED : AVAILABLE_ONLY, page: DEFAULT_PAGE });
  }

  function handlePaginationChange({ page, pageSize }: { page: number; pageSize: number }) {
    // Carbon's Pagination is 1-indexed; the API (like every other paginated route here) is 0-indexed.
    updateSearch({ page: page - 1, limit: pageSize });
  }

  function handleDelete(artifactId: string) {
    fetcher.submit({ intent: "delete", artifactId }, { method: "post" });
  }

  if (errorLoading || !artifacts) {
    return (
      <section aria-label={`${workspace.displayName} Workspace Artifacts`} className={styles.container}>
        <ErrorMessage />
      </section>
    );
  }

  const workflowNameMap = (workspace.workflows ?? []).reduce((acc: Record<string, string>, workflow) => {
    acc[workflow.name] = workflow.displayName;
    return acc;
  }, {});

  const { maxArtifactStorage, currentArtifactStorage } = workspace.quotas;
  const maxArtifactStorageBytes = maxArtifactStorage * 1024 ** 3;
  let storagePercentage = maxArtifactStorageBytes > 0 ? (currentArtifactStorage / maxArtifactStorageBytes) * 100 : 0;
  if (storagePercentage > 100) storagePercentage = 100;

  const { TableContainer, Table, TableHead, TableRow, TableBody, TableCell, TableHeader } = DataTable;

  return (
    <section aria-label={`${workspace.displayName} Workspace Artifacts`} className={styles.container}>
      <Helmet>
        <title>{`Artifacts - ${workspace.displayName}`}</title>
      </Helmet>
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
      <section className={styles.summary}>
        <p className={styles.summaryTitle}>{`${formatBytes(currentArtifactStorage)} of ${maxArtifactStorage} GB used`}</p>
        <ProgressBar
          maxValue={maxArtifactStorage}
          value={storagePercentage}
          coverageBarStyle={{ height: "1rem", width: "20rem" }}
        />
      </section>
      <div className={styles.filtersContainer}>
        <Toggle
          id="artifacts-show-expired"
          label="Show expired"
          labelText="Show expired"
          orientation="horizontal"
          toggled={showExpired}
          onToggle={handleToggleExpired}
        />
      </div>
      {artifacts.content.length > 0 ? (
        <>
          <DataTable
            rows={artifacts.content.map((artifact) => ({ ...artifact }))}
            headers={HEADERS}
            render={({ rows, headers }: any) => (
              <TableContainer>
                <Table>
                  <TableHead>
                    <TableRow>
                      {headers.map((header: { header: string; key: string }) => (
                        <TableHeader key={header.key} id={header.key}>
                          {header.header}
                        </TableHeader>
                      ))}
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {rows.map((row: any) => {
                      const artifact = artifacts.content.find((item) => item.id === row.id) as Artifact;
                      const isExpired = artifact.status === ArtifactStatus.Expired;
                      return (
                        <TableRow key={row.id} className={isExpired ? styles.expiredRow : ""}>
                          <TableCell>
                            <p className={styles.tableTextarea} title={artifact.name}>
                              {artifact.name}
                            </p>
                          </TableCell>
                          <TableCell>
                            <p className={styles.tableTextarea}>
                              {workflowNameMap[artifact.workflowRef] ?? artifact.workflowRef}
                            </p>
                          </TableCell>
                          <TableCell>
                            <Link
                              className={styles.runLink}
                              to={appLink.execution({ workspace: workspace.name, runId: artifact.workflowRunRef })}
                            >
                              View run
                            </Link>
                          </TableCell>
                          <TableCell>
                            <p className={styles.tableTextarea}>{formatBytes(artifact.size)}</p>
                          </TableCell>
                          <TableCell>
                            <p className={styles.tableTextarea}>
                              {isExpired ? "Expired " : "Expires "}
                              {artifact.expirationDate ? moment(artifact.expirationDate).format("YYYY-MM-DD") : "---"}
                            </p>
                          </TableCell>
                          <TableCell>
                            <ConfirmModal
                              modalTrigger={({ openModal }: { openModal: () => void }) => (
                                <Button
                                  kind="danger--ghost"
                                  size="sm"
                                  onClick={openModal}
                                  disabled={!canEdit}
                                  data-testid={`delete-artifact-button-${artifact.id}`}
                                >
                                  Delete
                                </Button>
                              )}
                              affirmativeAction={() => handleDelete(artifact.id)}
                              affirmativeButtonProps={{ kind: "danger" }}
                              affirmativeText="Delete"
                              negativeText="Cancel"
                              title="Are you sure?"
                            >
                              {`Delete "${artifact.name}"? This can't be undone.`}
                            </ConfirmModal>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </TableContainer>
            )}
          />
          <Pagination
            onChange={handlePaginationChange}
            page={artifacts.number + 1}
            pageSize={artifacts.size}
            pageSizes={PAGE_SIZES}
            totalItems={artifacts.totalElements}
          />
        </>
      ) : (
        <p className={styles.empty}>No artifacts found.</p>
      )}
    </section>
  );
}

export default Artifacts;
