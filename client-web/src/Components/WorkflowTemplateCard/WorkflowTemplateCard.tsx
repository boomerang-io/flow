import React from "react";
import { Bee } from "@carbon/react/icons";
import workflowIcons from "Assets/workflowIcons";
import { Workflow } from "Types";
import styles from "./workflowTemplateCard.module.scss";

interface WorkflowTemplateCardProps {
  workflow: Workflow;
}

// Templates are read-only seeded content, so this card only displays one. Creating a Workflow
// from a template is WorkflowTemplateHomeCard on the Home screen.
const WorkflowTemplateCard: React.FC<WorkflowTemplateCardProps> = ({ workflow }) => {
  const { name, Icon = Bee } = workflowIcons.find((icon) => icon.name === workflow.icon) ?? {};

  return (
    <div className={styles.container}>
      <section className={styles.details}>
        <div className={styles.iconContainer}>
          <Icon className={styles.icon} aria-label={`${name}`} />
        </div>
        <div className={styles.descriptionContainer}>
          <h1 title={workflow.name} className={styles.name} data-testid="workflow-card-title">
            {workflow.name}
          </h1>
          <p title={workflow.description} className={styles.description}>
            {workflow.description}
          </p>
        </div>
      </section>
    </div>
  );
};

export default WorkflowTemplateCard;
