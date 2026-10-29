import React from "react";
import { useFetcher } from "react-router-dom";
import {
  Button,
  FilterableMultiSelect,
  InlineNotification,
  ModalBody,
  ModalFooter,
  RadioButton,
  RadioButtonGroup,
} from "@carbon/react";
import { ComposedModal, Loading, ModalFlowForm, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { NodeType } from "Constants";
import { RetryScope, RunPhase, RunStatus, TaskRun, WorkflowRun } from "Types";
import { isActionError } from "Utils/actionResult";
import { isForeachItem } from "Utils/taskRunHelper";
import type { ActionResult } from "../WorkflowRun";
import styles from "./RetryRunModal.module.scss";

type Props = {
  workflowRun: WorkflowRun;
  isOpen: boolean;
  // Task names preselected under "from selected tasks" - a task row's Retry passes
  // its own; the header passes none.
  initialNodes: Array<string>;
  onClose: () => void;
  executionViewRedirect: ({ workflowRunRef }: { workflowRunRef: string }) => void;
};

// The tasks a retry can start from: the graph's vertices, so never START, END or a for-each item.
function selectableTasks(tasks: Array<TaskRun>) {
  return tasks.filter(
    (taskRun) => taskRun.type !== NodeType.Start && taskRun.type !== NodeType.End && !isForeachItem(taskRun),
  );
}

// "Failed" is read the way the backend reads it: anything that did not finish well re-runs, so the
// option is offered whenever at least one task is in that position.
function hasUnfinishedTask(tasks: Array<TaskRun>) {
  return selectableTasks(tasks).some(
    (taskRun) => taskRun.status !== RunStatus.Succeeded && taskRun.status !== RunStatus.Skipped,
  );
}

const SCOPE_COPY: Record<RetryScope, string> = {
  all: "Everything, from the start",
  failed: "The tasks that did not succeed, and everything after them",
  nodes: "Selected tasks, and everything after them",
};

export default function RetryRunModal({ workflowRun, isOpen, initialNodes, onClose, executionViewRedirect }: Props) {
  const fetcher = useFetcher<ActionResult>();
  const isSubmitting = fetcher.state !== "idle";
  const [scope, setScope] = React.useState<RetryScope>("all");
  const [nodes, setNodes] = React.useState<Array<string>>([]);

  // Opening the modal sets its choices from the entry point: a task row preselects itself under
  // "selected tasks", the header starts from the whole run.
  React.useEffect(() => {
    if (isOpen) {
      setScope(initialNodes.length > 0 ? "nodes" : "all");
      setNodes(initialNodes);
    }
  }, [isOpen, initialNodes]);

  // The fetcher settles asynchronously: on success the page moves to the run the retry created.
  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "retry") {
      return;
    }
    if (isActionError(fetcher.data)) {
      notify(
        <ToastNotification
          kind="error"
          title={fetcher.data.error.title ?? "Retry run"}
          subtitle={fetcher.data.error.message ?? "Failed to retry this run"}
        />,
      );
      return;
    }
    notify(<ToastNotification kind="success" title="Retry run" subtitle="Retry successful" />);
    onClose();
    if (fetcher.data.workflowRunRef) {
      executionViewRedirect({ workflowRunRef: fetcher.data.workflowRunRef });
    }
    // Keyed on the settled result so a re-render never replays the toast or the redirect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

  const tasks = selectableTasks(workflowRun.tasks ?? []);
  const failedAvailable = hasUnfinishedTask(workflowRun.tasks ?? []);
  const isActive = workflowRun.phase !== RunPhase.Completed;
  const needsNodes = scope === "nodes" && nodes.length === 0;
  // The new run gets a fresh shared workspace, so files a carried-over task wrote to the old one are gone.
  const losesWorkspaceFiles = scope !== "all" && Boolean(workflowRun.workspace);

  const handleSubmit = (event: React.SyntheticEvent) => {
    event.preventDefault();
    fetcher.submit(
      { intent: "retry", scope, nodes: JSON.stringify(scope === "nodes" ? nodes : []) },
      { method: "post" },
    );
  };

  return (
    <ComposedModal
      composedModalProps={{ shouldCloseOnOverlayClick: true }}
      isOpen={isOpen}
      modalHeaderProps={{
        title: "Retry run",
        subtitle: "A new run of this workflow. Tasks before the retry point keep their results instead of running again.",
      }}
      modalTrigger={() => null}
      onCloseModal={onClose}
    >
      {() => (
        <ModalFlowForm onSubmit={handleSubmit}>
          <ModalBody>
            {isSubmitting && <Loading />}
            {initialNodes.length > 0 && (
              <p className={styles.from} data-testid="retry-from">
                Starting from <strong>{initialNodes.join(", ")}</strong>: the tasks before it keep their results, and it
                and everything after it run again. Choose another option below to retry differently.
              </p>
            )}
            <RadioButtonGroup
              legendText="Re-run"
              name="retry-scope"
              onChange={(selection: string | number | undefined) => setScope(String(selection) as RetryScope)}
              orientation="vertical"
              valueSelected={scope}
            >
              <RadioButton id="retry-scope-all" labelText={SCOPE_COPY.all} value="all" />
              <RadioButton
                id="retry-scope-failed"
                labelText={SCOPE_COPY.failed}
                value="failed"
                disabled={!failedAvailable}
              />
              <RadioButton id="retry-scope-nodes" labelText={SCOPE_COPY.nodes} value="nodes" />
            </RadioButtonGroup>
            {scope === "nodes" && (
              <div className={styles.nodes}>
                <FilterableMultiSelect
                  id="retry-nodes"
                  key={initialNodes.join(",")}
                  titleText="Tasks to retry from"
                  placeholder="Choose task(s)"
                  items={tasks.map((taskRun) => taskRun.name)}
                  itemToString={(item: string | null) => item ?? ""}
                  filterItems={(items: Array<string>, { inputValue }: { inputValue?: string | null }) =>
                    items.filter((item) => !inputValue || item.toLowerCase().includes(inputValue.toLowerCase()))
                  }
                  initialSelectedItems={initialNodes}
                  onChange={({ selectedItems }: { selectedItems: Array<string> }) => setNodes(selectedItems)}
                  invalid={needsNodes}
                  invalidText="Choose at least one task"
                />
              </div>
            )}
            {losesWorkspaceFiles && (
              <InlineNotification
                className={styles.notice}
                hideCloseButton
                kind="warning"
                lowContrast
                title="Workspace files are not carried over."
                subtitle="The new run starts with an empty workspace, so a re-run task will not find files the carried-over tasks wrote there. Pass those files as artifacts, or re-run everything."
              />
            )}
            {isActive && (
              <InlineNotification
                className={styles.notice}
                hideCloseButton
                kind="warning"
                lowContrast
                title="This run is still active."
                subtitle="It is cancelled when the retry starts, so one lineage never has two live runs."
              />
            )}
          </ModalBody>
          <ModalFooter>
            <Button kind="secondary" onClick={onClose} disabled={isSubmitting}>
              Cancel
            </Button>
            <Button type="submit" data-testid="retry-run-submit" disabled={isSubmitting || needsNodes}>
              {isSubmitting ? "Retrying..." : "Retry run"}
            </Button>
          </ModalFooter>
        </ModalFlowForm>
      )}
    </ComposedModal>
  );
}
