//@ts-nocheck
import React from "react";
import styles from "./HeaderWidget.module.scss";

interface HeaderWidgetProps {
  icon?: any;
  text: string;
  value: string | number,
};

function HeaderWidget({ icon: Icon, text, value }: HeaderWidgetProps) {
  return (
    <div className={styles.container}>
      <div className={styles.valueAndIcon}>
        <p className={styles.value}>{value}</p>
        {typeof Icon === "object" ? (
          <Icon aria-label={text} className={styles.icon} />
        ) : typeof Icon === "string" ? (
          <span aria-label={text} aria-hidden={false} className={styles.emoji} role="img">
            {Icon}
          </span>
        ) : null}
      </div>
      <p className={styles.text}>{text}</p>
    </div>
  );
}

export default HeaderWidget;
