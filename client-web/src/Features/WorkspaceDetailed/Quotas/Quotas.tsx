//@ts-nocheck
import { TooltipHover, ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { Tile, Button, InlineNotification } from "@carbon/react";
import { Edit } from "@carbon/react/icons";
import React from "react";
import { Helmet } from "react-helmet";
import ProgressBar from "Components/ProgressBar";
import QuotaEditModalContent from "./QuotaEditModalContent";
import styles from "./Quotas.module.scss";
import RestoreDefaults from "./RestoreDefaults";
import { ModalTriggerProps, FlowWorkspace } from "Types";

function Quotas({ workspace, canEdit, workspaceDetailsUrl }: { workspace: FlowWorkspace; canEdit: boolean; workspaceDetailsUrl: string }) {
  let workflowLimitPercentage = (workspace.quotas.currentWorkflowCount / workspace.quotas.maxWorkflowCount) * 100;
  let concurrentLimitPercentage = (workspace.quotas.currentConcurrentRuns / workspace.quotas.maxConcurrentRuns) * 100;
  let monthlyExecutionPercentage = (workspace.quotas.currentRuns / workspace.quotas.maxWorkflowRunMonthly) * 100;

  if (concurrentLimitPercentage > 100) concurrentLimitPercentage = 100; 
  if (workflowLimitPercentage > 100) workflowLimitPercentage = 100;
  if (monthlyExecutionPercentage > 100) monthlyExecutionPercentage = 100;

  const coverageBarStyle = { height: "1rem", width: "17.625rem" };

  return (
    <section aria-label={`${workspace.displayName} Workspace Quotas`} className={styles.container}>
      <Helmet>
        <title>{`Quotas - ${workspace.displayName}`}</title>
      </Helmet>
      {!canEdit ? (
        <section className={styles.notificationsContainer}>
          <InlineNotification
            lowContrast
            hideCloseButton={true}
            kind="info"
            title="Read-only"
            subtitle="The workspace may be inactive or you don’t have the necessary permissions. You can still see what’s going on behind the
            scenes."
          />
        </section>
      ) : null}
      <section className={styles.actionsContainer}>
        <div className={styles.leftActions}>
          <p className={styles.featureDescription}>
            The following quotas have been set for the workspace - only administrators have access to adjust these.
          </p>
        </div>
        <div className={styles.rightActions}>
          <RestoreDefaults workspace={workspace} disabled={!canEdit} />
        </div>
      </section>
      <section className={styles.cardsSection}>
        <QuotaCard
          subtitle="Number of Workflows that can be created for this workspace."
          title="Number of Workflows"
          modalSubtitle="Set the maximum number of Workflows that can be created for this workspace."
          //   minValue={workspace.quotas.currentWorkflowCount}
          minValue={1}
          detailedTitle="Current Usage"
          detailedData={`${workspace.quotas.currentWorkflowCount}/${workspace.quotas.maxWorkflowCount}`}
          inputLabel="Maximum Workflows"
          inputUnits="Workflows"
          stepValue={1}
          workspaceName={workspace.name}
          quotaProperty="maxWorkflowCount"
          quotaValue={workspace.quotas.maxWorkflowCount}
          disabled={!canEdit}
          workspaceDetailsUrl={workspaceDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${workspace.quotas.maxWorkflowCount} Workflows`}</h3>
          <ProgressBar
            maxValue={workspace.quotas.maxWorkflowCount}
            value={workflowLimitPercentage}
            coverageBarStyle={coverageBarStyle}
          />
          <p className={styles.detailedSmallText}>{`Current usage: ${workspace.quotas.currentWorkflowCount}`}</p>
        </QuotaCard>
        <QuotaCard
          subtitle="Number of executions per month across all Workflows for this Workspace"
          title="Number of Executions"
          modalSubtitle="Set the maximum total number of executions per month - this is the total amount across all Workflows for this Workspace."
          //   minValue={workspace.quotas.currentWorkflowExecutionMonthly}
          minValue={1}
          detailedTitle="Current Usage"
          detailedData={`${workspace.quotas.currentRuns}/${workspace.quotas.maxWorkflowRunMonthly}`}
          inputLabel="Maximum executions"
          inputUnits="executions"
          stepValue={1}
          workspaceName={workspace.name}
          quotaProperty="maxWorkflowRunMonthly"
          quotaValue={workspace.quotas.maxWorkflowRunMonthly}
          disabled={!canEdit}
          workspaceDetailsUrl={workspaceDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${workspace.quotas.maxWorkflowRunMonthly} per month`}</h3>
          <ProgressBar
            maxValue={workspace.quotas.maxWorkflowRunMonthly}
            value={monthlyExecutionPercentage}
            coverageBarStyle={coverageBarStyle}
          />
          <p className={styles.detailedSmallText}>{`Current usage: ${workspace.quotas.currentRuns}`}</p>
        </QuotaCard>
        <QuotaCard
          subtitle="Maximum amount of time that a single Workflow can take for one run (execution)."
          title="Run Duration"
          modalSubtitle="Set the maximum amount of run time for a single Workflow."
          minValue={0}
          detailedTitle="Current average execution time"
          detailedData={`${workspace.quotas.currentRunMedianDuration} minutes`}
          inputLabel="Maximum duration"
          inputUnits="minutes"
          stepValue={1}
          workspaceName={workspace.name}
          quotaProperty="maxWorkflowRunDuration"
          quotaValue={workspace.quotas.maxWorkflowRunDuration}
          disabled={!canEdit}
          workspaceDetailsUrl={workspaceDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${workspace.quotas.maxWorkflowRunDuration} minutes`}</h3>
        </QuotaCard>
        <QuotaCard
          subtitle="Max number of Workflows able to run at the same time."
          title="Concurrent Runs (executions)"
          modalSubtitle="Set the maximum number of Workflows that are able to run at the same time."
          minValue={1}
          detailedTitle="Current number of Concurrent Workflow Runs"
          detailedData={`${workspace.quotas.currentConcurrentRuns} Workflow Runs`}
          inputLabel="Maximum concurrent"
          inputUnits="Workflows"
          stepValue={1}
          workspaceName={workspace.name}
          quotaProperty="maxConcurrentRuns"
          quotaValue={workspace.quotas.maxConcurrentRuns}
          disabled={!canEdit}
          workspaceDetailsUrl={workspaceDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${workspace.quotas.maxConcurrentRuns} Workflows`}</h3>
          <ProgressBar
            maxValue={workspace.quotas.maxConcurrentRuns}
            value={concurrentLimitPercentage}
            coverageBarStyle={coverageBarStyle}
          />
          <p className={styles.detailedSmallText}>{`Current usage: ${workspace.quotas.currentConcurrentRuns}`}</p>
        </QuotaCard>
        <QuotaCard
          subtitle="Workspace size limit for each Workflow using persistent storage on this Workspace."
          title="Workspace Capacity - Per Workflow"
          modalSubtitle="Set the storage size limit for each Workflow Workspace using persistent storage on this Workspace."
          minValue={0}
          detailedTitle="Persistent storage size limit"
          detailedData={`${workspace.quotas.maxWorkflowStorage}GB per Workflow`}
          inputLabel="Storage limit"
          inputUnits="GB"
          stepValue={1}
          workspaceName={workspace.name}
          quotaProperty="maxWorkflowStorage"
          quotaValue={workspace.quotas.maxWorkflowStorage}
          disabled={!canEdit}
          workspaceDetailsUrl={workspaceDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${workspace.quotas.maxWorkflowStorage}GB per Workflow`}</h3>
        </QuotaCard>
        <QuotaCard
          subtitle="Workspace size limit for each WorkflowRun using persistent storage on this Workspace."
          title="Workspace Capacity - Per Run"
          modalSubtitle="Set the storage size limit for each WorkflowRun Workspace using persistent storage on this Workspace."
          minValue={0}
          detailedTitle="Persistent storage size limit"
          detailedData={`${workspace.quotas.maxWorkflowRunStorage}GB per Workflow`}
          inputLabel="Storage limit"
          inputUnits="GB"
          stepValue={1}
          workspaceName={workspace.name}
          quotaProperty="maxWorkflowRunStorage"
          quotaValue={workspace.quotas.maxWorkflowRunStorage}
          disabled={!canEdit}
          workspaceDetailsUrl={workspaceDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${workspace.quotas.maxWorkflowRunStorage}GB per WorkflowRun`}</h3>
        </QuotaCard>
      </section>
    </section>
  );
}

interface QuotaCardProps {
  subtitle: boolean;
  title: string;
  modalSubtitle: string;
  detailedData: string;
  detailedTitle: string;
  inputLabel: string;
  inputUnits: string;
  stepValue: number;
  workspaceName: string;
  quotaProperty: string;
  quotaValue: number;
  disabled: boolean;
  minValue: number;
  workspaceDetailsUrl: string;
}

const QuotaCard: React.FC<QuotaCardProps> = ({
  children,
  subtitle,
  title,
  modalSubtitle,
  detailedData,
  detailedTitle,
  inputLabel,
  inputUnits,
  stepValue,
  workspaceName,
  quotaProperty,
  quotaValue,
  disabled,
  minValue,
  workspaceDetailsUrl,
}) => {
  return (
    <Tile className={styles.cardContainer}>
      <section className={styles.titleSection}>
        <h1 className={styles.title}>{title}</h1>
        <ComposedModal
          composedModalProps={{
            containerClassName: styles.modalContainer,
          }}
          modalHeaderProps={{
            title: title,
            subtitle: modalSubtitle,
          }}
          modalTrigger={({ openModal }: ModalTriggerProps) => (
            <TooltipHover direction="top" content={"Edit"}>
              <Button
                className={styles.editButton}
                iconDescription="Edit"
                kind="ghost"
                onClick={openModal}
                renderIcon={Edit}
                size="md"
                disabled={disabled}
              />
            </TooltipHover>
          )}
        >
          {({ closeModal }) => (
            <QuotaEditModalContent
              closeModal={closeModal}
              detailedData={detailedData}
              detailedTitle={detailedTitle}
              inputLabel={inputLabel}
              inputUnits={inputUnits}
              stepValue={stepValue}
              workspaceName={workspaceName}
              quotaProperty={quotaProperty}
              quotaValue={quotaValue}
              minValue={minValue}
              workspaceDetailsUrl={workspaceDetailsUrl}
            />
          )}
        </ComposedModal>
      </section>
      <h2 className={styles.subtitle}>{subtitle}</h2>
      <section>{children}</section>
    </Tile>
  );
};

export default Quotas;
