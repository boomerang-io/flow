import React from "react";
import { Bee } from "@carbon/react/icons";
import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { Handle, IsValidConnection, Position, useReactFlow } from "@xyflow/react";
import cx from "classnames";
import TaskUpdateModal from "Components/TaskUpdateModal";
import WorkflowCloseButton from "Components/WorkflowCloseButton";
import WorkflowEditButton from "Components/WorkflowEditButton";
import WorkflowWarningButton from "Components/WorkflowWarningButton";
import { useEditorContext, useRunContext, useWorkflowContext } from "Hooks";
import { taskIcons } from "Utils/taskIcons";
import { findTaskRunByName, foreachItemRuns, summarizeForeachItems } from "Utils/taskRunHelper";
import type { ForeachSummary } from "Utils/taskRunHelper";
import { WorkflowEngineMode } from "Constants";
import type { DataDrivenInput, Task, WorkflowEdge, WorkflowNode, WorkflowNodeProps } from "Types";
import { RunStatus, WorkflowEngineModeType } from "Types";
import { splitForeachValues } from "../../shared/foreach";
import { TaskForm as DefaultTaskForm } from "./TaskForm";
import styles from "./TemplateNode.module.scss";

interface TaskTemplateNodeProps extends WorkflowNodeProps {
  additionalFormInputs?: Array<Partial<DataDrivenInput>>;
  className?: string;
  formInputsToMerge?: Array<Partial<DataDrivenInput>>;
  TaskForm?: React.FC<any>; //TODO
}

export default function TaskTemplateNode(props: TaskTemplateNodeProps) {
  const { mode, tasks } = useWorkflowContext();
  // Get the first (and latest) version of the task template
  const taskTemplate = tasks[props.data.taskRef][0];

  if (mode === WorkflowEngineMode.Run) {
    return <TaskTemplateNodeRun {...props} taskTemplate={taskTemplate} />;
  }

  return <TaskTemplateNodeEditor {...props} taskTemplate={taskTemplate} TaskForm={props.TaskForm} />;
}

interface TaskTemplateNodeEditorProps extends TaskTemplateNodeProps {
  taskTemplate: Task;
}

