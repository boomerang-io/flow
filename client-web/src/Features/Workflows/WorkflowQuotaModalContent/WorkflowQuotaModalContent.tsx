import React from "react";
import { ModalBody } from "@carbon/react";
import moment from "moment";
import ProgressBar from "Components/ProgressBar";
import { FlowTeamQuotas } from "Types";
import styles from "./WorkflowQuotaModalContent.module.scss";
import { c } from "vitest/dist/reporters-5f784f42";

export default function WorkflowQuotaModalContent({
  closeModal,
  quotas,
}: {
  closeModal: () => void;
  quotas: FlowTeamQuotas;
}) {
  let workflowLimitPercentage = (quotas.currentWorkflowCount / quotas.maxWorkflowCount) * 100;
  let concurrentLimitPercentage = (quotas.currentConcurrentRuns / quotas.maxConcurrentRuns) * 100;
  let monthlyExecutionPercentage = (quotas.currentRuns / quotas.maxWorkflowRunMonthly) * 100;

  if (concurrentLimitPercentage > 100) concurrentLimitPercentage = 100; 
  if (workflowLimitPercentage > 100) workflowLimitPercentage = 100;
  if (monthlyExecutionPercentage > 100) monthlyExecutionPercentage = 100;

  return (
    <ModalBody className={styles.container}>
      <hr className={styles.divider} />
      <QuotaSection
        description="Number of Workflows that can be created."
        title="Number of Workflows"
        value={quotas.currentWorkflowCount}
        valueUnit="Workflows"
      >
        <ProgressBar maxValue={quotas.maxWorkflowCount} value={workflowLimitPercentage} />
        <p
          className={styles.currentUsage}
        >{`Current usage: ${quotas.currentWorkflowCount} of ${quotas.maxWorkflowCount}`}</p>
      </QuotaSection>
      <QuotaSection
        title="Concurrent runs (executions)"
        description="Number of Workflows able to run at the same time"
        value={quotas.maxConcurrentRuns}
        valueUnit="Workflows"
      >
        <ProgressBar maxValue={quotas.maxConcurrentRuns} value={concurrentLimitPercentage} />
        <p
          className={styles.currentUsage}
        >{`Current usage: ${quotas.currentConcurrentRuns} of ${quotas.maxConcurrentRuns}`}</p>
      </QuotaSection>
      <QuotaSection
        description="Number of runs per month across all Workflows"
        title="Total Workflow Runs"
        value={quotas.maxWorkflowRunMonthly}
        valueUnit="per month"
      >
        <ProgressBar maxValue={quotas.maxWorkflowRunMonthly} value={monthlyExecutionPercentage} />
        <p
          className={styles.detailedText}
        >{`Current usage: ${quotas.currentRuns} of ${quotas.maxWorkflowRunMonthly}`}</p>
        <time className={styles.detailedText}>
          {`Resets on ${moment.utc(quotas.monthlyResetDate).format("MMMM DD, YYYY")}`}
        </time>
      </QuotaSection>
      <hr className={styles.divider} />
      <QuotaSection
        description="Persistent storage size limit per Workflow"
        title="Workflow Storage size capacity"
        value={quotas.maxWorkflowStorage}
        valueUnit="GB"
      />
      <QuotaSection
        description="Persistent storage size limit per Workflow run"
        title="Run Storage size capacity"
        value={quotas.maxWorkflowStorage}
        valueUnit="GB"
      />
      <QuotaSection
        description="Maximum amount of time that a single Workflow can take for one execution."
        title="Run duration"
        value={quotas.maxWorkflowRunDuration}
        valueUnit="minutes"
      />
    </ModalBody>
  );
}

interface QuotaSectionProps {
  description: string;
  title: string;
  value: number;
  valueUnit: string;
}

const QuotaSection: React.FC<QuotaSectionProps> = ({ children, description, title, value, valueUnit }) => {
  return (
    <section>
      <h2 className={styles.title}>{title}</h2>
      <p className={styles.description}>{description}</p>
      <data className={styles.sectionValue} value={value}>{`${value} ${valueUnit}`}</data>
      {children}
    </section>
  );
};
