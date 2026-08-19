import { useState } from "react";
import { Button } from "@carbon/react";
import { SkeletonPlaceholder } from "@carbon/react";
import { ArrowsVertical, ChevronLeft } from "@carbon/react/icons";
import orderBy from "lodash/orderBy";
import { UseQueryResult } from "react-query";
import { getSimplifiedDuration } from "Utils/timeHelper";
import { ExecutionStatusCopy, executionStatusIcon, NodeType } from "Constants";
import { QueryStatus } from "Constants";
import { RunStatus, WorkflowRun } from "Types";
import TaskRunItem from "./TaskRunItem";
import styles from "./TaskRunList.module.scss";

type Props = {
  workflowRun: WorkflowRun;
  executionViewRedirect: ({ workflowRunRef }: { workflowRunRef: string }) => void;
};

function TaskRunLog({ workflowRun, executionViewRedirect }: Props) {
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
        {tasks.map((taskRun) =>
          taskRun.type !== NodeType.Start && taskRun.type !== NodeType.End ? (
            <TaskRunItem
              key={taskRun.id}
              taskRun={taskRun}
              workflowRun={workflowRun}
              executionViewRedirect={executionViewRedirect}
            />
          ) : null,
        )}
      </ul>
    </aside>
  );
}

export default TaskRunLog;
