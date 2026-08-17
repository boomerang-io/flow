import { Tile } from "@carbon/react";
import styles from "./chartsTile.module.scss";

interface ChartsTileProps {
  children: React.ReactNode;
}

function ChartsTile(props: ChartsTileProps) {
  return (
    <Tile className={styles.container}>
      {props.children}
    </Tile>
  );
}

export default ChartsTile;
