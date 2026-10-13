import React from "react";
import { Breadcrumb, BreadcrumbItem, Button, ModalBody, OverflowMenu, OverflowMenuItem, SkeletonPlaceholder, Tag, TextArea } from "@carbon/react";
import { CopyFile, Pause, Play, Warning, Redo } from "@carbon/react/icons";
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
import { Link, useFetcher, useLocation } from "react-router-dom";
import OutputPropertiesLog from "Features/WorkflowRun/TaskRunList/TaskRunItem/OutputPropertiesLog";
import ErrorModal from "Components/ErrorModal";
import { useAppContext, useWorkspaceContext } from "Hooks";
import { appLink } from "Config/appConfig";
import { hasPermission } from "Utils/permissionHelper";
import dateHelper, { getSimplifiedDuration } from "Utils/dateHelper";
import { ExecutionStatusCopy, executionStatusIcon } from "Constants";
import { RunPhase, RunStatus, WorkflowCanvas, WorkflowRun } from "Types";
import type { ActionResult, RunActionIntent } from "../WorkflowRun";
import { isActionError } from "Utils/actionResult";
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
// The run's status tag takes the colour its tasks use: teal done, red failed or stopped, blue in
// progress, gray not run.
const statusTagType: Partial<Record<RunStatus, "teal" | "red" | "blue" | "gray">> = {
  [RunStatus.Succeeded]: "teal",
  [RunStatus.Failed]: "red",
  [RunStatus.Cancelled]: "red",
  [RunStatus.TimedOut]: "red",
  [RunStatus.Invalid]: "red",
  [RunStatus.Running]: "blue",
  [RunStatus.Ready]: "blue",
  [RunStatus.Waiting]: "blue",
  [RunStatus.NotStarted]: "gray",
  [RunStatus.Skipped]: "gray",
};

// Copy for the toast each transition raises, keyed by the route action's intent - the five
// handlers below were otherwise identical mutate/notify/notify blocks.
const TRANSITION_COPY: Record<
  Exclude<RunActionIntent, "action" | "deleteArtifact">,
  { title: string; success: string; failure: string }
> = {
  retry: { title: "Retry run", success: "Retry successful", failure: "Failed to retry this run" },
  cancel: { title: "Cancel run", success: "Run successfully cancelled", failure: "Failed to cancel this run" },
  start: { title: "Start run", success: "Run successfully started", failure: "Failed to start this run" },
  pause: { title: "Pause run", success: "Run successfully paused", failure: "Failed to pause this run" },
  resume: { title: "Resume run", success: "Run successfully resumed", failure: "Failed to resume this run" },
};

