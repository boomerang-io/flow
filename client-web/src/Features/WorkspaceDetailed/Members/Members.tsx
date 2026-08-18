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
import { FlowWorkspace, FlowUser, Member } from "Types";
import EmptyState from "Components/EmptyState";
import AddMember from "./AddMember";
import AddMemberSearch from "./AddMemberSearch";
import RemoveMember from "./RemoveMember";
import styles from "./Members.module.scss";

interface MemberProps {
  canEdit: boolean;
  workspace: FlowWorkspace;
  user: FlowUser;
  workspaceDetailsUrl: string;
}

const Members: React.FC<MemberProps> = ({ canEdit, workspace, user, workspaceDetailsUrl }) => {
  const [searchQuery, setSearchQuery] = React.useState("");
  const filteredMemberList = searchQuery ? ms(workspace.members, searchQuery, { keys: ["name", "email"] }) : workspace.members;
  const memberMutator = useMutation(resolver.patchWorkspace);
  const queryClient = useQueryClient();

  const handleSubmit = async (request: Array<Member>) => {
    try {
      await memberMutator.mutateAsync({ workspace: workspace.name, body: { members: request } });
      queryClient.invalidateQueries([workspaceDetailsUrl]);
      request.forEach((user: Member) => {
        return notify(
          <ToastNotification
            title="Add User"
            subtitle={`Request to add ${user.email} to ${workspace.displayName} submitted`}
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
    <section aria-label={`${workspace.displayName} Workspace Members`} className={styles.container}>
      <Helmet>
        <title>{`Members - ${workspace.displayName}`}</title>
      </Helmet>
      <section className={styles.actionsContainer}>
        <div className={styles.leftActions}>
          <p className={styles.featureDescription}>These are the people who have access to this Workspace.</p>
          <p className={styles.memberCountText}>
            Showing {filteredMemberList.length} member{filteredMemberList.length !== 1 ? "s" : ""}
          </p>
          <Search
            labelText="member search"
            id="member-search"
            placeholder="Search for a member"
            onChange={(e: { target: HTMLInputElement; type: "change" }) => setSearchQuery(e.target.value)}
          />
        </div>
        {canEdit && (
          <div className={styles.rightActions}>
            {isAdmin && (
              <AddMemberSearch
                memberList={workspace.members}
                handleSubmit={handleSubmit}
                isSubmitting={memberMutator.isLoading}
                error={memberMutator.error}
              />
            )}
            <AddMember
              memberList={workspace.members}
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
                        pathname: appLink.user({ userId: member.id ?? "" }),
                        state: { fromWorkspace: workspace.name },
                      }}
                    >
                      View user
                    </Link>
                  </StructuredListCell>
                  <StructuredListCell>
                    {canEdit && <RemoveMember member={member} workspaceName={workspace.name} userId={user.id} />}
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
