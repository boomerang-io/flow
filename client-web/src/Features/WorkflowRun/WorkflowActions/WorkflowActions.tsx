import React from "react";
import { Button } from "@carbon/react";
import { Edit } from "@carbon/react/icons";
import { useHistory, useParams } from "react-router-dom";
import { appLink } from "Config/appConfig";
import type { Workflow } from "Types";
import styles from "./WorkflowActions.module.scss";

type Props = {
  workflow: Workflow;
};

function WorkflowActions({ workflow }: Props) {
  const { team } = useParams<{ team: string }>();
  const history = useHistory();

  return (
    <div className={styles.container}>
      <p className={styles.messageText}>Read-only</p>
      <Button
        kind="ghost"
        size="md"
        onClick={() => history.push(appLink.editorCanvas({ team, workflow: workflow.name }))}
        renderIcon={Edit}
      >
        Edit Workflow
      </Button>
    </div>
  );
}

export default WorkflowActions;
