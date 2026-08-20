import { Button, ModalBody } from "@carbon/react";
import { ArrowRight } from "@carbon/react/icons";
import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment";
import ReactMarkdown from "react-markdown";
import dateHelper from "Utils/dateHelper";
import { ExecutionStatusCopy, NodeType, executionStatusIcon } from "Constants";
import { Action, RunPhase, RunStatus, SimpleApprover, TaskRun, WorkflowRun } from "Types";
import ManualTaskModal from "./ManualTaskModal";
import PropertiesTable from "./PropertiesTable";
import ResultsTable from "./ResultsTable";
import TaskApprovalModal from "./TaskApprovalModal";
import TaskExecutionLog from "./TaskRunLog";
import styles from "./runTaskItem.module.scss";

const logTaskTypes = ["customtask", "template", "script"];
const logStatusTypes = [RunStatus.Succeeded, RunStatus.Failed, RunStatus.Running];

type Props = {
  taskRun: TaskRun;
  workflowRun: WorkflowRun;
  // The Action record behind an `approval`/`manual` task, when this task has one. Approver
  // names, comments, verdicts and dates live only on the Action (see the backend's
  // ActionEntity/Actioner) - the TaskRun itself carries nothing but an `actionRef` result
  // pointing at it, so the run route's loader resolves it and hands it down here rather than
  // every list item fetching its own.
  action?: Action;
  executionViewRedirect: ({ workflowRunRef }: { workflowRunRef: string }) => void;
};

