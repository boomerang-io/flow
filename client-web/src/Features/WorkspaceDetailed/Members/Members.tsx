import React from "react";
import { Helmet } from "react-helmet";
import { formatErrorMessage } from "@boomerang-io/utils";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { matchSorter as ms } from "match-sorter";
import capitalize from "lodash/capitalize";
import sortBy from "lodash/sortBy";
import { Link, useFetcher } from "react-router-dom";
import {
  Search,
  Select,
  SelectItem,
  StructuredListWrapper,
  StructuredListHead,
  StructuredListBody,
  StructuredListRow,
  StructuredListCell,
} from "@carbon/react";
import { notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { appLink } from "Config/appConfig";
import { Member, MemberRole } from "Types";
import { hasPermission } from "Utils/permissionHelper";
import { actionError, isActionError, type ActionError } from "Utils/actionResult";
import EmptyState from "Components/EmptyState";
import { useWorkspaceDetailedContext } from "../WorkspaceDetailed";
import AddMember from "./AddMember";
import AddMemberSearch from "./AddMemberSearch";
import RemoveMember from "./RemoveMember";
import styles from "./Members.module.scss";

// Route module for the Members tab - the *index* route of /:workspace/manage
// (app/routes/manageWorkspaceMembers.tsx). Every write on this tab (adding members, changing a
// member's role, removing one) posts to this single intent-keyed action; a fetcher with no explicit
// action path resolves to the nearest matched route, which for a component rendered by the index
// route is that index route. Settling the fetcher revalidates the parent layout route's loader,
// which is where the member list comes from - so there is nothing to invalidate by hand.
export type MembersActionResult =
  | { intent: "remove" }
  | ({ intent: "remove" } & ActionError)
  /** "role": the API sets the role of a member it is sent again. */
  | { intent: "role"; name: string; role: string }
  | ({ intent: "role"; name: string; role: string } & ActionError)
  /** "add" only: one success toast is raised per email, as before. */
  | { intent: "add"; emails: string[] }
  | ({ intent: "add"; emails: string[] } & ActionError);

export async function action({ params, request }: { params: { workspace?: string }; request: Request }) {
  const workspace = String(params.workspace);
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "remove") {
    const memberId = String(formData.get("memberId"));
    try {
      // DELETE with a request body, matching the previous resolver.deleteWorkspaceMembers.
      await serverFetch(request).delete(serviceUrl.workspace.deleteWorkspaceMembers({ workspace }), {
        data: [{ id: memberId }],
      });
      return { intent: "remove" as const };
    } catch (error) {
      return actionError({
        intent: "remove" as const,
        error: formatErrorMessage({
          error,
          defaultMessage: "Request to remove user from workspace failed",
        }),
      });
    }
  }

  if (intent === "role") {
    const memberId = String(formData.get("memberId"));
    const name = String(formData.get("name") ?? "");
    const role = String(formData.get("role"));
    try {
      await serverFetch(request).patch(serviceUrl.resourceWorkspace({ workspace }), { members: [{ id: memberId, role }] });
      return { intent: "role" as const, name, role };
    } catch (error) {
      return actionError({
        intent: "role" as const,
        name,
        role,
        error: formatErrorMessage({ error, defaultMessage: "Request to change the member's role failed" }),
      });
    }
  }

  const members: Array<Member> = JSON.parse(String(formData.get("members")));
  try {
    await serverFetch(request).patch(serviceUrl.resourceWorkspace({ workspace }), { members });
    return { intent: "add" as const, emails: members.map((member) => member.email ?? "") };
  } catch (error) {
    return actionError({
      intent: "add" as const,
      emails: [],
      error: formatErrorMessage({ error, defaultMessage: "Request to add members failed" }),
    });
  }
}

