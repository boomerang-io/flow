import React from "react";
import { Breadcrumb, BreadcrumbItem, Button, ModalBody, SkeletonPlaceholder, Tag, TextArea } from "@carbon/react";
import { Catalog, CopyFile, StopOutline, Warning, Redo } from "@carbon/react/icons";
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
import OutputPropertiesLog from "Features/WorkflowRun/TaskRunList/TaskRunItem";
import ErrorModal from "Components/ErrorModal";
import { useTeamContext } from "Hooks";
import { appLink } from "Config/appConfig";
import { resolver, serviceUrl } from "Config/servicesConfig";
import { RunStatus, WorkflowCanvas, WorkflowRun } from "Types";
import styles from "./RunHeader.module.scss";

type Props = {
  workflow: WorkflowCanvas;
  workflowRun: WorkflowRun;
  version: number;
  executionViewRedirect: (args: { workflowRunRef: string }) => void;
};

const cancelStatusTypes = [RunStatus.NotStarted, RunStatus.Waiting, RunStatus.Ready, RunStatus.Running];
const retryStatusTypes = [RunStatus.Cancelled, RunStatus.Failed, RunStatus.TimedOut, RunStatus.Invalid];

export default function RunHeader({ workflow, workflowRun, version, executionViewRedirect }: Props) {
  const { team } = useTeamContext();
  const history = useHistory<{ fromUrl: string; fromText: string }>();
  const state = history.location.state;
  const queryClient = useQueryClient();

  const { initiatedByRef, trigger, creationDate, status, id } = workflowRun;
  const displayCancelButton = cancelStatusTypes.includes(status);
  const displayRetryButton = retryStatusTypes.includes(status);

  const { mutateAsync: retryWorkflowRunMutation } = useMutation(resolver.putRetryWorkflowRun, {
    onSuccess: () => queryClient.invalidateQueries(serviceUrl.team.workflowrun.getWorkflowRun({ team: team.name, id })),
  });

  const { mutateAsync: cancelWorkflowRunMutation } = useMutation(resolver.deleteCancelWorkflowRun, {
    onSuccess: () => queryClient.invalidateQueries(serviceUrl.team.workflowrun.getWorkflowRun({ team: team.name, id })),
  });

  const handleRetryWorkflow = async () => {
    try {
      await retryWorkflowRunMutation({ id, team: team.name });
      notify(<ToastNotification kind="success" title="Retry run" subtitle="Retry successful" />);
      executionViewRedirect({ workflowRunRef: id });
    } catch {
      notify(<ToastNotification kind="error" title="Something's wrong" subtitle={`Failed to retry this run`} />);
    }
  };

  const handleCancelWorkflow = async () => {
    try {
      await cancelWorkflowRunMutation({ id, team: team.name });
      notify(<ToastNotification kind="success" title="Cancel run" subtitle="Run successfully cancelled" />);
    } catch {
      notify(<ToastNotification kind="error" title="Something's wrong" subtitle={`Failed to cancel this run`} />);
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
              <Link to={state ? state.fromUrl : appLink.activity({ team: team.name })}>
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
            <dt className={styles.dataTitle}>Team</dt>
            <dd className={styles.dataValue}>{team.displayName ?? "---"}</dd>
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
            {displayRetryButton && (
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
            {displayCancelButton && (
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
  const { team, workflow: workflowRef, runId } = useParams<{ team: string; workflow: string; runId: string }>();
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
