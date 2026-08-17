import React from "react";
import { Helmet } from "react-helmet";
import ChangeLogTable from "./ChangeLogTable";
import { ChangeLog as ChangeLogType } from "Types";
import styles from "./changeLog.module.scss";

interface ChangeLogProps {
  changeLogData: ChangeLogType;
}

function ChangeLog({ changeLogData }: ChangeLogProps) {
  return (
    <div className={styles.container}>
      <Helmet>
        <title>Change Log</title>
      </Helmet>
      <ChangeLogTable changeLog={changeLogData} />
    </div>
  );
}

export default ChangeLog;
