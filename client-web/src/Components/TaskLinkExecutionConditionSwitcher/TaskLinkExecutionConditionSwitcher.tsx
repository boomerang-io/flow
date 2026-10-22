import React from "react";
import cx from "classnames";
import { WorkflowEngineMode } from "Constants";
import { WorkflowEngineModeType } from "Types";
import styles from "./TaskLinkExecutionConditionSwitcher.module.scss";

interface TaskLinkExecutionConditionSwitcherProps {
  disabled?: boolean;
  executionCondition: {
    name: string;
    Icon: (props: React.SVGProps<SVGSVGElement>) => JSX.Element;
  };
  kind?: WorkflowEngineModeType;
  onClick?: () => void;
}

function TaskLinkExecutionConditionSwitcher({
  disabled = false,
  executionCondition,
  kind = WorkflowEngineMode.Edit,
  onClick,
}: TaskLinkExecutionConditionSwitcherProps) {
  const { name, Icon } = executionCondition;
  if (disabled) {
    return <Icon className={cx(styles.container, styles[kind], styles[name], { [styles.disabled]: disabled })} />;
  } else {
    return (
      <button onClick={onClick} style={{ lineHeight: 1 }}>
        <Icon className={cx(styles.container, styles[kind], styles[name])} />
      </button>
    );
  }
}

export default TaskLinkExecutionConditionSwitcher;
