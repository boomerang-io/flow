import React from "react";
import { Button } from "@carbon/react";
import { Edit } from "@carbon/react/icons";
import { useNavigate, useParams } from "react-router-dom";
import { appLink } from "Config/appConfig";
import type { Workflow } from "Types";
import styles from "./WorkflowActions.module.scss";

type Props = {
  workflow: Workflow;
};

function WorkflowActions({ workflow }: Props) {
  const { workspace = "" } = useParams<{ workspace: string }>();
  const navigate = useNavigate();

  return (
    <div className={styles.container}>
      <p className={styles.messageText}>Read-only</p>
      <Button
        kind="ghost"
        size="md"
        onClick={() => navigate(appLink.editorCanvas({ workspace, workflow: workflow.name }))}
        renderIcon={Edit}
      >
        Edit Workflow
      </Button>
    </div>
  );
}

export default WorkflowActions;