function TaskTemplateNodeEditor(props: TaskTemplateNodeEditorProps) {
  const { TaskForm = DefaultTaskForm } = props;
  const { nodeToEdit, clearNodeToEdit } = useWorkflowContext();
  const reactFlowInstance = useReactFlow<WorkflowNode, WorkflowEdge>();

  const { availableParameters } = useEditorContext();
  const nodes = reactFlowInstance.getNodes();

  // Get the taskNames names from the nodes on the model
  const otherTaskNames = nodes.map((node) => node.data.name).filter((name) => name !== props.data.name);

  const taskTemplate = mergeFormInputs(props.taskTemplate, props.formInputsToMerge);

  const handleOnUpdateTaskVersion = ({ inputs, version }: { inputs: Record<string, string>; version: number }) => {
    const nameAndParamListRecord = inputRecordToNameAndParamListRecord(inputs);
    const newNodes = nodes.map((node) => {
      if (node.id === props.id) {
        return {
          ...node,
          data: { ...node.data, ...nameAndParamListRecord, taskVersion: version, upgradesAvailable: false },
        };
      } else {
        return node;
      }
    });

    reactFlowInstance.setNodes(newNodes);
  };

  // `results` is passed only by the forms whose results the user defines (custom and script tasks);
  // any other task keeps the results it has.
  const handleOnSaveTaskConfig = (
    inputs: Record<string, string>,
    results?: Array<{ name: string; description: string }>,
  ) => {
    // The for-each setting shares the form with the params but is not a param.
    const { foreach, rest } = splitForeachValues(inputs);
    const nameAndParamListRecord = inputRecordToNameAndParamListRecord(rest);
    const newNodes = nodes.map((node) => {
      if (node.id === props.id) {
        return {
          ...node,
          data: { ...node.data, ...nameAndParamListRecord, results: results ?? node.data.results, foreach },
        };
      } else {
        return node;
      }
    });

    reactFlowInstance.setNodes(newNodes);
  };

  return (
    <BaseNode
      isConnectable
      className={props.className}
      icon={taskTemplate.icon}
      mode={WorkflowEngineMode.Edit}
      nodeProps={props}
      title={props.data.name}
      subtitle={taskTemplate.description}
    >
      <ComposedModal
        isOpen={nodeToEdit === props.id}
        onCloseModal={() => {
          if (nodeToEdit === props.id) clearNodeToEdit?.();
        }}
        modalHeaderProps={{
          title: `Edit ${taskTemplate.displayName}`,
          subtitle: taskTemplate.description || "Configure the task",
        }}
        modalTrigger={({ openModal }) => <WorkflowEditButton className={styles.editButton} onClick={openModal} />}
      >
        {({ closeModal }) => (
          <TaskForm
            availableParameters={availableParameters}
            additionalFormInputs={props.additionalFormInputs}
            closeModal={closeModal}
            node={props.data}
            nodeType={props.type}
            onSave={handleOnSaveTaskConfig}
            otherTaskNames={otherTaskNames}
            task={taskTemplate}
          />
        )}
      </ComposedModal>
      <ComposedModal
        composedModalProps={{
          containerClassName: styles.updateTaskModalContainer,
          shouldCloseOnOverlayClick: false,
        }}
        modalHeaderProps={{
          title: `New version available`,
          subtitle:
            "The managers of this task have made some changes that were significant enough for a new version. You can still use the current version, but it’s usually a good idea to update when available. The details of the change are outlined below. If you’d like to update, review the changes below and make adjustments if needed. This process will only update the task in this Workflow - not any other workflows where this task appears.",
        }}
        modalTrigger={({ openModal }) =>
          props.data?.upgradesAvailable ? (
            <WorkflowWarningButton className={styles.updateButton} onClick={openModal} />
          ) : null
        }
      >
        {({ closeModal }) => (
          <TaskUpdateModal
            availableParameters={availableParameters}
            closeModal={closeModal}
            node={props.data}
            onSave={handleOnUpdateTaskVersion}
            latestTaskTemplate={taskTemplate}
          />
        )}
      </ComposedModal>
    </BaseNode>
  );
}

interface TaskTemplateNodeRunProps extends WorkflowNodeProps {
  className?: string;
  taskTemplate: Task;
}

function TaskTemplateNodeRun(props: TaskTemplateNodeRunProps) {
  const { workflowRun } = useRunContext();
  const scrollToTask = () => {
    const taskLogItem = document.getElementById(`task-${props.data.name}`);
    if (taskLogItem) {
      taskLogItem.scrollIntoView();
      taskLogItem.focus();
    }
  };

  // The node stands for the task's own run; for a for-each task that is the parent, never an item.
  const taskRun = findTaskRunByName(workflowRun.tasks, props.data.name);
  const status = taskRun?.status;
  const foreachProgress =
    props.data.foreach && taskRun ? summarizeForeachItems(foreachItemRuns(workflowRun.tasks, taskRun.id)) : undefined;

  return (
    <BaseNode
      className={props.className}
      icon={props.taskTemplate.icon}
      isConnectable={false}
      mode={WorkflowEngineMode.Run}
      foreachProgress={foreachProgress}
      nodeProps={props}
      onClick={scrollToTask}
      status={status}
      subtitle={props.taskTemplate.description}
      title={props.data.name}
    />
  );
}

// A for-each node's badge: its items' progress once it has items, "0 items" when it succeeded with
// none, and plain "For each" otherwise - before it fans out, or when it failed without items (its
// failed colour and the task log's reason say why).
function foreachBadgeText(progress: ForeachSummary | undefined, status: RunStatus | undefined) {
  if (progress && progress.total > 0) {
    return `For each · ${progress.succeeded} of ${progress.total} succeeded`;
  }
  if (progress && status === RunStatus.Succeeded) {
    return "For each · 0 items";
  }
  return "For each";
}