function RunTaskItem({ taskRun, workflowRun, action, executionViewRedirect }: Props) {
  const Icon = executionStatusIcon[taskRun.status];
  const statusClassName = styles[taskRun.status];

  const calculatedDuration = taskRun.duration
    ? dateHelper.timeMillisecondsToTimeUnit(taskRun.duration)
    : dateHelper.durationFromThenToNow(taskRun.startTime) || "---";

  return (
    <li key={taskRun.name} id={`task-${taskRun.name}`} className={`${styles.taskitem} ${statusClassName}`}>
      <div className={styles.progressBar} />
      <section className={styles.header}>
        <div className={styles.title}>
          <Icon aria-label={taskRun.status} className={styles.taskIcon} />
          <p title={taskRun.name} data-testid="taskitem-name">
            {taskRun.name}
          </p>
        </div>
        <div className={`${styles.status} ${statusClassName}`}>
          <Icon aria-label={taskRun.status} className={styles.statusIcon} />
          <p>{ExecutionStatusCopy[taskRun.status]}</p>
        </div>
      </section>
      <section className={styles.data}>
        <div className={styles.time}>
          <p className={styles.timeTitle}>Start time</p>
          <time className={styles.timeValue}>
            {taskRun.startTime ? moment(taskRun.startTime).format("hh:mm:ss A") : "---"}
          </time>
        </div>
        <div className={styles.time}>
          <p className={styles.timeTitle}>Duration</p>
          <time className={styles.timeValue}>{calculatedDuration}</time>
        </div>
      </section>
      <section className={styles.data}>
        <ComposedModal
          composedModalProps={{
            containerClassName: styles.actionManualTaskModalContainer,
          }}
          modalHeaderProps={{
            title: "View Details",
            subtitle: taskRun.name,
          }}
          modalTrigger={({ openModal }) => (
            <Button className={styles.modalTrigger} size="sm" kind="ghost" onClick={openModal}>
              View Details
            </Button>
          )}
        >
          {() => <TaskRunDetail taskRun={taskRun} />}
        </ComposedModal>
        {((taskRun.status === RunStatus.Cancelled && taskRun.duration > 0) ||
          (logTaskTypes.includes(taskRun.type) && logStatusTypes.includes(taskRun.status))) && (
          <TaskExecutionLog taskrunId={taskRun.id} taskName={taskRun.name} />
        )}
        {taskRun.status === RunStatus.Waiting && taskRun.type === NodeType.Approval && (
          <ComposedModal
            modalHeaderProps={{
              title: "Action Manual Approval",
              subtitle: taskRun.name,
            }}
            modalTrigger={({ openModal }) => (
              <Button size="sm" kind="ghost" onClick={openModal}>
                Action
              </Button>
            )}
          >
            {({ closeModal }) => (
              <TaskApprovalModal
                actionId={taskRun.results.find((result) => result.name === "actionRef")?.value}
                closeModal={closeModal}
                workflowRunId={workflowRun.id}
              />
            )}
          </ComposedModal>
        )}
        {taskRun.status === RunStatus.Waiting && taskRun.type === NodeType.Manual && (
          <ComposedModal
            composedModalProps={{
              containerClassName: styles.actionManualTaskModalContainer,
            }}
            modalHeaderProps={{
              title: "Action Manual Task",
              subtitle: taskRun.name,
            }}
            modalTrigger={({ openModal }) => (
              <Button size="sm" kind="ghost" onClick={openModal}>
                Action
              </Button>
            )}
          >
            {({ closeModal }) => (
              <ManualTaskModal
                actionId={taskRun.results.find((result) => result.name === "actionRef")?.value}
                closeModal={closeModal}
                instructions={taskRun.params.find((param) => param.name === "instructions")?.value}
                workflowRunId={workflowRun.id}
              />
            )}
          </ComposedModal>
        )}
        {taskRun.type === NodeType.RunWorkflow && taskRun.id && taskRun.workflowRef && (
          <Button
            kind="ghost"
            size="sm"
            onClick={() =>
              executionViewRedirect({
                workflowRunRef: taskRun.results.find((result) => result.name === "workflowRunRef")?.value ?? "",
              })
            }
            renderIcon={ArrowRight}
          >
            View Activity
          </Button>
        )}
        {taskRun.type === NodeType.Approval && taskRun.phase === RunPhase.Completed && (
          <ComposedModal
            composedModalProps={{
              containerClassName: styles.approvalResultsModalContainer,
              shouldCloseOnOverlayClick: true,
            }}
            modalHeaderProps={{
              title: "Manual Approval details",
            }}
            modalTrigger={({ openModal }) => (
              <Button size="sm" kind="ghost" onClick={openModal}>
                View Action
              </Button>
            )}
          >
            {() => <ApprovalResult taskRun={taskRun} action={action} />}
          </ComposedModal>
        )}
        {taskRun.type === NodeType.Manual && taskRun.phase === RunPhase.Completed && (
          <ComposedModal
            composedModalProps={{
              containerClassName: styles.approvalResultsModalContainer,
              shouldCloseOnOverlayClick: true,
            }}
            modalHeaderProps={{
              title: "Manual Task details",
            }}
            modalTrigger={({ openModal }) => (
              <Button size="sm" kind="ghost" onClick={openModal}>
                View Action
              </Button>
            )}
          >
            {() => <ManualResult taskRun={taskRun} action={action} />}
          </ComposedModal>
        )}
      </section>
    </li>
  );
}

export default RunTaskItem;

// An Action is actioned by potentially MANY approvers (`numberOfApprovers` on the backend
// entity), each recording their own verdict, comment and timestamp - so every actioner is
// rendered with its own status rather than one shared verdict for the whole Action.
// `approverName`/`approverEmail` are resolved server-side from `approverId` and are absent
// whenever that lookup fails (or no principal was recorded, e.g. `flow.security.enabled=false`),
// so neither is assumed present.
function approverLabel({ approverName, approverEmail }: SimpleApprover) {
  if (approverName && approverEmail) {
    return `${approverName} (${approverEmail})`;
  }
  return approverName || approverEmail || "Unknown approver";
}

