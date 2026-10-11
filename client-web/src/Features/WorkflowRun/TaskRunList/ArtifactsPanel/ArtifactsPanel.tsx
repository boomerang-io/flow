import React from "react";
import { Button, ModalBody } from "@carbon/react";
import { ComposedModal, ConfirmModal, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment";
import { useFetcher } from "react-router-dom";
import type { ActionResult } from "Features/WorkflowRun/WorkflowRun";
import { formatBytes } from "Utils/byteHelper";
import { resourceRoute } from "Config/resourceRoutes";
import { isActionError } from "Utils/actionResult";
import { Artifact, ArtifactStatus, TaskRun, WorkflowRun } from "Types";
import styles from "./artifactsPanel.module.scss";

type Props = {
  artifacts: Array<Artifact>;
  workflowRun: WorkflowRun;
  workspace: string;
};

// Resolves an artifact's `taskRunRef` to the name of the task run that uploaded it - the API
// carries only the id (see Types' Artifact doc comment), and WorkflowRun.tasks is the only place
// that id maps to something a person can read.
function uploaderName(taskRunRef: string, tasks: Array<TaskRun>): string {
  return tasks.find((taskRun) => taskRun.id === taskRunRef)?.name ?? "---";
}

/**
 * The "Artifacts" tab of the run view's aside (see ../TaskRunList.tsx, which renders this beside
 * the unchanged Task log tab). One item per artifact, in the same visual language as a task item
 * (TaskRunItem/RunTaskItem.tsx) - a header, label/value pairs, then a row of ghost buttons -
 * rather than a table, matching the run view's existing list rather than introducing a second
 * layout for the same aside.
 */
function ArtifactsPanel({ artifacts, workflowRun, workspace }: Props) {
  const available = artifacts.filter((artifact) => artifact.status !== ArtifactStatus.Expired);
  const expiredCount = artifacts.length - available.length;
  const totalAvailableBytes = available.reduce((total, artifact) => total + (artifact.size ?? 0), 0);

  if (artifacts.length === 0) {
    return (
      <div className={styles.empty}>
        <p>No artifacts for this run.</p>
      </div>
    );
  }

  return (
    <div className={styles.container}>
      <p className={styles.summary}>
        {formatBytes(totalAvailableBytes)} across {available.length} artifact{available.length === 1 ? "" : "s"}
        {expiredCount > 0 ? ` · ${expiredCount} expired` : ""}
      </p>
      <ul className={styles.list}>
        {artifacts.map((artifact) => (
          <ArtifactItem
            key={artifact.id}
            artifact={artifact}
            uploadedBy={uploaderName(artifact.taskRunRef, workflowRun.tasks)}
            workspace={workspace}
            runId={workflowRun.id}
          />
        ))}
      </ul>
    </div>
  );
}

type ItemProps = {
  artifact: Artifact;
  uploadedBy: string;
  workspace: string;
  runId: string;
};

function ArtifactItem({ artifact, uploadedBy, workspace, runId }: ItemProps) {
  const isExpired = artifact.status === ArtifactStatus.Expired;

  return (
    <li className={`${styles.artifactItem} ${isExpired ? styles.expired : ""}`}>
      <section className={styles.header}>
        <div className={styles.title}>
          <p title={artifact.name} data-testid="artifactitem-name">
            {artifact.name}
          </p>
        </div>
      </section>
      <section className={styles.data}>
        <div className={styles.field}>
          <p className={styles.fieldTitle}>Size</p>
          <p className={styles.fieldValue}>{formatBytes(artifact.size)}</p>
        </div>
        <div className={styles.field}>
          <p className={styles.fieldTitle}>Uploaded by</p>
          <p className={styles.fieldValue}>{uploadedBy}</p>
        </div>
        <div className={styles.field}>
          <p className={styles.fieldTitle}>{isExpired ? "Expired" : "Expires"}</p>
          <p className={styles.fieldValue}>
            {artifact.expirationDate ? moment(artifact.expirationDate).format("YYYY-MM-DD hh:mm A") : "---"}
          </p>
        </div>
      </section>
      <section className={`${styles.data} ${styles.actions}`}>
        {!isExpired && (
          <Button
            className={styles.actionButton}
            size="sm"
            kind="ghost"
            href={resourceRoute.artifactDownload({ workspace, runId, name: artifact.name })}
          >
            Download
          </Button>
        )}
        <ComposedModal
          modalHeaderProps={{
            title: "View Details",
            subtitle: artifact.name,
          }}
          modalTrigger={({ openModal }) => (
            <Button className={styles.actionButton} size="sm" kind="ghost" onClick={openModal}>
              View Details
            </Button>
          )}
        >
          {() => <ArtifactDetail artifact={artifact} uploadedBy={uploadedBy} />}
        </ComposedModal>
        <DeleteArtifact artifact={artifact} />
      </section>
    </li>
  );
}

function ArtifactDetail({ artifact, uploadedBy }: { artifact: Artifact; uploadedBy: string }) {
  return (
    <ModalBody>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Size</span>
        <p className={styles.sectionDetail}>
          {formatBytes(artifact.size)} ({artifact.size.toLocaleString()} bytes)
        </p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Content type</span>
        <p className={styles.sectionDetail}>{artifact.contentType || "---"}</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Uploaded by</span>
        <p className={styles.sectionDetail}>{uploadedBy}</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Uploaded</span>
        <p className={styles.sectionDetail}>
          {artifact.creationDate ? moment(artifact.creationDate).format("YYYY-MM-DD hh:mm A") : "---"}
        </p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Retention</span>
        <p className={styles.sectionDetail}>{artifact.retentionDays} days</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>
          {artifact.status === ArtifactStatus.Expired ? "Expired" : "Expires"}
        </span>
        <p className={styles.sectionDetail}>
          {artifact.expirationDate ? moment(artifact.expirationDate).format("YYYY-MM-DD hh:mm A") : "---"}
        </p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>SHA-256</span>
        <p className={`${styles.sectionDetail} ${styles.sha256}`}>{artifact.sha256 || "---"}</p>
      </section>
    </ModalBody>
  );
}

// Posts to the run route's `action` (WorkflowRun.tsx) via the nearest matched route - the same
// technique TaskApprovalModal/ManualTaskModal use - which revalidates the loader (and this list)
// on completion.
function DeleteArtifact({ artifact }: { artifact: Artifact }) {
  const fetcher = useFetcher<ActionResult>();
  const isDeleting = fetcher.state !== "idle";

  // A toast, not an inline notification in the list - matches how every other write on this
  // screen (RunHeader's lifecycle actions, the approval/manual modals) reports its result.
  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "deleteArtifact") {
      return;
    }
    if (isActionError(fetcher.data)) {
      notify(<ToastNotification kind="error" title="Something's Wrong" subtitle="Failed to delete this artifact" />);
    } else {
      notify(<ToastNotification kind="success" title="Delete Artifact" subtitle={`"${artifact.name}" deleted`} />);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

  const handleDelete = () => {
    fetcher.submit({ intent: "deleteArtifact", name: artifact.name }, { method: "post" });
  };

  return (
    <ConfirmModal
      modalTrigger={({ openModal }: { openModal: () => void }) => (
        <Button className={styles.actionButton} size="sm" kind="ghost" onClick={openModal} disabled={isDeleting}>
          Delete
        </Button>
      )}
      affirmativeAction={handleDelete}
      affirmativeButtonProps={{ kind: "danger" }}
      affirmativeText="Delete"
      negativeText="Cancel"
      title="Are you sure?"
    >
      {`Delete "${artifact.name}"? This can't be undone.`}
    </ConfirmModal>
  );
}

export default ArtifactsPanel;