// Lays the node type's own settings (such as the workspace's approver groups) over the template's
// params, matched by name, on a copy so the shared task template is never changed.
function mergeFormInputs(taskTemplate: Task, formInputsToMerge?: Array<Partial<DataDrivenInput>>): Task {
  if (!formInputsToMerge?.length || !taskTemplate.spec.params) return taskTemplate;
  const params = taskTemplate.spec.params.map((param) => {
    const input = formInputsToMerge.find((candidate) => candidate.name === param.name);
    return input ? { ...param, ...input } : param;
  });
  return { ...taskTemplate, spec: { ...taskTemplate.spec, params } };
}

// `results` is never a param: the default form shows a template's declared results read-only, and
// the custom and script forms hand theirs to onSave separately.
function inputRecordToNameAndParamListRecord(inputRecord: Record<string, string>): {
  name: string;
  params: Array<{ name: string; value: string }>;
} {
  // Pull off taskName from input record to set the new name
  // TODO: think about making this better
  const name = inputRecord["taskName"];
  delete inputRecord["taskName"];

  const params = Object.entries(inputRecord)
    .filter(([key]) => key !== "results")
    .map(([key, value]) => {
      return { name: key, value };
    });

  return { name, params };
}

//About: based on WorkflowNode component that serves as a base for many of the the components
//TODO: add icon
//TODO: look at what props are required

interface BaseNodeProps {
  children?: React.ReactNode;
  className?: string;
  // Run mode only: how a for-each task's items have fared so far, shown in its badge.
  foreachProgress?: ForeachSummary;
  icon?: string;
  isConnectable: boolean;
  mode: WorkflowEngineModeType;
  nodeProps: WorkflowNodeProps;
  onClick?: () => void;
  subtitle?: string;
  title?: string;
  status?: RunStatus;
}

function BaseNode(props: BaseNodeProps) {
  const { isConnectable, children, className, foreachProgress, icon, onClick, status, subtitle, title } = props;
  const reactFlowInstance = useReactFlow<WorkflowNode, WorkflowEdge>();
  let Icon = () => <Bee style={{ willChange: "auto" }} />;

  if (icon) {
    //@ts-ignore
    Icon = taskIcons.find((taskIcon) => taskIcon.name === icon)?.Icon ?? Icon;
  }

  const isEditor = props.mode === WorkflowEngineMode.Edit;
  const isForeach = Boolean(props.nodeProps.data?.foreach);
  // See StartNode.tsx for why this is typed via `IsValidConnection` rather than `Connection`.
  // Declared here (rather than after the `return`, as the pre-migration `function` declaration
  // was) because a `const` isn't hoisted the way a `function` declaration is, and it's
  // referenced below in the JSX this function returns.
  const isValidHandle: IsValidConnection = (connection) => connection.source !== connection.target;
  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div
      className={cx(styles.node, className, styles[status ?? ""], {
        [styles.locked]: !isEditor,
        [styles.foreach]: isForeach,
      })}
      onClick={onClick}
    >
      {isForeach ? (
        <div className={cx(styles.badgeContainer, styles.foreachBadge)}>
          <p className={styles.badgeText} data-testid="foreach-badge">
            {foreachBadgeText(foreachProgress, status)}
          </p>
        </div>
      ) : null}
      {isEditor ? (
        <div style={{ position: "absolute", top: "-1rem", right: "-0.875rem", display: "flex", gap: "0.25rem" }}>
          <WorkflowCloseButton
            style={{ height: "1.75rem" }}
            className={""}
            onClick={() => reactFlowInstance.deleteElements({ nodes: [props.nodeProps] })}
          >
            Delete
          </WorkflowCloseButton>
        </div>
      ) : null}
      {status === RunStatus.Running ? <div className={styles.progressBar} /> : null}
      <header className={styles.header}>
        <Icon />
        <h3 title={title || "Task"} className={styles.title}>
          {title || "Task"}
        </h3>
      </header>
      <p title={subtitle} className={styles.subtitle}>
        {subtitle || "Task"}
      </p>
      <Handle
        className={cx(styles.port, styles.right)}
        isConnectable={isConnectable}
        isValidConnection={isValidHandle}
        position={Position.Right}
        type="source"
      />
      <Handle
        className={cx(styles.port, styles.left)}
        isConnectable={isConnectable}
        isValidConnection={isValidHandle}
        position={Position.Left}
        type="target"
      />
      {children}
    </div>
  );
}
