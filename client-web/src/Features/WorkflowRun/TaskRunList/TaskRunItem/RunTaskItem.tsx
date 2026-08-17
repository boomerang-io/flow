import { Button, ModalBody } from "@carbon/react";
import { ArrowRight } from "@carbon/react/icons";
import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { task } from "ApiServer/fixtures";
import moment from "moment";
import ReactMarkdown from "react-markdown";
import dateHelper from "Utils/dateHelper";
import { ExecutionStatusCopy, NodeType, executionStatusIcon } from "Constants";
import { RunPhase, RunStatus, TaskRun, WorkflowRun } from "Types";
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
  executionViewRedirect: ({ workflowRunRef }: { workflowRunRef: string }) => void;
};

function RunTaskItem({ taskRun, workflowRun, executionViewRedirect }: Props) {
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
                workflowRef: taskRun.params.find((param) => param.name === "workflowRef")?.value ?? "",
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
            {() => <ApprovalResult taskRun={taskRun} />}
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
            {() => <ManualResult taskRun={taskRun} />}
          </ComposedModal>
        )}
      </section>
    </li>
  );
}

export default RunTaskItem;

interface ApprovalResultProps {
  taskRun: TaskRun;
}

function ApprovalResult({ taskRun }: ApprovalResultProps) {
  return (
    <ModalBody>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Approval Status</span>
        <p className={styles.sectionDetail}>{taskRun.status}</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Approver</span>
        <p className={styles.sectionDetail}>{`${approval.audit.approverName} (${approval.audit.approverEmail})`}</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Approval submitted</span>
        <p className={styles.sectionDetail}>{moment(approval.audit.actionDate).format("YYYY-MM-DD hh:mm A")}</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Approval comments</span>
        <p className={styles.sectionDetail}>{approval.audit.comments}</p>
      </section>
    </ModalBody>
  );
}

interface ManualResultProps {
  taskRun: TaskRun;
}
function ManualResult({ taskRun }: ManualResultProps) {
  return (
    <ModalBody>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Status</span>
        <p className={styles.sectionDetail}>{`${
          taskRun.status === RunStatus.Succeeded ? "Successfully Completed" : "Unsuccessfully Completed"
        }`}</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Approver</span>
        <p className={styles.sectionDetail}>{`${approval.audit.approverName} (${approval.audit.approverEmail})`}</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Submitted</span>
        <p className={styles.sectionDetail}>{moment(approval.audit.actionDate).format("YYYY-MM-DD hh:mm A")}</p>
      </section>
      <section className={styles.detailedSection}>
        <span className={styles.sectionHeader}>Instructions</span>
        <ReactMarkdown className="markdown-body" children={approval?.instructions} />
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
