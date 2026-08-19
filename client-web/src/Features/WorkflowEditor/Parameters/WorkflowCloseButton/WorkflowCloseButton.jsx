import React from "react";
import PropTypes from "prop-types";
import cx from "classnames";
import CloseButton from "./CloseButton";
import { isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import styles from "./WorkflowCloseButton.module.scss";

WorkflowCloseButton.propTypes = {
  alt: PropTypes.string,
  className: PropTypes.string,
  onClick: PropTypes.func.isRequired,
};

export default function WorkflowCloseButton({ alt = "Workflow close button", className, onClick, ...rest }) {
  return (
    <CloseButton
      alt={alt}
      className={cx(styles.closeButton, className)}
      onClick={onClick}
      onKeyDown={(e) => isAccessibleKeyboardEvent(e) && onClick(e)}
      role="button"
      tabIndex={0}
      {...rest}
    />
  );
}
