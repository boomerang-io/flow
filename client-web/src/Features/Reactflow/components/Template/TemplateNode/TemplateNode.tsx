import React from "react";
import { Bee } from "@carbon/react/icons";
import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import cx from "classnames";
import { Connection, Handle, Node, Position, useReactFlow } from "reactflow";
import TaskUpdateModal from "Components/TaskUpdateModal";
import WorkflowCloseButton from "Components/WorkflowCloseButton";
import WorkflowEditButton from "Components/WorkflowEditButton";
import WorkflowWarningButton from "Components/WorkflowWarningButton";
import { useEditorContext, useRunContext, useWorkflowContext } from "Hooks";
import { taskIcons } from "Utils/taskIcons";
import { WorkflowEngineMode } from "Constants";
import type { DataDrivenInput, Task, WorkflowNode, WorkflowNodeProps } from "Types";
import { RunStatus, WorkflowEngineModeType } from "Types";
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
  const { taskTemplate, TaskForm = DefaultTaskForm } = props;
  const reactFlowInstance = useReactFlow();

  const { availableParameters } = useEditorContext();
  const nodes = reactFlowInstance.getNodes() as Array<WorkflowNode>;

  // Get the taskNames names from the nodes on the model
  const otherTaskNames = nodes.map((node) => node.data.name).filter((name) => name !== props.data.name);

  props.formInputsToMerge?.forEach((input) => {
    const foundConfigItemIdx = taskTemplate.spec.params?.findIndex((param) => param.name === input.name) ?? -1;
    if (foundConfigItemIdx >= 0 && taskTemplate.spec.params) {
      taskTemplate.spec.params[foundConfigItemIdx] = { ...taskTemplate.spec.params[foundConfigItemIdx], ...input };
    }
  });

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
    }) as Array<WorkflowNode>;

    reactFlowInstance.setNodes(newNodes);
  };

  const handleOnSaveTaskConfig = (
    inputs: Record<string, string>,
    results: Array<{ name: string; description: string }> = [],
  ) => {
    const nameAndParamListRecord = inputRecordToNameAndParamListRecord(inputs);
    const newNodes = nodes.map((node) => {
      if (node.id === props.id) {
        return {
          ...node,
          data: { ...node.data, ...nameAndParamListRecord, results },
        };
      } else {
        return node;
      }
    }) as unknown as Node<any>[];

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

  const status = workflowRun.tasks.find((task) => task.name === props.data.name)?.status;

  return (
    <BaseNode
      className={props.className}
      icon={props.taskTemplate.icon}
      isConnectable={false}
      mode={WorkflowEngineMode.Run}
      nodeProps={props}
      onClick={scrollToTask}
      status={status}
      subtitle={props.taskTemplate.description}
      title={props.data.name}
    />
  );
}

function inputRecordToNameAndParamListRecord(inputRecord: Record<string, string>): {
  name: string;
  params: Array<{ name: string; value: string }>;
} {
  // Pull off taskName from input record to set the new name
  // TODO: think about making this better
  const name = inputRecord["taskName"];
  delete inputRecord["taskName"];

  const params = Object.entries(inputRecord).map(([key, value]) => {
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
  const { isConnectable, children, className, icon, onClick, status, subtitle, title } = props;
  const reactFlowInstance = useReactFlow();
  let Icon = () => <Bee style={{ willChange: "auto" }} />;

  if (icon) {
    //@ts-ignore
    Icon = taskIcons.find((taskIcon) => taskIcon.name === icon)?.Icon ?? Icon;
  }

  const isEditor = props.mode === WorkflowEngineMode.Edit;
  return (
    // eslint-disable-next-line jsx-a11y/click-events-have-key-events, jsx-a11y/no-static-element-interactions
    <div className={cx(styles.node, className, styles[status ?? ""], { [styles.locked]: !isEditor })} onClick={onClick}>
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

  function isValidHandle(connection: Connection) {
    return connection.source !== connection.target;
  }
}
