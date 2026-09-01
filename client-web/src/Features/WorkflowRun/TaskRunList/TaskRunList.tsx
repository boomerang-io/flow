import { useState } from "react";
import { Button } from "@carbon/react";
import { SkeletonPlaceholder } from "@carbon/react";
import { ArrowsVertical, ChevronLeft } from "@carbon/react/icons";
import orderBy from "lodash/orderBy";
import { getSimplifiedDuration } from "Utils/dateHelper";
import { ExecutionStatusCopy, executionStatusIcon, NodeType } from "Constants";
import { Action, RunStatus, WorkflowRun } from "Types";
import TaskRunItem from "./TaskRunItem";
import styles from "./TaskRunList.module.scss";

type Props = {
  workflowRun: WorkflowRun;
  // Actions for this run's `approval`/`manual` tasks, keyed by the TaskRun id they belong to
  // (`Action.taskRunRef`). Resolved once by the route loader - see WorkflowRun.tsx - because a
  // TaskRun only carries an `actionRef`, never the approver detail itself.
  actions?: Record<string, Action>;
  executionViewRedirect: ({ workflowRunRef }: { workflowRunRef: string }) => void;
};

function TaskRunLog({ workflowRun, actions, executionViewRedirect }: Props) {
  const [isCollapsed, setIsCollapsed] = useState(false);
  const [tasksSort, setTasksSort] = useState<"desc" | "asc">("desc");

  const toggleCollapse = () => {
    setIsCollapsed(!isCollapsed);
  };

  const toggleSort = () => {
    setTasksSort(tasksSort === "desc" ? "asc" : "desc");
  };

  if (workflowRun.status === RunStatus.Waiting) {
    return (
      <aside className={`${styles.container} ${isCollapsed ? styles.collapsed : ""}`}>
        <section className={styles.statusBlock}>
          <SkeletonPlaceholder className={styles.statusBlockSkeleton} />
        </section>
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

  const { duration, status, tasks } = workflowRun;
  const Icon = executionStatusIcon[status];

  // START/END are synthetic graph markers the engine adds to every run, not executed tasks -
  // they always bookend the log (START first, END last) and never take part in the start-time
  // sort applied to the rest.
  const startTask = tasks.find((taskRun) => taskRun.type === NodeType.Start);
  const endTask = tasks.find((taskRun) => taskRun.type === NodeType.End);
  const sortedTasks = orderBy(
    tasks.filter((taskRun) => taskRun.type !== NodeType.Start && taskRun.type !== NodeType.End),
    ["startTime"],
    [tasksSort],
  );

  return (
    <aside className={`${styles.container} ${isCollapsed ? styles.collapsed : ""}`}>
      <section className={`${styles.statusBlock} ${styles[status]}`}>
        <div className={styles.duration}>
          <p className={styles.title}>Duration</p>
          <time className={styles.value}>
            {typeof duration === "number" ? getSimplifiedDuration(duration / 1000) : "--"}
          </time>
        </div>
        <div className={styles.status}>
          <p className={styles.title}>Status</p>
          <div className={styles.statusData}>
            {Icon && <Icon aria-label={status} className={styles.statusIcon} />}
            <p className={styles.value}>{status ? ExecutionStatusCopy[status] : "--"}</p>
          </div>
        </div>
        <button className={styles.collapseButton} onClick={toggleCollapse}>
          <ChevronLeft size={32} className={styles.chevron} />
        </button>
      </section>
      <section className={styles.taskbar}>
        <p className={styles.taskbarTitle}>Task log</p>
        {!isCollapsed && (
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
    </aside>
  );
}

export default TaskRunLog;