// The workspace, `canEdit` and the current user used to arrive as props from the one route that
// rendered every tab; they now come from the parent layout route's <Outlet context> - see
// WorkspaceDetailed.tsx.
const Members: React.FC = () => {
  const { canEdit, workspace, user } = useWorkspaceDetailedContext();
  const [searchQuery, setSearchQuery] = React.useState("");
  const filteredMemberList = searchQuery ? ms(workspace.members, searchQuery, { keys: ["name", "email"] }) : workspace.members;
  const fetcher = useFetcher<MembersActionResult>();
  // Removing a member and changing a role each settle on a fetcher of this page, not of the row:
  // the row re-renders or unmounts as the list revalidates, and its result would go unseen.
  const removeFetcher = useFetcher<MembersActionResult>();
  const roleFetcher = useFetcher<MembersActionResult>();
  // The add-member modals hand this component their `closeModal` at submit time; the fetcher
  // settles asynchronously, so it is stashed here and invoked once the add succeeds - the same
  // "keep the modal up with a spinner" behaviour the previous mutateAsync/then chain had. Follows
  // Features/Parameters/GlobalParameters/GlobalParameters.tsx.
  const closeModalRef = React.useRef<(() => void) | null>(null);

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "add") {
      return;
    }
    if (isActionError(fetcher.data)) {
      // Failures leave the modal open; AddMember/AddMemberSearch surface them inline via `error`.
      return;
    }
    const { emails } = fetcher.data;
    closeModalRef.current?.();
    closeModalRef.current = null;
    emails?.forEach((email) =>
      notify(
        <ToastNotification
          title="Add User"
          subtitle={`Request to add ${email} to ${workspace.displayName} submitted`}
          kind="success"
        />,
      ),
    );
  }, [fetcher.state, fetcher.data, workspace.displayName]);

  React.useEffect(() => {
    const result = removeFetcher.data;
    if (removeFetcher.state !== "idle" || !result || result.intent !== "remove") {
      return;
    }
    notify(
      !isActionError(result) ? (
        <ToastNotification
          title="Remove User Requested"
          subtitle="Request to remove user from workspace successful"
          kind="success"
          data-cy="b-toast_remove_success"
        />
      ) : (
        <ToastNotification
          title="Something's Wrong"
          subtitle="Request to remove user from workspace failed"
          kind="error"
          data-cy="b-toast_remove_error"
        />
      ),
    );
  }, [removeFetcher.state, removeFetcher.data]);

  React.useEffect(() => {
    const result = roleFetcher.data;
    if (roleFetcher.state !== "idle" || !result || result.intent !== "role") {
      return;
    }
    notify(
      !isActionError(result) ? (
        <ToastNotification
          title="Role changed"
          subtitle={`Too easy! ${result.name} is now ${withArticle(result.role)} of ${workspace.displayName}.`}
          kind="success"
        />
      ) : (
        <ToastNotification
          title="Crikey, that didn’t work!"
          subtitle={`${result.name}'s role is unchanged. Give it another go or try again later.`}
          kind="error"
        />
      ),
    );
  }, [roleFetcher.state, roleFetcher.data, workspace.displayName]);

  const handleSubmit = (request: Array<Member>, closeModal?: () => void) => {
    closeModalRef.current = closeModal ?? null;
    fetcher.submit({ intent: "add", members: JSON.stringify(request) }, { method: "post" });
  };

  const handleRemove = (member: Member) => {
    removeFetcher.submit({ intent: "remove", memberId: member.id ?? "" }, { method: "post" });
  };

  const handleRoleChange = (member: Member, role: string) => {
    roleFetcher.submit({ intent: "role", memberId: member.id ?? "", name: member.name ?? "", role }, { method: "post" });
  };

  const isSubmitting = fetcher.state !== "idle";
  const submitError =
    fetcher.data && isActionError(fetcher.data) && fetcher.data.intent === "add" ? fetcher.data.error : null;
  const isAdmin = user?.type === "admin";
  // Removing a member and changing a role need workspace/delete, which only an owner (or an admin)
  // holds - the same check the API makes on removal.
  const canManageMembers = canEdit && hasPermission(user, "workspace", "delete", workspace.name);
  const ownerCount = workspace.members.filter((member) => member.role === MemberRole.Owner).length;
  // Your own row is locked, and so is the last owner's, so a workspace always keeps an owner.
  const isLocked = (member: Member) =>
    member.id === user.id || (member.role === MemberRole.Owner && ownerCount <= 1);
  const pendingRemoval = removeFetcher.state !== "idle" ? removeFetcher.formData?.get("memberId") : undefined;
  const pendingRole =
    roleFetcher.state !== "idle"
      ? { memberId: roleFetcher.formData?.get("memberId"), role: String(roleFetcher.formData?.get("role") ?? "") }
      : undefined;

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
                isSubmitting={isSubmitting}
                error={submitError}
              />
            )}
            <AddMember
              memberList={workspace.members}
              handleSubmit={handleSubmit}
              isSubmitting={isSubmitting}
              error={submitError}
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
              const isChangingRole = pendingRole?.memberId === member.id;
              return (
                <StructuredListRow key={member.id}>
                  <StructuredListCell>
                    <div className={styles.memberNameContainer}>
                      <p>{`${member.name}${user.id === member.id ? " (you!)" : ""}`}</p>
                    </div>
                  </StructuredListCell>
                  <StructuredListCell>{member.email}</StructuredListCell>
                  <StructuredListCell>
                    {canManageMembers && !isLocked(member) ? (
                      <Select
                        className={styles.roleSelect}
                        disabled={isChangingRole}
                        hideLabel
                        id={`member-role-${member.id}`}
                        labelText={`Role for ${member.name}`}
                        onChange={(event: React.ChangeEvent<HTMLSelectElement>) =>
                          handleRoleChange(member, event.target.value)
                        }
                        size="sm"
                        value={isChangingRole ? pendingRole?.role : member.role}
                      >
                        {Object.values(MemberRole).map((role) => (
                          <SelectItem key={role} text={capitalize(role)} value={role} />
                        ))}
                      </Select>
                    ) : (
                      member.role
                    )}
                  </StructuredListCell>
                  <StructuredListCell>
                    <Link
                      className={styles.viewMemberLink}
                      to={appLink.user({ userId: member.id ?? "" })}
                      state={{ fromWorkspace: workspace.name }}
                    >
                      View user
                    </Link>
                  </StructuredListCell>
                  <StructuredListCell>
                    {canEdit && (
                      <RemoveMember
                        member={member}
                        workspaceName={workspace.name}
                        isLocked={isLocked(member)}
                        isRemoving={pendingRemoval === member.id}
                        onRemove={handleRemove}
                      />
                    )}
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

// "owner" -> "an owner", "reader" -> "a reader".
function withArticle(role: string): string {
  return `${/^[aeiou]/i.test(role) ? "an" : "a"} ${role}`;
}

export default Members;
