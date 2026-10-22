import React from "react";
import cx from "classnames";
import { Helmet } from "react-helmet";
import { useLocation, useParams } from "react-router-dom";
import ReactFlow from "Features/Reactflow";
import { groupTasksByName } from "Utils";
import { TaskTemplateStatus, WorkflowEngineMode } from "Constants";
import { appLink } from "Config/appConfig";
import { Task, WorkflowEditorState, WorkflowReactFlowInstance } from "Types";
import Notes from "./Notes";
import TaskList from "./Tasks";
import styles from "./designer.module.scss";

interface DesignerContainerProps {
  notes?: string;
  reactFlowInstance: WorkflowReactFlowInstance | null;
  setReactFlowInstance: React.Dispatch<React.SetStateAction<WorkflowReactFlowInstance | null>>;
  tasks: Array<Task>;
  updateNotes: (markdown: string) => void;
  workflow: WorkflowEditorState;
}

function DesignerContainer(props: DesignerContainerProps) {
  const { notes, reactFlowInstance, setReactFlowInstance, tasks, updateNotes, workflow } = props;

  const params = useParams<{ workspace: string; workflow: string }>();

  const location = useLocation();
  const isOnDesignerPath =
    appLink.editorCanvas({ workspace: params.workspace ?? "", workflow: params.workflow ?? "" }) === location.pathname;

  return (
    <div className={cx(styles.container, { [styles.hidden]: !isOnDesignerPath })}>
      <Helmet>
        <title>{`Workflow - ${workflow.name}`}</title>
      </Helmet>
      <TaskList tasks={tasks.filter((task) => task.status === TaskTemplateStatus.Active)} />
      <>
        <div id="workflow-dag-designer" className={styles.workflowContainer}>
          <ReactFlow
            mode={WorkflowEngineMode.Edit}
            nodes={workflow.nodes}
            edges={workflow.edges}
            reactFlowInstance={reactFlowInstance!}
            setReactFlowInstance={setReactFlowInstance}
            tasks={groupTasksByName(tasks)}
          />
        </div>
        <Notes markdown={notes} updateNotes={updateNotes} />
      </>
    </div>
  );
}

export default DesignerContainer;
