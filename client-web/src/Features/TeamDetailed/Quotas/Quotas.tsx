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
import { ModalTriggerProps, FlowTeam } from "Types";

function Quotas({ team, canEdit, teamDetailsUrl }: { team: FlowTeam; canEdit: boolean; teamDetailsUrl: string }) {
  let workflowLimitPercentage = (team.quotas.currentWorkflowCount / team.quotas.maxWorkflowCount) * 100;
  let concurrentLimitPercentage = (team.quotas.currentConcurrentRuns / team.quotas.maxConcurrentRuns) * 100;
  let monthlyExecutionPercentage = (team.quotas.currentRuns / team.quotas.maxWorkflowRunMonthly) * 100;

  if (concurrentLimitPercentage > 100) concurrentLimitPercentage = 100; 
  if (workflowLimitPercentage > 100) workflowLimitPercentage = 100;
  if (monthlyExecutionPercentage > 100) monthlyExecutionPercentage = 100;

  const coverageBarStyle = { height: "1rem", width: "17.625rem" };

  return (
    <section aria-label={`${team.displayName} Team Quotas`} className={styles.container}>
      <Helmet>
        <title>{`Quotas - ${team.displayName}`}</title>
      </Helmet>
      {!canEdit ? (
        <section className={styles.notificationsContainer}>
          <InlineNotification
            lowContrast
            hideCloseButton={true}
            kind="info"
            title="Read-only"
            subtitle="The team may be inactive or you don’t have the necessary permissions. You can still see what’s going on behind the
            scenes."
          />
        </section>
      ) : null}
      <section className={styles.actionsContainer}>
        <div className={styles.leftActions}>
          <p className={styles.featureDescription}>
            The following quotas have been set for the team - only administrators have access to adjust these.
          </p>
        </div>
        <div className={styles.rightActions}>
          <RestoreDefaults team={team} disabled={!canEdit} />
        </div>
      </section>
      <section className={styles.cardsSection}>
        <QuotaCard
          subtitle="Number of Workflows that can be created for this team."
          title="Number of Workflows"
          modalSubtitle="Set the maximum number of Workflows that can be created for this team."
          //   minValue={team.quotas.currentWorkflowCount}
          minValue={1}
          detailedTitle="Current Usage"
          detailedData={`${team.quotas.currentWorkflowCount}/${team.quotas.maxWorkflowCount}`}
          inputLabel="Maximum Workflows"
          inputUnits="Workflows"
          stepValue={1}
          teamName={team.name}
          quotaProperty="maxWorkflowCount"
          quotaValue={team.quotas.maxWorkflowCount}
          disabled={!canEdit}
          teamDetailsUrl={teamDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${team.quotas.maxWorkflowCount} Workflows`}</h3>
          <ProgressBar
            maxValue={team.quotas.maxWorkflowCount}
            value={workflowLimitPercentage}
            coverageBarStyle={coverageBarStyle}
          />
          <p className={styles.detailedSmallText}>{`Current usage: ${team.quotas.currentWorkflowCount}`}</p>
        </QuotaCard>
        <QuotaCard
          subtitle="Number of executions per month across all Workflows for this Team"
          title="Number of Executions"
          modalSubtitle="Set the maximum total number of executions per month - this is the total amount across all Workflows for this Team."
          //   minValue={team.quotas.currentWorkflowExecutionMonthly}
          minValue={1}
          detailedTitle="Current Usage"
          detailedData={`${team.quotas.currentRuns}/${team.quotas.maxWorkflowRunMonthly}`}
          inputLabel="Maximum executions"
          inputUnits="executions"
          stepValue={1}
          teamName={team.name}
          quotaProperty="maxWorkflowRunMonthly"
          quotaValue={team.quotas.maxWorkflowRunMonthly}
          disabled={!canEdit}
          teamDetailsUrl={teamDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${team.quotas.maxWorkflowRunMonthly} per month`}</h3>
          <ProgressBar
            maxValue={team.quotas.maxWorkflowRunMonthly}
            value={monthlyExecutionPercentage}
            coverageBarStyle={coverageBarStyle}
          />
          <p className={styles.detailedSmallText}>{`Current usage: ${team.quotas.currentRuns}`}</p>
        </QuotaCard>
        <QuotaCard
          subtitle="Maximum amount of time that a single Workflow can take for one run (execution)."
          title="Run Duration"
          modalSubtitle="Set the maximum amount of run time for a single Workflow."
          minValue={0}
          detailedTitle="Current average execution time"
          detailedData={`${team.quotas.currentRunMedianDuration} minutes`}
          inputLabel="Maximum duration"
          inputUnits="minutes"
          stepValue={1}
          teamName={team.name}
          quotaProperty="maxWorkflowRunDuration"
          quotaValue={team.quotas.maxWorkflowRunDuration}
          disabled={!canEdit}
          teamDetailsUrl={teamDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${team.quotas.maxWorkflowRunDuration} minutes`}</h3>
        </QuotaCard>
        <QuotaCard
          subtitle="Max number of Workflows able to run at the same time."
          title="Concurrent Runs (executions)"
          modalSubtitle="Set the maximum number of Workflows that are able to run at the same time."
          minValue={1}
          detailedTitle="Current number of Concurrent Workflow Runs"
          detailedData={`${team.quotas.currentConcurrentRuns} Workflow Runs`}
          inputLabel="Maximum concurrent"
          inputUnits="Workflows"
          stepValue={1}
          teamName={team.name}
          quotaProperty="maxConcurrentRuns"
          quotaValue={team.quotas.maxConcurrentRuns}
          disabled={!canEdit}
          teamDetailsUrl={teamDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${team.quotas.maxConcurrentRuns} Workflows`}</h3>
          <ProgressBar
            maxValue={team.quotas.maxConcurrentRuns}
            value={concurrentLimitPercentage}
            coverageBarStyle={coverageBarStyle}
          />
          <p className={styles.detailedSmallText}>{`Current usage: ${team.quotas.currentConcurrentRuns}`}</p>
        </QuotaCard>
        <QuotaCard
          subtitle="Workspace size limit for each Workflow using persistent storage on this Team."
          title="Workspace Capacity - Per Workflow"
          modalSubtitle="Set the storage size limit for each Workflow Workspace using persistent storage on this Team."
          minValue={0}
          detailedTitle="Persistent storage size limit"
          detailedData={`${team.quotas.maxWorkflowStorage}GB per Workflow`}
          inputLabel="Storage limit"
          inputUnits="GB"
          stepValue={1}
          teamName={team.name}
          quotaProperty="maxWorkflowStorage"
          quotaValue={team.quotas.maxWorkflowStorage}
          disabled={!canEdit}
          teamDetailsUrl={teamDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${team.quotas.maxWorkflowStorage}GB per Workflow`}</h3>
        </QuotaCard>
        <QuotaCard
          subtitle="Workspace size limit for each WorkflowRun using persistent storage on this Team."
          title="Workspace Capacity - Per Run"
          modalSubtitle="Set the storage size limit for each WorkflowRun Workspace using persistent storage on this Team."
          minValue={0}
          detailedTitle="Persistent storage size limit"
          detailedData={`${team.quotas.maxWorkflowRunStorage}GB per Workflow`}
          inputLabel="Storage limit"
          inputUnits="GB"
          stepValue={1}
          teamName={team.name}
          quotaProperty="maxWorkflowRunStorage"
          quotaValue={team.quotas.maxWorkflowRunStorage}
          disabled={!canEdit}
          teamDetailsUrl={teamDetailsUrl}
        >
          <h3 className={styles.detailedHeading}> {`${team.quotas.maxWorkflowRunStorage}GB per WorkflowRun`}</h3>
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
  teamName: string;
  quotaProperty: string;
  quotaValue: number;
  disabled: boolean;
  minValue: number;
  teamDetailsUrl: string;
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
  teamName,
  quotaProperty,
  quotaValue,
  disabled,
  minValue,
  teamDetailsUrl,
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
              teamName={teamName}
              quotaProperty={quotaProperty}
              quotaValue={quotaValue}
              minValue={minValue}
              teamDetailsUrl={teamDetailsUrl}
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