function ActionersList({ actioners, showVerdict }: { actioners: SimpleApprover[]; showVerdict: boolean }) {
  return (
    <ul className={styles.actionerList}>
      {actioners.map((actioner, index) => (
        <li className={styles.actioner} key={`${actioner.approverId ?? "unknown"}-${index}`}>
          <p className={styles.sectionDetail}>{approverLabel(actioner)}</p>
          <p className={styles.actionerMeta}>
            {showVerdict ? `${actioner.approved ? "Approved" : "Rejected"} · ` : ""}
            {actioner.date ? moment(actioner.date).format("YYYY-MM-DD hh:mm A") : "---"}
          </p>
          {actioner.comments && <p className={styles.sectionDetail}>{actioner.comments}</p>}
        </li>
      ))}
    </ul>
  );
}

interface ApprovalResultProps {
  taskRun: TaskRun;
  action?: Action;
}

function ApprovalResult({ taskRun, action }: ApprovalResultProps) {
  const actioners = action?.actioners ?? [];
  return (
    <ModalBody>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Approval Status</span>
        <p className={styles.sectionDetail}>{taskRun.status}</p>
      </section>
      {action && (
        <section className={styles.detailedSection}>
          <span className={styles.sectionHeader}>Approvals</span>
          <p className={styles.sectionDetail}>{`${action.numberOfApprovals} of ${action.approvalsRequired}`}</p>
        </section>
      )}
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Approvers</span>
        {actioners.length > 0 ? (
          <ActionersList actioners={actioners} showVerdict />
        ) : (
          <p className={styles.sectionDetail}>No approvals have been recorded.</p>
        )}
      </section>
    </ModalBody>
  );
}

interface ManualResultProps {
  taskRun: TaskRun;
  action?: Action;
}

function ManualResult({ taskRun, action }: ManualResultProps) {
  const actioners = action?.actioners ?? [];
  // A manual task's instructions are stored on the Action, but they originate as a task param -
  // fall back to that so the instructions still render if the Action can't be resolved.
  const instructions =
    action?.instructions ?? taskRun.params.find((param) => param.name === "instructions")?.value ?? "";
  return (
    <ModalBody>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Status</span>
        <p className={styles.sectionDetail}>{`${
          taskRun.status === RunStatus.Succeeded ? "Successfully Completed" : "Unsuccessfully Completed"
        }`}</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Submitted by</span>
        {actioners.length > 0 ? (
          <ActionersList actioners={actioners} showVerdict={false} />
        ) : (
          <p className={styles.sectionDetail}>No submission has been recorded.</p>
        )}
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Instructions</span>
        <ReactMarkdown className="markdown-body" children={String(instructions)} />
      </section>
    </ModalBody>
  );
}

interface TaskRunDetailModalProps {
  taskRun: TaskRun;
}

function TaskRunDetail({ taskRun }: TaskRunDetailModalProps) {
  let paramList: { id: string; key: string; value: string; description?: string }[] = [];
  taskRun.params.forEach((result, index) =>
    paramList.push({
      id: `${result.name}-${index}`,
      key: result.name,
      value: !result.value
        ? "---"
        : Array.isArray(result.value) || typeof result.value === "string"
        ? result.value
        : JSON.stringify(result.value),
    }),
  );

  let resultsList: { id: string; key: string; value: string; description?: string }[] = [];
  taskRun.results.forEach((result, index) =>
    resultsList.push({
      id: `${result.name}-${index}`,
      key: result.name,
      description: result.description ? result.description : "---",
      value: !result.value
        ? "---"
        : Array.isArray(result.value) || typeof result.value === "string"
        ? result.value
        : JSON.stringify(result.value),
    }),
  );

  return (
    <ModalBody>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Status</span>
        <p className={styles.sectionDetail}>{taskRun.status}</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Message</span>
        <p className={styles.sectionDetail}>{taskRun.statusMessage || "---"}</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Params</span>
        <p>
          <PropertiesTable data={paramList} hasJsonValues={false} />
        </p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Results</span>
        <p>
          <ResultsTable data={resultsList} hasJsonValues={false} />
        </p>
      </section>
    </ModalBody>
  );
}
