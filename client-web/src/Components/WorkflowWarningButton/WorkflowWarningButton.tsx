import React from "react";
import cx from "classnames";
import EditButton from "./WarningButton";
import { isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import styles from "./WorkflowWarningButton.module.scss";

interface WorkflowWarningButtonProps {
  alt?: string;
  className?: string;
  onClick?: (e: React.MouseEvent<HTMLButtonElement, MouseEvent>) => void;
}

const WorkflowWarningButton: React.FC<WorkflowWarningButtonProps> = ({
  alt = "Workflow warning button",
  className,
  onClick,
  ...rest
}) => {
  return (
    <EditButton
      alt={alt}
      className={cx(styles.button, className)}
      onClick={onClick}
      onKeyDown={(e: React.MouseEvent<HTMLButtonElement, MouseEvent>) =>
        isAccessibleKeyboardEvent(e) && onClick && onClick(e)
      }
      role="button"
      tabIndex="0"
      style={{ willChange: "auto" }}
      {...rest}
    />
  );
};
export default WorkflowWarningButton;