export default function RunHeader({ workflow, workflowRun, version, executionViewRedirect }: Props) {
  const { workspace } = useWorkspaceContext();
  const { user } = useAppContext();
  const location = useLocation();
  const state: { fromUrl: string; fromText: string } | null = location.state;
  // Submits to this route's own `action` (see WorkflowRun.tsx). A completed fetcher submission
  // revalidates the route loader, which is what replaces the
  // queryClient.invalidateQueries(getWorkflowRun) each of these mutations used to run onSuccess.
  const fetcher = useFetcher<ActionResult>();

  const { initiatedByRef, initiatedByWorkflowRunRef, trigger, creationDate, status, phase, paused, id, workflowName } =
    workflowRun;
  // Ruled design (#359, Option A): a schedule-fired run stamps the firing Schedule's id into the
  // existing initiatedByRef field (mirrors the retry path's convention). The Schedules page has
  // no per-schedule focus route, so this deep-links its existing workflow filter instead of
  // building one - see appLink.schedulesForWorkflow.
  const isScheduleTriggered = trigger === "schedule" && Boolean(initiatedByRef);
  // A child run: a runworkflow task started it, so initiatedByRef is that TaskRun's id. The run
  // that owns it comes back on initiatedByWorkflowRunRef, which is what the parent link needs.
  const isTaskTriggered = trigger === "task" && Boolean(initiatedByWorkflowRunRef);
  const canActionWorkflowRun = hasPermission(user, "workflowrun", "action", workspace.name);
  const displayCancelButton = cancelStatusTypes.includes(status);
  const displayRetryButton = retryStatusTypes.includes(status);
  // Start only admits a run still waiting to begin. An absent `paused` (older backends) is
  // treated as not paused, so Pause is the one shown rather than both or neither.
  const displayStartButton = startPhaseTypes.includes(phase);
  const displayPauseButton = phase === RunPhase.Running && !paused;
  const displayResumeButton = Boolean(paused);
  // Workspace is the fact that gives way when a primary run action needs the room.
  const showsPrimaryAction =
    canActionWorkflowRun && (displayStartButton || displayRetryButton || displayPauseButton || displayResumeButton);
  // The overflow's items open these modals, which cannot sit inside a menu item themselves.
  const [openModal, setOpenModal] = React.useState<"advanced" | "cancel" | null>(null);
  // "in 21s" once finished, "for 2m 10s" while running, nothing before it starts.
  const runDuration =
    phase === RunPhase.Completed && typeof workflowRun.duration === "number"
      ? `in ${getSimplifiedDuration(workflowRun.duration / 1000)}`
      : status === RunStatus.Running && workflowRun.startTime
        ? `for ${dateHelper.durationFromThenToNow(workflowRun.startTime)}`
        : "";

  // The fetcher settles asynchronously, so the toast is raised from an effect once the result
  // lands rather than from an awaited mutate call. Retry additionally redirects to the run it
  // just created, which is why the redirect fires here too.
  React.useEffect(() => {
    if (
      fetcher.state !== "idle" ||
      !fetcher.data ||
      fetcher.data.intent === "action" ||
      fetcher.data.intent === "deleteArtifact"
    ) {
      return;
    }
    const { intent } = fetcher.data;
    const copy = TRANSITION_COPY[intent];
    if (!isActionError(fetcher.data)) {
      notify(<ToastNotification kind="success" title={copy.title} subtitle={copy.success} />);
      if (intent === "retry") {
        executionViewRedirect({ workflowRunRef: id });
      }
    } else {
      notify(
        <ToastNotification
          kind="error"
          title={fetcher.data.error.title ?? "Something's wrong"}
          subtitle={fetcher.data.error.message ?? copy.failure}
        />,
      );
    }
    // `executionViewRedirect`/`id` are stable for a given run; keying the effect on the fetcher
    // result alone stops a re-render from replaying the same toast.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

  const submitTransition = (intent: Exclude<RunActionIntent, "action">) => fetcher.submit({ intent }, { method: "post" });

  const handleRetryWorkflow = () => submitTransition("retry");
  const handleCancelWorkflow = () => submitTransition("cancel");
  const handleStartWorkflow = () => submitTransition("start");
  const handlePauseWorkflow = () => submitTransition("pause");
  const handleResumeWorkflow = () => submitTransition("resume");

  return (
    <Header
      className={styles.container}
      nav={
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
            <p>Activity detail</p>
          </BreadcrumbItem>
        </Breadcrumb>
      }
      header={
        <div className={styles.titleRow}>
          {/* A page inside one run is titled with its workflow's name; the breadcrumb carries the route. */}
          {!workflow?.name ? (
            <SkeletonPlaceholder className={styles.workflowNameSkeleton} />
          ) : (
            <HeaderTitle title={workflow.displayName ?? workflow.name}>{workflow.displayName ?? workflow.name}</HeaderTitle>
          )}
          {Boolean(paused) && (
            <Tag className={styles.pausedTag} type="gray" data-testid="paused-indicator">
              <Pause style={{ marginRight: "0.5rem" }} />
              Paused
            </Tag>
          )}
        </div>
      }
      actions={
        <div className={styles.content}>
          <dl className={styles.data}>
            <dt className={styles.dataTitle}>Status</dt>
            <dd className={styles.dataValue}>
              <Tag
                className={styles.statusTag}
                data-testid="run-status"
                renderIcon={executionStatusIcon[status]}
                type={statusTagType[status] ?? "gray"}
              >
                <strong>{ExecutionStatusCopy[status] ?? status}</strong>
                {runDuration ? ` ${runDuration}` : null}
              </Tag>
            </dd>
          </dl>
          {!showsPrimaryAction && (
            <dl className={styles.data}>
              <dt className={styles.dataTitle}>Workspace</dt>
              <dd className={styles.dataValue}>{workspace.displayName ?? "---"}</dd>
            </dl>
          )}
          <dl className={styles.data}>
            <dt className={styles.dataTitle}>Version</dt>
            <dd className={styles.dataValue}>{version ?? "---"}</dd>
          </dl>
          <dl className={styles.data}>
            <dt className={styles.dataTitle}>Initiated by</dt>
            {isScheduleTriggered ? (
              <dd className={styles.dataValue}>
                <Link
                  to={appLink.schedulesForWorkflow({ workspace: workspace.name, workflow: workflowName })}
                  data-testid="initiated-by-schedule-link"
                >
                  {initiatedByRef}
                </Link>
              </dd>
            ) : isTaskTriggered ? (
              <dd className={styles.dataValue}>
                <Link
                  to={appLink.execution({ workspace: workspace.name, runId: initiatedByWorkflowRunRef as string })}
                  data-testid="initiated-by-parent-run-link"
                >
                  {initiatedByWorkflowRunRef}
                </Link>
              </dd>
            ) : initiatedByRef ? (
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
          {/* Inside to outside: the run error, View results, the one primary run action, then the overflow. */}
          <div className={styles.controls}>
            {Boolean(workflowRun.statusMessage) && (
              <ComposedModal
                composedModalProps={{ shouldCloseOnOverlayClick: true }}
                modalHeaderProps={{ title: "Run Error" }}
                modalTrigger={({ openModal }) => (
                  <Button
                    className={styles.runErrorButton}
                    hasIconOnly
                    iconDescription="View run error"
                    kind="ghost"
                    onClick={openModal}
                    renderIcon={Warning}
                    size="md"
                    tooltipPosition="bottom"
                  />
                )}
              >
                {() => <ErrorModal errorCode={workflowRun.status} errorMessage={workflowRun.statusMessage ?? ""} />}
              </ComposedModal>
            )}
            {workflowRun.results && Object.keys(workflowRun.results).length > 0 && (
              <OutputPropertiesLog isOutput taskName={workflowRun.workflowName} results={workflowRun.results} />
            )}
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
                    size="md"
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
                    size="md"
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
                      kind="primary"
                      iconDescription="Pause run"
                      onClick={openModal}
                      renderIcon={Pause}
                      size="md"
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
                    kind="primary"
                    iconDescription="Resume run"
                    onClick={openModal}
                    renderIcon={Play}
                    size="md"
                  >
                    Resume run
                  </Button>
                )}
              />
            )}
            <OverflowMenu aria-label="More run actions" align="left" flipped iconDescription="More run actions" size="md">
              <OverflowMenuItem
                data-testid="advanced-detail-trigger"
                itemText="Advanced detail"
                onClick={() => setOpenModal("advanced")}
              />
              {canActionWorkflowRun && displayCancelButton && (
                <OverflowMenuItem
                  data-testid="cancel-run"
                  hasDivider
                  isDelete
                  itemText="Cancel run"
                  onClick={() => setOpenModal("cancel")}
                />
              )}
            </OverflowMenu>
          </div>
          {workflow && (
            <ComposedModal
              composedModalProps={{ shouldCloseOnOverlayClick: true }}
              isOpen={openModal === "advanced"}
              modalHeaderProps={{
                title: "Advanced detail",
                subtitle:
                  "Use the following to dive deeper and debug the run. Tip: copy the commands into your local terminal and add the namespace.",
              }}
              modalTrigger={() => null}
              onCloseModal={() => setOpenModal(null)}
            >
              {() => <WorkflowAdvancedDetail workflow={workflow} workflowRun={workflowRun} />}
            </ComposedModal>
          )}
          <ConfirmModal
            affirmativeAction={handleCancelWorkflow}
            affirmativeButtonProps={{ kind: "danger" }}
            children="Are you sure? Once a workflow is cancelled it will stop executing."
            isOpen={openModal === "cancel"}
            onCloseModal={() => setOpenModal(null)}
            title="Cancel run"
          />
        </div>
      }
    />
  );
}

