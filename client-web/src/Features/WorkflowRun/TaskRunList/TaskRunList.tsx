import { useState } from "react";
import { Button, Tab, TabList, Tabs } from "@carbon/react";
import { SkeletonPlaceholder } from "@carbon/react";
import { ArrowsVertical, ChevronLeft } from "@carbon/react/icons";
import orderBy from "lodash/orderBy";
import { foreachItemRuns, isForeachItem } from "Utils/taskRunHelper";
import { NodeType } from "Constants";
import { Action, Artifact, RunStatus, WorkflowRun } from "Types";
import ArtifactsPanel from "./ArtifactsPanel";
import TaskRunItem from "./TaskRunItem";
import styles from "./TaskRunList.module.scss";

type Props = {
  workflowRun: WorkflowRun;
  // Actions for this run's `approval`/`manual` tasks, keyed by the TaskRun id they belong to
  // (`Action.taskRunRef`). Resolved once by the route loader - see WorkflowRun.tsx - because a
  // TaskRun only carries an `actionRef`, never the approver detail itself.
  actions?: Record<string, Action>;
  // This run's artifacts, for the "Artifacts (n)" tab - see WorkflowRun.tsx's loader. Optional so
  // this component's existing spec (which never touched artifacts) keeps working unchanged.
  artifacts?: Array<Artifact>;
  workspace?: string;
  executionViewRedirect: ({ workflowRunRef }: { workflowRunRef: string }) => void;
  // The names of the workflow's for-each tasks, from its definition - a TaskRun does not say.
  foreachTaskNames?: ReadonlySet<string>;
};

const TAB_TASK_LOG = 0;
const TAB_ARTIFACTS = 1;

function TaskRunLog({ workflowRun, actions, artifacts = [], workspace = "", executionViewRedirect, foreachTaskNames }: Props) {
  const [isCollapsed, setIsCollapsed] = useState(false);
  const [tasksSort, setTasksSort] = useState<"desc" | "asc">("desc");
  const [activeTab, setActiveTab] = useState<number>(TAB_TASK_LOG);

  const toggleCollapse = () => {
    setIsCollapsed(!isCollapsed);
  };

  const toggleSort = () => {
    setTasksSort(tasksSort === "desc" ? "asc" : "desc");
  };

  if (workflowRun.status === RunStatus.Waiting) {
    return (
      <aside className={`${styles.container} ${isCollapsed ? styles.collapsed : ""}`}>
        <section className={styles.taskbar}>
          <p className={styles.taskbarTitle}>Task log</p>
          {!isCollapsed && (
            <Button
              disabled
              data-testid="taskbar-button"
              iconDescription="Change sort direction (by start time)"
              renderIcon={ArrowsVertical}
              size="sm"
              kind="ghost"
              hasIconOnly
            />
          )}
        </section>
        <ul className={styles.tasklog}>
          <SkeletonPlaceholder className={styles.taskLogSkeleton} />
        </ul>
      </aside>
    );
  }

  const { tasks } = workflowRun;

  // START/END are synthetic graph markers the engine adds to every run, not executed tasks -
  // they always bookend the log (START first, END last) and never take part in the start-time
  // sort applied to the rest.
  const startTask = tasks.find((taskRun) => taskRun.type === NodeType.Start);
  const endTask = tasks.find((taskRun) => taskRun.type === NodeType.End);
  // The items of a for-each task are listed inside their parent's entry, not beside it.
  const sortedTasks = orderBy(
    tasks.filter(
      (taskRun) => taskRun.type !== NodeType.Start && taskRun.type !== NodeType.End && !isForeachItem(taskRun),
    ),
    ["startTime"],
    [tasksSort],
  );

  return (
    <aside className={`${styles.container} ${isCollapsed ? styles.collapsed : ""}`}>
      {/* The run's status and duration sit in the page header; the panel opens on its tabs. */}
      <button
        aria-label={isCollapsed ? "Expand the task log" : "Collapse the task log"}
        className={styles.collapseButton}
        onClick={toggleCollapse}
      >
        <ChevronLeft size={16} className={styles.chevron} />
      </button>
      <section className={styles.taskbar}>
        {/* Two views of one panel: contained tabs, full width, with a count on each. */}
        <div className={styles.switcher}>
          <Tabs selectedIndex={activeTab} onChange={({ selectedIndex }: { selectedIndex: number }) => setActiveTab(selectedIndex)}>
            <TabList aria-label="Run detail" contained fullWidth>
              <Tab>{`Task log (${sortedTasks.length})`}</Tab>
              <Tab>{`Artifacts (${artifacts.length})`}</Tab>
            </TabList>
          </Tabs>
        </div>
        {!isCollapsed && activeTab === TAB_TASK_LOG && (
          <Button
            data-testid="taskbar-button"
            iconDescription="Change sort direction (by start time)"
            renderIcon={ArrowsVertical}
            onClick={toggleSort}
            size="sm"
            kind="ghost"
            hasIconOnly
          />
        )}
      </section>
      {activeTab === TAB_TASK_LOG ? (
        <ul className={styles.tasklog}>
          {startTask && (
            <TaskRunItem
              key={startTask.id}
              taskRun={startTask}
              workflowRun={workflowRun}
              action={actions?.[startTask.id]}
              executionViewRedirect={executionViewRedirect}
            />
          )}
          {sortedTasks.map((taskRun) => (
            <TaskRunItem
              key={taskRun.id}
              taskRun={taskRun}
              workflowRun={workflowRun}
              action={actions?.[taskRun.id]}
              executionViewRedirect={executionViewRedirect}
              isForeach={foreachTaskNames?.has(taskRun.name)}
              items={foreachItemRuns(tasks, taskRun.id)}
            />
          ))}
          {endTask && (
            <TaskRunItem
              key={endTask.id}
              taskRun={endTask}
              workflowRun={workflowRun}
              action={actions?.[endTask.id]}
              executionViewRedirect={executionViewRedirect}
            />
          )}
        </ul>
      ) : null}
      {activeTab === TAB_ARTIFACTS && (
        <div className={styles.tasklog}>
          <ArtifactsPanel artifacts={artifacts} workflowRun={workflowRun} workspace={workspace} />
        </div>
      )}
    </aside>
  );
}

export default TaskRunLog;
