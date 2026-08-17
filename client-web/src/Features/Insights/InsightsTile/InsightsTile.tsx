import { Tile } from "@carbon/react";
import styles from "./insightsTile.module.scss";

interface InsightsTileProps {
  infoList: Array<{ label: string; value: string | number }>;
  title: string;
  totalCount: string | number;
  type: string;
  valueWidth?: string;
}

function InsightsTile(props: InsightsTileProps) {
  const { title, type, totalCount, infoList, valueWidth = "2rem" } = props;
  return (
    <Tile className={styles.container}>
      <div>
        <h3 className={styles.title}>{title}</h3>
        <div className={styles.totalCountContainer}>
          <p className={styles.totalCount}>{totalCount}</p>
          <p className={styles.type}>{type}</p>
        </div>
      </div>
      <ul className={styles.list}>
        {infoList.map((item, index) => (
          <li className={styles.row} key={index}>
            <p className={styles.itemValue} style={{ width: valueWidth }}>
              {item.value}
            </p>
            <p className={styles.itemLabel}>{item.label}</p>
          </li>
        ))}
      </ul>
    </Tile>
  );
}

export default InsightsTile;
