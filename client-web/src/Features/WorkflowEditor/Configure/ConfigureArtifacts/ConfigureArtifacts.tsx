import React from "react";
import { Button, DataTable } from "@carbon/react";
import { ConfirmModal, ErrorMessage, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment";
import { Link, useFetcher } from "react-router-dom";
import { appLink } from "Config/appConfig";
import { resourceRoute } from "Config/resourceRoutes";
import { formatBytes } from "Utils/byteHelper";
import { isActionError } from "Utils/actionResult";
import type { Artifact } from "Types";
import { useEditorRouteData } from "../../editorRouteData";
import type { EditorActionResult } from "../../editorRoute";
import styles from "./ConfigureArtifacts.module.scss";

/*
 * The Configure > Artifacts panel: the artifacts this workflow keeps between runs (scope
 * `workflow`). The list comes from the editor route's loader (editorRoute.ts reads it only when
 * this panel is addressed, the way the Schedule tab's data is) through useEditorRouteData(), and
 * a delete posts the `deleteKeptArtifact` intent to that same route's action through a bare
 * useFetcher() - the completion revalidates the loader, which refreshes this list. Same table
 * style as Manage Workspace's Artifacts tab (WorkspaceDetailed/Artifacts/Artifacts.tsx).
 */

const HEADERS = [
  { header: "Name", key: "name" },
  { header: "Size", key: "size" },
  { header: "Uploaded by", key: "workflowRunRef" },
  { header: "", key: "delete" },
];

type Props = {
  workspace: string;
};

function ConfigureArtifacts({ workspace }: Props) {
  const keptArtifacts = useEditorRouteData()?.keptArtifacts;
  const fetcher = useFetcher<EditorActionResult>();
  const isDeleting = fetcher.state !== "idle";

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "deleteKeptArtifact") {
      return;
    }
    if (isActionError(fetcher.data)) {
      notify(<ToastNotification kind="error" title="Something's Wrong" subtitle="Failed to delete this artifact" />);
    } else {
      notify(<ToastNotification kind="success" title="Delete Artifact" subtitle={`"${fetcher.data.name}" deleted`} />);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

  function handleDelete(artifact: Artifact) {
    fetcher.submit({ intent: "deleteKeptArtifact", artifactId: artifact.id, name: artifact.name }, { method: "post" });
  }

  // The loader only reads the list when this panel is addressed; while a navigation onto it is
  // still resolving there is nothing to show yet.
  if (!keptArtifacts) {
    return null;
  }

  if (keptArtifacts.artifacts === null) {
    return <ErrorMessage />;
  }

  const artifacts = keptArtifacts.artifacts;

  if (artifacts.length === 0) {
    return (
      <div className={styles.empty} data-testid="configure-artifacts-empty">
        <p>No kept artifacts yet.</p>
        <p>Add an Upload Artifact task with scope Workflow to the canvas and run the workflow to create one.</p>
      </div>
    );
  }

  const { TableContainer, Table, TableHead, TableRow, TableBody, TableCell, TableHeader } = DataTable;

  return (
    <DataTable
      rows={artifacts.map((artifact) => ({ ...artifact }))}
      headers={HEADERS}
      render={({ rows, headers }: any) => (
        <TableContainer className={styles.tableContainer}>
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
                const artifact = artifacts.find((item) => item.id === row.id) as Artifact;
                return (
                  <TableRow key={row.id}>
                    <TableCell>
                      <a
                        className={styles.link}
                        href={resourceRoute.artifactDownload({
                          workspace,
                          runId: artifact.workflowRunRef,
                          name: artifact.name,
                        })}
                        title={artifact.name}
                      >
                        {artifact.name}
                      </a>
                    </TableCell>
                    <TableCell>
                      <p className={styles.tableText}>{formatBytes(artifact.size)}</p>
                    </TableCell>
                    <TableCell>
                      <Link className={styles.link} to={appLink.execution({ workspace, runId: artifact.workflowRunRef })}>
                        View run
                      </Link>
                      <p className={styles.tableSecondary}>
                        {artifact.creationDate ? moment(artifact.creationDate).format("YYYY-MM-DD hh:mm A") : "---"}
                      </p>
                    </TableCell>
                    <TableCell>
                      <ConfirmModal
                        modalTrigger={({ openModal }: { openModal: () => void }) => (
                          <Button
                            kind="danger--ghost"
                            size="sm"
                            onClick={openModal}
                            disabled={isDeleting}
                            data-testid={`delete-kept-artifact-${artifact.id}`}
                          >
                            Delete
                          </Button>
                        )}
                        affirmativeAction={() => handleDelete(artifact)}
                        affirmativeButtonProps={{ kind: "danger" }}
                        affirmativeText="Delete"
                        negativeText="Cancel"
                        title="Are you sure?"
                      >
                        {`Delete "${artifact.name}"? A later run's Download Artifact task will no longer find it. This can't be undone.`}
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
  );
}

export default ConfigureArtifacts;
