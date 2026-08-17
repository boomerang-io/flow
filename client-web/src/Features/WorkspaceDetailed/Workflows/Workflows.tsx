import React from "react";
import {
  Search,
  StructuredListWrapper,
  StructuredListHead,
  StructuredListBody,
  StructuredListRow,
  StructuredListCell,
} from "@carbon/react";
import sortBy from "lodash/sortBy";
import { matchSorter as ms } from "match-sorter";
import moment from "moment";
import queryString from "query-string";
import { Helmet } from "react-helmet";
import { useQuery } from "react-query";
import { Link } from "react-router-dom";
import EmptyState from "Components/EmptyState";
import { appLink } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { FlowWorkspace, PaginatedWorkflowResponse } from "Types";
import styles from "./Workflows.module.scss";

function Workflows({ workspace }: { workspace: FlowWorkspace }) {
  const [searchQuery, setSearchQuery] = React.useState("");

  const getWorkflowsUrl = serviceUrl.workspace.workflow.getWorkflows({ workspace: workspace?.name });
  const workflowsQuery = useQuery<PaginatedWorkflowResponse, string>({
    queryKey: getWorkflowsUrl,
    queryFn: resolver.query(getWorkflowsUrl),
  });

  const workflows = workflowsQuery.data?.content || [];
  const filteredWorkflowsList = searchQuery ? ms(workflows, searchQuery, { keys: ["name", "description"] }) : workflows;

  return (
    <section aria-label={`${workspace.displayName} Workspace Workflows`} className={styles.container}>
      <Helmet>
        <title>{`Workflows - ${workspace.displayName}`}</title>
      </Helmet>
      <section className={styles.actionsContainer}>
        <div className={styles.leftActions}>
          <p className={styles.featureDescription}>These are the workflows for this Workspace.</p>
          <p className={styles.workflowCountText}>
            Showing {filteredWorkflowsList.length} workflow{filteredWorkflowsList.length !== 1 ? "s" : ""}
          </p>
          <Search
            labelText="workflow search"
            id="workflow-search"
            placeholder="Search for a workflow"
            onChange={(e: React.FormEvent<HTMLInputElement>) => setSearchQuery(e.currentTarget.value)}
            size="sm"
          />
        </div>
      </section>
      {filteredWorkflowsList.length > 0 ? (
        <StructuredListWrapper>
          <StructuredListHead>
            <StructuredListRow head>
              <StructuredListCell head>Display Name</StructuredListCell>
              <StructuredListCell head>Unique Identifier Name</StructuredListCell>
              <StructuredListCell head>Date Created</StructuredListCell>
              <StructuredListCell head>Description</StructuredListCell>
              <StructuredListCell head>Version</StructuredListCell>
              <StructuredListCell head />
              <StructuredListCell head />
            </StructuredListRow>
          </StructuredListHead>
          <StructuredListBody>
            {sortBy(filteredWorkflowsList, "name").map((workflow) => {
              return (
                <StructuredListRow key={workflow.name}>
                  <StructuredListCell>
                    <div className={styles.workflowNameContainer}>
                      <p>{workflow.displayName}</p>
                    </div>
                  </StructuredListCell>
                  <StructuredListCell>
                    <div className={styles.workflowNameContainer}>
                      <p>{workflow.name}</p>
                    </div>
                  </StructuredListCell>
                  <StructuredListCell>
                    <time>{moment(workflow.creationDate).format("YYYY-MM-DD hh:mm A")}</time>
                  </StructuredListCell>
                  <StructuredListCell>{workflow.description !== "" ? workflow.description : "---"}</StructuredListCell>
                  <StructuredListCell>{workflow.version}</StructuredListCell>
                  <StructuredListCell>
                    <Link
                      className={styles.viewWorkflowLink}
                      to={{
                        pathname: appLink.editorCanvas({ workspace: workspace.name, workflow: workflow.name }),
                        state: { fromWorkspace: workspace.name },
                      }}
                    >
                      View/edit
                    </Link>
                  </StructuredListCell>
                  <StructuredListCell>
                    <Link
                      className={styles.viewWorkflowLink}
                      to={{
                        pathname: appLink.activity({ workspace: workspace.name }),
                        search: queryString.stringify({ page: 0, size: 10, workflows: workflow.name }),
                        state: { fromWorkspace: workspace.name },
                      }}
                    >
                      Activity
                    </Link>
                  </StructuredListCell>
                </StructuredListRow>
              );
            })}
          </StructuredListBody>
        </StructuredListWrapper>
      ) : (
        <EmptyState />
      )}
    </section>
  );
}

export default Workflows;
