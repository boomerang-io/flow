import React from "react";
import { Tile } from "@carbon/react";
import styles from "./statTile.module.scss";

interface StatTileProps {
  label: string;
  value: React.ReactNode;
  /** One line under the number: a breakdown, a period, a caveat. */
  detail?: React.ReactNode;
  testId?: string;
}

/** A headline number: label, value, one line of detail. Rounded like every other card. */
export default function StatTile({ label, value, detail, testId }: StatTileProps) {
  return (
    <Tile className={styles.tile} data-testid={testId}>
      <span className={styles.label}>{label}</span>
      <span className={styles.value}>{value}</span>
      {detail ? <span className={styles.detail}>{detail}</span> : null}
    </Tile>
  );
}
