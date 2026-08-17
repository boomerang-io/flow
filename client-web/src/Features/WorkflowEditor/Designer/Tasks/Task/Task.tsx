import React from "react";
import { Tile } from "@carbon/react";
import { Bee, Recommend } from "@carbon/react/icons";
import { TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import cx from "classnames";
import { taskIcons } from "Utils/taskIcons";
import { Task as TaskType } from "Types";
import styles from "./task.module.scss";

function Task({ name, icon, verified, scope, taskData }: TaskType & { taskData: TaskType }) {
  const [isDragActive, setIsDragActive] = React.useState(false);
  const isWorkspaceTask = scope === "workspace";
  const TaskIcon = taskIcons.find((currentIcon) => currentIcon.name === icon);

  const onDragStart = (event: any, task: TaskType) => {
    event.dataTransfer.setData("application/reactflow", JSON.stringify(task));
    event.dataTransfer.effectAllowed = "move";
  };

  return (
    <li>
      <Tile
        role="option"
        aria-selected={isDragActive}
        className={cx(styles.container, { [styles.globalTask]: !isWorkspaceTask })}
        draggable={true}
        onDragEnd={() => setIsDragActive(false)}
        onDragStart={(event: React.DragEvent<HTMLDivElement>) => {
          setIsDragActive(true);
          onDragStart(event, taskData);
        }}
        tabIndex="0"
        title={name}
      >
        <div className={styles.columnContainer}>
          <div className={styles.rowContainer}>
            {TaskIcon?.Icon ? (
              <TaskIcon.Icon className={cx(styles.taskIcon, { [styles.workspaceTask]: isWorkspaceTask })} />
            ) : (
              <Bee className={cx(styles.taskIcon, { [styles.workspaceTask]: isWorkspaceTask })} />
            )}
            <p className={styles.taskName}> {name} </p>
            {verified && (
              <TooltipHover
                className={cx(styles.tooltipHover, { [styles.workspaceTask]: isWorkspaceTask })}
                direction="top"
                tooltipText={
                  <div className={styles.tooltipContainer}>
                    <strong>Verified</strong>
                    <p style={{ marginTop: "0.5rem" }}>
                      This task has been fully tested and verified right out of the box.
                    </p>
                  </div>
                }
              >
                <Recommend fill="#0072C3" style={{ willChange: "auto" }} />
              </TooltipHover>
            )}
          </div>

          {isWorkspaceTask && <p className={styles.taskSubtext}>Workspace Task</p>}
        </div>
      </Tile>
    </li>
  );
}

export default Task;
