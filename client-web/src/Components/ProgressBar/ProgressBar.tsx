import React from "react";
import styles from "./ProgressBar.module.scss";

interface ProgressBarProps {
  value: number;
  maxValue: number;
  minValue?: number;
  coverageBarStyle?: object;
}

const ProgressBar: React.FC<ProgressBarProps> = ({
  maxValue = 100,
  minValue = 0,
  value = 0,
  coverageBarStyle = { height: "1.125rem", width: "28.125rem" },
}) => {
  return (
    <div className={styles.coverageBar} style={coverageBarStyle}>
      <div
        role="progressbar"
        aria-valuenow={value}
        aria-valuemin={minValue}
        aria-valuemax={maxValue}
        className={styles.coverageFiller}
        style={{ width: `${value}%` }}
      />
    </div>
  );
};

export default ProgressBar;
