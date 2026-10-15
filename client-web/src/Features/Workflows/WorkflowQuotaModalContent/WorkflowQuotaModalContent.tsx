import React from "react";
import { ModalBody } from "@carbon/react";
import moment from "moment";
import ProgressBar from "Components/ProgressBar";
import { FlowWorkspaceQuotas } from "Types";
import { formatQuotaLimit, formatQuotaUsage, quotaUsagePercent } from "Utils/quotaHelper";
import styles from "./WorkflowQuotaModalContent.module.scss";

export default function WorkflowQuotaModalContent({
  closeModal,
  quotas,
}: {
  closeModal: () => void;
  quotas: FlowWorkspaceQuotas;
}) {
  // null when the quota is unlimited: no bar, since there is no limit to measure against.
  const workflowLimitPercentage = quotaUsagePercent(quotas.currentWorkflowCount, quotas.maxWorkflowCount);
  const concurrentLimitPercentage = quotaUsagePercent(quotas.currentConcurrentRuns, quotas.maxConcurrentRuns);
  const monthlyExecutionPercentage = quotaUsagePercent(quotas.currentRuns, quotas.maxWorkflowRunMonthly);

  return (
    <ModalBody className={styles.container}>
      <hr className={styles.divider} />
      <QuotaSection
        description="Number of Workflows that can be created."
        title="Number of Workflows"
        value={quotas.maxWorkflowCount}
        valueUnit="Workflows"
      >
        {workflowLimitPercentage !== null && (
          <ProgressBar maxValue={quotas.maxWorkflowCount} value={workflowLimitPercentage} />
        )}
        <p className={styles.currentUsage}>
          {`Current usage: ${formatQuotaUsage(quotas.currentWorkflowCount, quotas.maxWorkflowCount)}`}
        </p>
      </QuotaSection>
      <QuotaSection
        title="Concurrent runs (executions)"
        description="Number of Workflows able to run at the same time"
        value={quotas.maxConcurrentRuns}
        valueUnit="Workflows"
      >
        {concurrentLimitPercentage !== null && (
          <ProgressBar maxValue={quotas.maxConcurrentRuns} value={concurrentLimitPercentage} />
        )}
        <p className={styles.currentUsage}>
          {`Current usage: ${formatQuotaUsage(quotas.currentConcurrentRuns, quotas.maxConcurrentRuns)}`}
        </p>
      </QuotaSection>
      <QuotaSection
        description="Number of runs per month across all Workflows"
        title="Total Workflow Runs"
        value={quotas.maxWorkflowRunMonthly}
        valueUnit="per month"
      >
        {monthlyExecutionPercentage !== null && (
          <ProgressBar maxValue={quotas.maxWorkflowRunMonthly} value={monthlyExecutionPercentage} />
        )}
        <p className={styles.detailedText}>
          {`Current usage: ${formatQuotaUsage(quotas.currentRuns, quotas.maxWorkflowRunMonthly)}`}
        </p>
        <time className={styles.detailedText}>
          {`Resets on ${moment.utc(quotas.monthlyResetDate).format("MMMM DD, YYYY")}`}
        </time>
      </QuotaSection>
      <hr className={styles.divider} />
      <QuotaSection
        description="Size limit of the shared workspace a workflow can declare for each run"
        title="Workspace Storage - Per Run"
        value={quotas.maxWorkspaceStorage}
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
  children?: React.ReactNode;
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
      <data className={styles.sectionValue} value={value}>
        {formatQuotaLimit(value, valueUnit)}
      </data>
      {children}
    </section>
  );
};
