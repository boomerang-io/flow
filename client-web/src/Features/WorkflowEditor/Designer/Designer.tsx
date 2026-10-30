import React from "react";
import { InlineNotification } from "@carbon/react";
import { Locked } from "@carbon/react/icons";
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
  // The tasks belong to a template: no palette, no editing of the graph.
  readOnly?: boolean;
  reactFlowInstance: WorkflowReactFlowInstance | null;
  setReactFlowInstance: React.Dispatch<React.SetStateAction<WorkflowReactFlowInstance | null>>;
  tasks: Array<Task>;
  updateNotes: (markdown: string) => void;
  workflow: WorkflowEditorState;
}

function DesignerContainer(props: DesignerContainerProps) {
  const { notes, readOnly = false, reactFlowInstance, setReactFlowInstance, tasks, updateNotes, workflow } = props;

  const params = useParams<{ workspace: string; workflow: string }>();

  const location = useLocation();
  const isOnDesignerPath =
    appLink.editorCanvas({ workspace: params.workspace ?? "", workflow: params.workflow ?? "" }) === location.pathname;

  return (
    <div className={cx(styles.container, { [styles.hidden]: !isOnDesignerPath, [styles.readOnly]: readOnly })}>
      <Helmet>
        <title>{`Workflow - ${workflow.name}`}</title>
      </Helmet>
      {readOnly ? (
        <aside className={styles.rail} aria-label="Tasks are the template's">
          <Locked size={16} aria-hidden="true" />
          <span className={styles.railLabel}>Tasks are the template&apos;s</span>
        </aside>
      ) : (
        <TaskList tasks={tasks.filter((task) => task.status === TaskTemplateStatus.Active)} />
      )}
      <>
        <div id="workflow-dag-designer" className={styles.workflowContainer}>
          {readOnly ? (
            <InlineNotification
              className={styles.templateNotice}
              hideCloseButton
              kind="info"
              lowContrast
              title="This is a template."
              subtitle="Its tasks and parameters are read-only and follow the template's versions. Run it as is, or give it a name, labels, triggers or a schedule of your own on Configure."
            />
          ) : null}
          <ReactFlow
            mode={readOnly ? WorkflowEngineMode.View : WorkflowEngineMode.Edit}
            nodes={workflow.nodes}
            edges={workflow.edges}
            reactFlowInstance={reactFlowInstance!}
            setReactFlowInstance={setReactFlowInstance}
            tasks={groupTasksByName(tasks)}
          />
        </div>
        {readOnly ? null : <Notes markdown={notes} updateNotes={updateNotes} />}
      </>
    </div>
  );
}

export default DesignerContainer;
