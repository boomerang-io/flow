import React from "react";
import { Helmet } from "react-helmet";
import { useMutation, useQueryClient } from "react-query";
import { resolver } from "Config/servicesConfig";
import { matchSorter as ms } from "match-sorter";
import sortBy from "lodash/sortBy";
import { Link } from "react-router-dom";
import {
  Search,
  StructuredListWrapper,
  StructuredListHead,
  StructuredListBody,
  StructuredListRow,
  StructuredListCell,
} from "@carbon/react";
import { notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { appLink } from "Config/appConfig";
import { FlowTeam, FlowUser, Member } from "Types";
import EmptyState from "Components/EmptyState";
import AddMember from "./AddMember";
import AddMemberSearch from "./AddMemberSearch";
import RemoveMember from "./RemoveMember";
import styles from "./Members.module.scss";

interface MemberProps {
  canEdit: boolean;
  team: FlowTeam;
  user: FlowUser;
  teamDetailsUrl: string;
}

const Members: React.FC<MemberProps> = ({ canEdit, team, user, teamDetailsUrl }) => {
  const [searchQuery, setSearchQuery] = React.useState("");
  const filteredMemberList = searchQuery ? ms(team.members, searchQuery, { keys: ["name", "email"] }) : team.members;
  const memberMutator = useMutation(resolver.patchTeam);
  const queryClient = useQueryClient();

  const handleSubmit = async (request: Array<Member>) => {
    try {
      await memberMutator.mutateAsync({ team: team.name, body: { members: request } });
      queryClient.invalidateQueries([teamDetailsUrl]);
      request.forEach((user: Member) => {
        return notify(
          <ToastNotification
            title="Add User"
            subtitle={`Request to add ${user.email} to ${team.displayName} submitted`}
            kind="success"
          />,
        );
      });
    } catch (error) {
      // noop
    }
  };

  const isAdmin = user?.type === "admin";
  return (
    <section aria-label={`${team.displayName} Team Members`} className={styles.container}>
      <Helmet>
        <title>{`Members - ${team.displayName}`}</title>
      </Helmet>
      <section className={styles.actionsContainer}>
        <div className={styles.leftActions}>
          <p className={styles.featureDescription}>These are the people who have access to this Team.</p>
          <p className={styles.memberCountText}>
            Showing {filteredMemberList.length} member{filteredMemberList.length !== 1 ? "s" : ""}
          </p>
          <Search
            labelText="member search"
            id="member-search"
            placeholder="Search for a member"
            onChange={(e: React.FormEvent<HTMLInputElement>) => setSearchQuery(e.currentTarget.value)}
          />
        </div>
        {canEdit && (
          <div className={styles.rightActions}>
            {isAdmin && (
              <AddMemberSearch
                memberList={team.members}
                handleSubmit={handleSubmit}
                isSubmitting={memberMutator.isLoading}
                error={memberMutator.error}
              />
            )}
            <AddMember
              memberList={team.members}
              handleSubmit={handleSubmit}
              isSubmitting={memberMutator.isLoading}
              error={memberMutator.error}
            />
          </div>
        )}
      </section>
      {filteredMemberList.length > 0 ? (
        <StructuredListWrapper>
          <StructuredListHead>
            <StructuredListRow head>
              <StructuredListCell head>Name</StructuredListCell>
              <StructuredListCell head>Email</StructuredListCell>
              <StructuredListCell head>Role</StructuredListCell>
              <StructuredListCell head />
              <StructuredListCell head />
            </StructuredListRow>
          </StructuredListHead>
          <StructuredListBody>
            {sortBy(filteredMemberList, "name").map((member) => {
              return (
                <StructuredListRow key={member.id}>
                  <StructuredListCell>
                    <div className={styles.memberNameContainer}>
                      <p>{`${member.name}${user.id === member.id ? " (you!)" : ""}`}</p>
                    </div>
                  </StructuredListCell>
                  <StructuredListCell>{member.email}</StructuredListCell>
                  <StructuredListCell>{member.role}</StructuredListCell>
                  <StructuredListCell>
                    <Link
                      className={styles.viewMemberLink}
                      to={{
                        pathname: appLink.user({ userId: member.id }),
                        state: { fromTeam: team.name },
                      }}
                    >
                      View user
                    </Link>
                  </StructuredListCell>
                  <StructuredListCell>
                    {canEdit && <RemoveMember member={member} teamName={team.name} userId={user.id} />}
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
};

export default Members;
