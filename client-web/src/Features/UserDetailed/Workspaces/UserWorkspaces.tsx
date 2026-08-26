import React from "react";
import { Helmet } from "react-helmet";
import sortBy from "lodash/sortBy";
import {
  Search,
  StructuredListBody,
  StructuredListCell,
  StructuredListHead,
  StructuredListRow,
  StructuredListWrapper,
} from "@carbon/react";
import { Link } from "react-router-dom";
import EmptyState from "Components/EmptyState";
import { matchSorter as ms } from "match-sorter";
import { appLink } from "Config/appConfig";
import { FlowUser, FlowWorkspaceSummary } from "Types";
import styles from "./UserWorkspaces.module.scss";

interface UserWorkspacesProps {
  user: FlowUser;
  workspaces?: Array<FlowWorkspaceSummary> | null;
}

function UserWorkspaces({ user, workspaces }: UserWorkspacesProps) {
  const [searchQuery, setSearchQuery] = React.useState("");
  const userWorkspaces = workspaces ?? [];
  const filteredWorkspacesList = searchQuery ? ms(userWorkspaces, searchQuery, { keys: ["name"] }) : userWorkspaces;

  return (
    <section aria-label={`${user.name} Workspaces`} className={styles.container}>
      <Helmet>
        <title>{`Workspaces - ${user.name}`}</title>
      </Helmet>
      <section className={styles.actionsContainer}>
        <div className={styles.leftActions}>
          <p className={styles.featureDescription}>{`These are ${user.name}'s workspaces`}</p>
          <p className={styles.workspaceCountText}>
            Showing {filteredWorkspacesList.length} workspace{filteredWorkspacesList.length !== 1 ? "s" : ""}
          </p>
          <Search
            labelText="workspaces search"
            id="workspaces-search"
            placeholder="Search for a workspace"
            onChange={(e: { target: HTMLInputElement; type: "change" }) => setSearchQuery(e.target.value)}
          />
        </div>
      </section>
      {filteredWorkspacesList.length > 0 ? (
        <StructuredListWrapper>
          <StructuredListHead>
            <StructuredListRow head>
              <StructuredListCell head>Name</StructuredListCell>
              <StructuredListCell head />
            </StructuredListRow>
          </StructuredListHead>
          <StructuredListBody>
            {sortBy(filteredWorkspacesList, "name").map((workspace) => (
              <StructuredListRow key={workspace.name}>
                <StructuredListCell>{workspace.displayName}</StructuredListCell>
                <StructuredListCell>
                  <Link
                    className={styles.viewWorkspaceLink}
                    to={appLink.manageWorkspace({ workspace: workspace.name })}
                    state={{
                      navList: [
                        {
                          to: appLink.userList(),
                          text: "Users",
                        },
                        {
                          to: appLink.user({ userId: user.id }),
                          text: user.name,
                        },
                      ],
                    }}
                  >
                    View workspace
                  </Link>
                </StructuredListCell>
              </StructuredListRow>
            ))}
          </StructuredListBody>
        </StructuredListWrapper>
      ) : (
        <EmptyState />
      )}
    </section>
  );
}

export default UserWorkspaces;
