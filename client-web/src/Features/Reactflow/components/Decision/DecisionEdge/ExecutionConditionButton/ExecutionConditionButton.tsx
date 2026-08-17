import React from "react";
import { isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import cx from "classnames";
import { WorkflowEngineMode } from "Constants";
import { WorkflowEngineModeType } from "Types";
import styles from "./ExecutionConditionButton.module.scss";

interface ConditionButtonProps {
  alt?: string;
  className?: string;
  displayText?: string;
  role?: "button";
  onClick?: () => void;
  onKeyDown?: (e: any) => void;
  tabIndex?: 0;
}

const ConditionButton = ({ displayText, ...rest }: ConditionButtonProps) => (
  <svg xmlns="http://www.w3.org/2000/svg" width="80" height="26" viewBox="0 0 80 26" {...rest}>
    <g fill="none" fillRule="evenodd" stroke="none" strokeWidth="1">
      <g fillRule="evenodd" strokeWidth="1" transform="translate(1 1)">
        <rect width="80" height="24" x="0" y="0" fill="#FFF" fillRule="nonzero" stroke="#6E32C9" rx="4" />
        <g transform="translate(4 2)">
          <path
            fill="#6E32C9"
            fillRule="nonzero"
            d="M13.42 11.077c-.94.003-1.758.762-1.992 1.846H8.774V6.77h2.654c.26 1.2 1.228 1.983 2.26 1.827 1.03-.156 1.804-1.203 1.804-2.442 0-1.24-.773-2.286-1.805-2.442-1.031-.156-1.999.626-2.259 1.826H8.774c-.57.001-1.031.552-1.032 1.231v2.462H5.088c-.26-1.2-1.228-1.983-2.26-1.827-1.03.156-1.804 1.203-1.804 2.442 0 1.24.774 2.286 1.805 2.442 1.031.156 1.999-.626 2.259-1.826h2.654v2.461c0 .68.462 1.23 1.032 1.23h2.654c.27 1.237 1.289 2.02 2.344 1.803 1.056-.216 1.796-1.36 1.704-2.633-.092-1.272-.986-2.248-2.057-2.246zm0-6.154c.57 0 1.032.551 1.032 1.23 0 .68-.463 1.232-1.033 1.232-.57 0-1.032-.551-1.032-1.231s.462-1.23 1.032-1.23zM3.096 11.077c-.57 0-1.032-.551-1.032-1.23 0-.68.462-1.232 1.032-1.232.57 0 1.032.551 1.032 1.231s-.462 1.23-1.032 1.23zm10.322 3.692c-.57 0-1.032-.55-1.032-1.23 0-.68.462-1.231 1.032-1.231.57 0 1.033.55 1.033 1.23-.001.68-.463 1.23-1.033 1.231z"
          />
        </g>
      </g>
      <text
        fill="#242A2E"
        fontFamily="IBMPlexSans, IBM Plex Sans"
        fontSize="12"
        fontWeight="normal"
        transform="translate(1 1)"
      >
        <tspan x="24" y="16">
          {displayText || "default"}
        </tspan>
      </text>
    </g>
  </svg>
);

interface ExecutionConditionButtonProps {
  alt?: string;
  className?: string;
  disabled?: boolean;
  inputText?: string;
  kind?: WorkflowEngineModeType;
  onClick?: () => void;
}

function ExecutionConditionButton({
  alt = "Switch edit button",
  className,
  disabled = false,
  kind = WorkflowEngineMode.Edit,
  onClick,
  inputText,
  ...rest
}: ExecutionConditionButtonProps) {
  /***
   * want to max out the value at 8 characters (until we find a way to make expandable)
   */
  let displayText = inputText;
  if (inputText && inputText.length > 8) {
    displayText = `${inputText.substring(0, 5)}...`;
  }
  if (disabled) {
    return (
      <ConditionButton
        alt={alt}
        className={cx(styles.container, className, styles[kind], styles.disabled)}
        displayText={displayText}
      />
    );
  }
  return (
    <ConditionButton
      alt={alt}
      className={cx(styles.container, className, styles[kind])}
      displayText={displayText}
      onClick={onClick}
      onKeyDown={(e: any) => isAccessibleKeyboardEvent(e) && onClick && onClick()}
      role="button"
      tabIndex={0}
      {...rest}
    />
  );
}

export default ExecutionConditionButton;
