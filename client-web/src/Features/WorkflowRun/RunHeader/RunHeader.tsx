import React from "react";
import { Breadcrumb, BreadcrumbItem, Button, ModalBody, SkeletonPlaceholder, Tag, TextArea } from "@carbon/react";
import { CheckmarkOutline, Catalog, CopyFile, Pause, Play, StopOutline, Warning, Redo } from "@carbon/react/icons";
import {
  ComposedModal,
  ConfirmModal,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  ToastNotification,
  TooltipHover,
  notify,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { capitalize } from "lodash";
import moment from "moment";
import CopyToClipboard from "react-copy-to-clipboard";
import { useMutation, useQueryClient } from "react-query";
import { Link, useHistory, useParams } from "react-router-dom";
import OutputPropertiesLog from "Features/WorkflowRun/TaskRunList/TaskRunItem/OutputPropertiesLog";
import ErrorModal from "Components/ErrorModal";
import { useAppContext, useWorkspaceContext } from "Hooks";
import { appLink } from "Config/appConfig";
import { resolver, serviceUrl } from "Config/servicesConfig";
import { hasPermission } from "Utils/permissionHelper";
import { RunPhase, RunStatus, WorkflowCanvas, WorkflowRun } from "Types";
import styles from "./RunHeader.module.scss";

type Props = {
  workflow: WorkflowCanvas;
  workflowRun: WorkflowRun;
  version: number;
  executionViewRedirect: (args: { workflowRunRef: string }) => void;
};

const cancelStatusTypes = [RunStatus.NotStarted, RunStatus.Waiting, RunStatus.Ready, RunStatus.Running];
const retryStatusTypes = [RunStatus.Cancelled, RunStatus.Failed, RunStatus.TimedOut, RunStatus.Invalid];
const startPhaseTypes = [RunPhase.Pending, RunPhase.Queued];

export default function RunHeader({ workflow, workflowRun, version, executionViewRedirect }: Props) {
  const { workspace } = useWorkspaceContext();
  const { user } = useAppContext();
  const history = useHistory<{ fromUrl: string; fromText: string }>();
  const state = history.location.state;
  const queryClient = useQueryClient();

  const { initiatedByRef, trigger, creationDate, status, phase, paused, id } = workflowRun;
  const canActionWorkflowRun = hasPermission(user, "workflowrun", "action", workspace.name);
  const displayCancelButton = cancelStatusTypes.includes(status);
  const displayRetryButton = retryStatusTypes.includes(status);
  // Start only admits a run still waiting to begin; Finalize only applies once the DAG has
  // completed. An absent `paused` (older backends) is treated as not paused, so Pause is the
  // one shown rather than both or neither.
  const displayStartButton = startPhaseTypes.includes(phase);
  const displayPauseButton = phase === RunPhase.Running && !paused;
  const displayResumeButton = Boolean(paused);
  const displayFinalizeButton = phase === RunPhase.Completed;

  const invalidateWorkflowRun = () =>
    queryClient.invalidateQueries(serviceUrl.workspace.workflowrun.getWorkflowRun({ workspace: workspace.name, id }));

  const { mutateAsync: retryWorkflowRunMutation } = useMutation(resolver.putRetryWorkflowRun, {
    onSuccess: invalidateWorkflowRun,
  });

  const { mutateAsync: cancelWorkflowRunMutation } = useMutation(resolver.deleteCancelWorkflowRun, {
    onSuccess: invalidateWorkflowRun,
  });

  const { mutateAsync: startWorkflowRunMutation } = useMutation(resolver.putStartWorkflowRun, {
    onSuccess: invalidateWorkflowRun,
  });

  const { mutateAsync: pauseWorkflowRunMutation } = useMutation(resolver.putPauseWorkflowRun, {
    onSuccess: invalidateWorkflowRun,
  });

  const { mutateAsync: resumeWorkflowRunMutation } = useMutation(resolver.putResumeWorkflowRun, {
    onSuccess: invalidateWorkflowRun,
  });

  const { mutateAsync: finalizeWorkflowRunMutation } = useMutation(resolver.putFinalizeWorkflowRun, {
    onSuccess: invalidateWorkflowRun,
  });

  const handleRetryWorkflow = async () => {
    try {
      await retryWorkflowRunMutation({ id, workspace: workspace.name });
      notify(<ToastNotification kind="success" title="Retry run" subtitle="Retry successful" />);
      executionViewRedirect({ workflowRunRef: id });
    } catch {
      notify(<ToastNotification kind="error" title="Something's wrong" subtitle={`Failed to retry this run`} />);
    }
  };

  const handleCancelWorkflow = async () => {
    try {
      await cancelWorkflowRunMutation({ id, workspace: workspace.name });
      notify(<ToastNotification kind="success" title="Cancel run" subtitle="Run successfully cancelled" />);
    } catch {
      notify(<ToastNotification kind="error" title="Something's wrong" subtitle={`Failed to cancel this run`} />);
    }
  };

  const handleStartWorkflow = async () => {
    try {
      await startWorkflowRunMutation({ id, workspace: workspace.name });
      notify(<ToastNotification kind="success" title="Start run" subtitle="Run successfully started" />);
    } catch {
      notify(<ToastNotification kind="error" title="Something's wrong" subtitle={`Failed to start this run`} />);
    }
  };

  const handlePauseWorkflow = async () => {
    try {
      await pauseWorkflowRunMutation({ id, workspace: workspace.name });
      notify(<ToastNotification kind="success" title="Pause run" subtitle="Run successfully paused" />);
    } catch {
      notify(<ToastNotification kind="error" title="Something's wrong" subtitle={`Failed to pause this run`} />);
    }
  };

  const handleResumeWorkflow = async () => {
    try {
      await resumeWorkflowRunMutation({ id, workspace: workspace.name });
      notify(<ToastNotification kind="success" title="Resume run" subtitle="Run successfully resumed" />);
    } catch {
      notify(<ToastNotification kind="error" title="Something's wrong" subtitle={`Failed to resume this run`} />);
    }
  };

  const handleFinalizeWorkflow = async () => {
    try {
      await finalizeWorkflowRunMutation({ id, workspace: workspace.name });
      notify(<ToastNotification kind="success" title="Finalize run" subtitle="Run successfully finalized" />);
    } catch {
      notify(<ToastNotification kind="error" title="Something's wrong" subtitle={`Failed to finalize this run`} />);
    }
  };

  return (
    <Header
      className={styles.container}
      nav={
        <div className={styles.headerNav}>
          <Breadcrumb noTrailingSlash>
            <BreadcrumbItem>
              <Link to={appLink.home()}>Home</Link>
            </BreadcrumbItem>
            <BreadcrumbItem>
              <Link to={state ? state.fromUrl : appLink.activity({ workspace: workspace.name })}>
                {state ? capitalize(state.fromText) : "Activity"}
              </Link>
            </BreadcrumbItem>
            <BreadcrumbItem isCurrentPage>
              {!workflow?.name ? (
                <SkeletonPlaceholder className={styles.workflowNameSkeleton} />
              ) : (
                <p>{workflow.name}</p>
              )}
            </BreadcrumbItem>
          </Breadcrumb>
          {workflow && (
            <ComposedModal
              composedModalProps={{ shouldCloseOnOverlayClick: true }}
              modalHeaderProps={{
                title: "Advanced detail",
                subtitle:
                  "Use the following to dive deeper and debug the run. Tip: copy the commands into your local terminal and add the namespace.",
              }}
              modalTrigger={({ openModal }) => (
                <TooltipHover direction="right" content="Advanced detail">
                  <button className={styles.workflowAdvancedDetailTrigger} onClick={openModal}>
                    <Catalog />
                  </button>
                </TooltipHover>
              )}
            >
              {() => <WorkflowAdvancedDetail workflow={workflow} />}
            </ComposedModal>
          )}
        </div>
      }
      header={
        <div style={{ display: "flex" }}>
          <HeaderTitle>Activity detail</HeaderTitle>
          {Boolean(paused) && (
            <Tag className={styles.pausedTag} type="gray" data-testid="paused-indicator">
              <Pause style={{ marginRight: "0.5rem" }} />
              Paused
            </Tag>
          )}
          {Boolean(workflowRun.statusMessage) && (
            <ComposedModal
              composedModalProps={{ shouldCloseOnOverlayClick: true }}
              modalHeaderProps={{ title: "Run Error" }}
              modalTrigger={({ openModal }) => (
                <Button
                  className={styles.workflowErrorTrigger}
                  kind={"ghost"}
                  onClick={openModal}
                  renderIcon={Warning}
                  size="sm"
                >
                  View Run Error
                </Button>
              )}
            >
              {() => <ErrorModal errorCode={workflowRun.status} errorMessage={workflowRun.statusMessage ?? ""} />}
            </ComposedModal>
          )}
        </div>
      }
      actions={
        <div className={styles.content}>
          {workflowRun.results && Object.keys(workflowRun.results).length > 0 && (
            <div className={styles.workflowOutputLog}>
              <OutputPropertiesLog isOutput taskName={workflowRun.workflowName} results={workflowRun.results} />
            </div>
          )}
          <dl className={styles.data}>
            <dt className={styles.dataTitle}>Workspace</dt>
            <dd className={styles.dataValue}>{workspace.displayName ?? "---"}</dd>
          </dl>
          <dl className={styles.data}>
            <dt className={styles.dataTitle}>Version</dt>
            <dd className={styles.dataValue}>{version ?? "---"}</dd>
          </dl>
          <dl className={styles.data}>
            <dt className={styles.dataTitle}>Initiated by</dt>
            {initiatedByRef ? (
              <dd className={styles.dataValue}>{initiatedByRef}</dd>
            ) : (
              <dd aria-label="robot" aria-hidden={false} role="img">
                {"🤖"}
              </dd>
            )}
          </dl>
          <dl className={styles.data}>
            <dt className={styles.dataTitle}>Trigger</dt>
            <dd className={styles.dataValue}>{trigger}</dd>
          </dl>
          <dl className={styles.data}>
            <dt className={styles.dataTitle}>Start time</dt>
            <dd className={styles.dataValue}>{moment(creationDate).format("YYYY-MM-DD hh:mm A")}</dd>
          </dl>
          <dl className={styles.dataButton}>
            {canActionWorkflowRun && displayStartButton && (
              <ConfirmModal
                affirmativeAction={handleStartWorkflow}
                children="Are you sure? This will start execution of the queued Workflow run."
                title="Start run"
                modalTrigger={({ openModal }) => (
                  <Button
                    className={styles.cancelRun}
                    data-testid="start-run"
                    kind="primary"
                    iconDescription="Start run"
                    onClick={openModal}
                    renderIcon={Play}
                    size="sm"
                  >
                    Start run
                  </Button>
                )}
              />
            )}
            {canActionWorkflowRun && displayRetryButton && (
              <ConfirmModal
                affirmativeAction={handleRetryWorkflow}
                children="Are you sure? A new execution of this Workflow will be started with all the same parameters."
                title="Retry run"
                modalTrigger={({ openModal }) => (
                  <Button
                    className={styles.cancelRun}
                    data-testid="cancel-run"
                    kind="primary"
                    iconDescription="Retry run"
                    onClick={openModal}
                    renderIcon={Redo}
                    size="sm"
                  >
                    Retry run
                  </Button>
                )}
              />
            )}
            {canActionWorkflowRun && displayPauseButton && (
              <ConfirmModal
                affirmativeAction={handlePauseWorkflow}
                children="Are you sure? This blocks new tasks from starting. Tasks already claimed, running, or ready continue to completion and still time out on their own deadline - this does not freeze the run."
                title="Pause run"
                modalTrigger={({ openModal }) => (
                  <TooltipHover
                    direction="top"
                    content="Blocks new tasks from starting; work already in flight runs to completion."
                  >
                    <Button
                      className={styles.cancelRun}
                      data-testid="pause-run"
                      kind="tertiary"
                      iconDescription="Pause run"
                      onClick={openModal}
                      renderIcon={Pause}
                      size="sm"
                    >
                      Pause run
                    </Button>
                  </TooltipHover>
                )}
              />
            )}
            {canActionWorkflowRun && displayResumeButton && (
              <ConfirmModal
                affirmativeAction={handleResumeWorkflow}
                children="Are you sure? If this run is paused, resuming will allow new tasks to start again."
                title="Resume run"
                modalTrigger={({ openModal }) => (
                  <Button
                    className={styles.cancelRun}
                    data-testid="resume-run"
                    kind="tertiary"
                    iconDescription="Resume run"
                    onClick={openModal}
                    renderIcon={Play}
                    size="sm"
                  >
                    Resume run
                  </Button>
                )}
              />
            )}
            {canActionWorkflowRun && displayFinalizeButton && (
              <ConfirmModal
                affirmativeAction={handleFinalizeWorkflow}
                children="Are you sure? This will mark the completed run as finalized."
                title="Finalize run"
                modalTrigger={({ openModal }) => (
                  <Button
                    className={styles.cancelRun}
                    data-testid="finalize-run"
                    kind="tertiary"
                    iconDescription="Finalize run"
                    onClick={openModal}
                    renderIcon={CheckmarkOutline}
                    size="sm"
                  >
                    Finalize run
                  </Button>
                )}
              />
            )}
            {canActionWorkflowRun && displayCancelButton && (
              <ConfirmModal
                affirmativeAction={handleCancelWorkflow}
                affirmativeButtonProps={{ kind: "danger" }}
                children="Are you sure? Once a workflow is cancelled it will stop executing."
                title="Cancel run"
                modalTrigger={({ openModal }) => (
                  <Button
                    className={styles.cancelRun}
                    data-testid="cancel-run"
                    kind="danger--tertiary"
                    iconDescription="Cancel run"
                    onClick={openModal}
                    renderIcon={StopOutline}
                    size="sm"
                  >
                    Cancel run
                  </Button>
                )}
              />
            )}
          </dl>
        </div>
      }
    />
  );
}

function WorkflowAdvancedDetail({ workflow }: { workflow: WorkflowCanvas }) {
  const { workspace, workflow: workflowRef, runId } = useParams<{ workspace: string; workflow: string; runId: string }>();
  const [copyTokenText, setCopyTokenText] = React.useState("Copy");

  const labelTexts = [`boomerang.io/workflow-ref=${workflowRef}`, `boomerang.io/workflowrun-ref=${runId}`];

  if (Array.isArray(workflow.labels) && workflow.labels.length > 0) {
    workflow.labels.forEach((label) => {
      labelTexts.push(`${label.key}=${label.value}`);
    });
  }

  const kubernetesCommand = `kubectl get pods -l ${labelTexts.join(",")}`;
  const tektonCommand = `tkn tr list --label ${labelTexts.join(",")}`;

  return (
    <ModalBody>
      <div>Use this information to debug the run using the Tekton CLI.</div>
      <h1 className={styles.detailHeading} style={{ marginTop: "0rem" }}>
        Labels
      </h1>
      <div className={styles.workflowLabels}>
        {labelTexts.map((label, index) => (
          <Tag key={`${label}-${index}`} className={styles.workflowLabelBubble} type="teal">
            {label}
          </Tag>
        ))}
      </div>
      <h1 className={styles.detailHeading}>Tekton Information</h1>
      <div>Use this information to debug the run using the Tekton CLI.</div>
      <div className={styles.kubernetes}>
        <TextArea labelText="" readOnly value={tektonCommand} />
        <TooltipHover direction="top" content={copyTokenText} hideOnClick={false}>
          <div className={styles.kubernetesCopyContainer}>
            <CopyToClipboard text={tektonCommand}>
              <Button
                className={styles.kubernetesCopy}
                iconDescription="copy-kubernetes"
                kind="ghost"
                onClick={() => setCopyTokenText("Copied!")}
                onMouseLeave={() => setCopyTokenText("Copy")}
                renderIcon={CopyFile}
                size="sm"
              />
            </CopyToClipboard>
          </div>
        </TooltipHover>
      </div>
      <h1 className={styles.detailHeading}>Kubernetes Information</h1>
      <div>Use this information to debug the run using the Kubernetes CLI.</div>
      <div className={styles.kubernetes}>
        <TextArea labelText="" readOnly value={kubernetesCommand} />
        <TooltipHover direction="top" content={copyTokenText} hideOnClick={false}>
          <div className={styles.kubernetesCopyContainer}>
            <CopyToClipboard text={kubernetesCommand}>
              <Button
                className={styles.kubernetesCopy}
                iconDescription="copy-kubernetes"
                kind="ghost"
                onClick={() => setCopyTokenText("Copied!")}
                onMouseLeave={() => setCopyTokenText("Copy")}
                renderIcon={CopyFile}
                size="sm"
              />
            </CopyToClipboard>
          </div>
        </TooltipHover>
      </div>
    </ModalBody>
  );
}
