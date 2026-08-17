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
import { FlowUser, FlowTeam } from "Types";
import styles from "./UserTeams.module.scss";

interface UserTeamsProps {
  user: FlowUser;
  teams?: Array<FlowTeam>;
}

function UserTeams({ user, teams }: UserTeamsProps) {
  const [searchQuery, setSearchQuery] = React.useState("");
  const userTeams = teams ?? [];
  const filteredTeamsList = searchQuery ? ms(userTeams, searchQuery, { keys: ["name"] }) : userTeams;

  return (
    <section aria-label={`${user.name} Teams`} className={styles.container}>
      <Helmet>
        <title>{`Teams - ${user.name}`}</title>
      </Helmet>
      <section className={styles.actionsContainer}>
        <div className={styles.leftActions}>
          <p className={styles.featureDescription}>{`These are ${user.name}'s teams`}</p>
          <p className={styles.teamCountText}>
            Showing {filteredTeamsList.length} team{filteredTeamsList.length !== 1 ? "s" : ""}
          </p>
          <Search
            labelText="teams search"
            id="teams-search"
            placeholder="Search for a team"
            onChange={(e: React.FormEvent<HTMLInputElement>) => setSearchQuery(e.currentTarget.value)}
          />
        </div>
      </section>
      {filteredTeamsList.length > 0 ? (
        <StructuredListWrapper>
          <StructuredListHead>
            <StructuredListRow head>
              <StructuredListCell head>Name</StructuredListCell>
              <StructuredListCell head />
            </StructuredListRow>
          </StructuredListHead>
          <StructuredListBody>
            {sortBy(filteredTeamsList, "name").map((team) => (
              <StructuredListRow key={team.name}>
                <StructuredListCell>{team.displayName}</StructuredListCell>
                <StructuredListCell>
                  <Link
                    className={styles.viewTeamLink}
                    to={{
                      pathname: appLink.manageTeam({ team: team.name }),
                      state: {
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
                      },
                    }}
                  >
                    View team
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

export default UserTeams;
