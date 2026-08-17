import React from "react";
import { Button } from "@carbon/react";
import { WarningAlt } from "@carbon/react/icons";
import { ComposedModal, Error, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import { useFeature } from "flagged";
import { matchSorter } from "match-sorter";
import queryString from "query-string";
import { useQuery } from "react-query";
import { useHistory, useLocation } from "react-router-dom";
import CreateWorkflow from "Components/CreateWorkflow";
import EmptyState from "Components/EmptyState";
import WorkflowCard from "Components/WorkflowCard";
import { WorkflowCardSkeleton } from "Components/WorkflowCard";
import WorkflowsHeader from "Components/WorkflowsHeader";
import { useTeamContext } from "Hooks";
import { WorkflowView } from "Constants";
import { FeatureFlag, appLink } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { FlowTeam, ModalTriggerProps, PaginatedWorkflowResponse, Workflow } from "Types";
import WorkflowQuotaModalContent from "./WorkflowQuotaModalContent";
import styles from "./workflows.module.scss";

export default function Workflows() {
  const { team } = useTeamContext();
  const history = useHistory();
  const location = useLocation();

  const getWorkflowsUrl = serviceUrl.team.workflow.getWorkflows({
    team: team?.name,
    query: `statuses=active,inactive`,
  });
  const workflowsQuery = useQuery<PaginatedWorkflowResponse, string>({
    queryKey: getWorkflowsUrl,
    queryFn: resolver.query(getWorkflowsUrl),
  });

  // TODO: make this smarter bc we shouldn't get to the route without an active team
  if (!team) {
    return history.push(appLink.home());
  }

  const { query: searchQuery = "" } = queryString.parse(location.search, {
    arrayFormat: "comma",
  });

  const handleUpdateFilter = (args: { query: string }) => {
    const queryStr = `?${queryString.stringify(args, { arrayFormat: "comma", skipEmptyString: true })}`;
    history.push({ search: queryStr });
  };

  let safeSearchQuery = "";
  if (Array.isArray(searchQuery)) {
    safeSearchQuery = searchQuery.join().toLowerCase();
  } else if (searchQuery) {
    safeSearchQuery = searchQuery.toLowerCase();
  }

  if (workflowsQuery.isLoading) {
    return (
      <Layout team={team} handleUpdateFilter={handleUpdateFilter} searchQuery={safeSearchQuery} workflowList={[]}>
        <WorkflowCardSkeleton />
      </Layout>
    );
  }

  if (workflowsQuery.error) {
    return (
      <Layout team={team} handleUpdateFilter={handleUpdateFilter} searchQuery={safeSearchQuery} workflowList={[]}>
        <Error />
      </Layout>
    );
  }

  if (workflowsQuery.data) {
    return (
      <Layout
        team={team}
        handleUpdateFilter={handleUpdateFilter}
        searchQuery={safeSearchQuery}
        workflowList={workflowsQuery.data.content}
      >
        <WorkflowContent
          team={team}
          searchQuery={safeSearchQuery}
          workflowList={workflowsQuery.data.content}
          getWorkflowsUrl={getWorkflowsUrl}
        />
      </Layout>
    );
  }

  return null;
}

interface LayoutProps {
  team: FlowTeam;
  children: React.ReactNode;
  handleUpdateFilter: (args: { query: string }) => void;
  searchQuery: string;
  workflowList: Array<Workflow>;
}

function Layout(props: LayoutProps) {
  return (
    <div className={styles.container}>
      <WorkflowsHeader
        title={"Workflows"}
        subtitle="Your playground to create, execute, and collaborate on workflows. Work smarter with automation."
        handleUpdateFilter={props.handleUpdateFilter}
        searchQuery={props.searchQuery}
        team={props.team}
        workflowList={props.workflowList}
        viewType={WorkflowView.Workflow}
      />
      <div aria-label="My Workflows" className={styles.content} role="region" id="my-workflows">
        <section className={styles.sectionContainer}>{props.children}</section>
      </div>
    </div>
  );
}

interface WorkflowContentProps {
  team: FlowTeam;
  searchQuery: string;
  workflowList: Array<Workflow>;
  getWorkflowsUrl: string;
}

const WorkflowContent: React.FC<WorkflowContentProps> = ({ team, searchQuery, workflowList, getWorkflowsUrl }) => {
  const hasWorkflows = workflowList.length > 0;
  const teamQuotasEnabled = useFeature(FeatureFlag.TeamQuotasEnabled);
  const hasReachedWorkflowLimit = team.quotas.maxWorkflowCount <= team.quotas.currentWorkflowCount;

  const filteredWorkflowList = Boolean(searchQuery)
    ? matchSorter(workflowList, searchQuery, { keys: ["name"] })
    : workflowList;

  if (hasWorkflows && Boolean(searchQuery) && filteredWorkflowList.length === 0) {
    return <EmptyState />;
  }

  return (
    <>
      <hgroup className={styles.header}>
        {teamQuotasEnabled ? (
          <div className={styles.teamQuotaContainer}>
            <div className={styles.quotaDescriptionContainer}>
              <p
                className={styles.teamQuotaText}
              >{`Workflow quota - ${team.quotas.currentWorkflowCount} of ${team.quotas.maxWorkflowCount} used`}</p>
              {hasReachedWorkflowLimit && (
                <TooltipHover
                  direction="top"
                  tooltipText={
                    "This team has reached the maximum number of Workflows allowed. Contact your administrator or team owner to increase the quota, or delete a Workflow to create a new one."
                  }
                >
                  <WarningAlt className={styles.warningIcon} />
                </TooltipHover>
              )}
            </div>
            <ComposedModal
              composedModalProps={{
                containerClassName: styles.quotaModalContainer,
                shouldCloseOnOverlayClick: true,
              }}
              modalHeaderProps={{
                title: `Team quotas - ${team.displayName}`,
                subtitle:
                  "Quotas are set by the administrator. If you have a concern about your allotted amounts, contact an administrator.",
              }}
              modalTrigger={({ openModal }: ModalTriggerProps) => (
                <Button iconDescription="View quota details" kind="ghost" size="sm" onClick={openModal}>
                  View more quotas
                </Button>
              )}
            >
              {({ closeModal }) => <WorkflowQuotaModalContent closeModal={closeModal} quotas={team.quotas} />}
            </ComposedModal>
          </div>
        ) : null}

        {hasWorkflows === false ? (
          <p className={styles.noWorkflowsMessage}>
            This team doesn’t have any Workflows - be the first to take the plunge.
          </p>
        ) : null}
      </hgroup>
      <div className={styles.workflows}>
        {filteredWorkflowList.map((workflow) => (
          <WorkflowCard
            key={workflow.name}
            quotas={team.quotas}
            teamName={team.name}
            viewType={WorkflowView.Workflow}
            workflow={workflow}
            getWorkflowsUrl={getWorkflowsUrl}
          />
        ))}
        <CreateWorkflow
          hasReachedWorkflowLimit={hasReachedWorkflowLimit}
          team={team}
          viewType={WorkflowView.Workflow}
          workflows={workflowList}
        />
      </div>
    </>
  );
};