function WorkflowAdvancedDetail({
  workflow,
  workflowRun,
}: {
  workflow: WorkflowCanvas;
  workflowRun: WorkflowRun;
}) {
  const [copyTokenText, setCopyTokenText] = React.useState("Copy");

  // These are the labels service-agent stamps on the Tekton/Kubernetes resources it creates
  // (KubeHelperService#getLabels: "boomerang.io/workflow-ref" carries the workflow ref,
  // "boomerang.io/workflowrun-ref" the run id), so the two CLI commands below only select
  // anything when the values match what the agent wrote.
  //
  // Both come off the run. `boomerang.io/workflow-ref` used to read a `:workflow` route param,
  // but this page's route is `/:workspace/activity/:runId` (app/routes.ts) and has no such
  // param - so it rendered a literal `boomerang.io/workflow-ref=undefined` into the labels and
  // into both copyable commands. A ref that is somehow absent now drops its label rather than
  // emitting "undefined" into a command the user is invited to paste into a terminal.
  const labelTexts = [
    workflowRun.workflowRef ? `boomerang.io/workflow-ref=${workflowRun.workflowRef}` : null,
    workflowRun.id ? `boomerang.io/workflowrun-ref=${workflowRun.id}` : null,
  ].filter((label): label is string => label !== null);

  // Workflow labels are a Record<string, string> (Types.Workflow#labels), not the array of
  // { key, value } pairs this used to test for with Array.isArray - which never matched, so the
  // workflow's own labels were silently dropped from both commands.
  Object.entries(workflow.labels ?? {}).forEach(([key, value]) => {
    labelTexts.push(`${key}=${value}`);
  });

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
