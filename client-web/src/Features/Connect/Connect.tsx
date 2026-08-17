import React from "react";
import queryString from "query-string";
import { useLocation } from "react-router-dom";
import GitHub from "./GitHub";
import styles from "./connect.module.scss";

function Connect() {
  const location = useLocation();
  const searchParams = new URLSearchParams(location.search);
  console.log(searchParams);

  const state = searchParams.get("state");
  const installationId = searchParams.get("installation_id");
  const team = atob(decodeURIComponent(state));
  console.log(team);
  return (
    <div className={styles.container}>
      <GitHub installId={installationId} team={team} />
    </div>
  );
}

export default Connect;
