import React from "react";
import { Helmet } from "react-helmet";
import { formatErrorMessage } from "@boomerang-io/utils";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { matchSorter as ms } from "match-sorter";
import sortBy from "lodash/sortBy";
import { Link, useFetcher } from "react-router-dom";
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
import { Member } from "Types";
import EmptyState from "Components/EmptyState";
import { useWorkspaceDetailedContext } from "../WorkspaceDetailed";
import AddMember from "./AddMember";
import AddMemberSearch from "./AddMemberSearch";
import RemoveMember from "./RemoveMember";
import styles from "./Members.module.scss";

// Route module for the Members tab - the *index* route of /:workspace/manage
// (app/routes/manageWorkspaceMembers.tsx). Both writes on this tab (adding members here, removing
// one in ./RemoveMember) post to this single intent-keyed action; a fetcher with no explicit
// action path resolves to the nearest matched route, which for a component rendered by the index
// route is that index route. Settling the fetcher revalidates the parent layout route's loader,
// which is where the member list comes from - so there is nothing to invalidate by hand.
export type MembersActionResult = {
  ok: boolean;
  intent: "add" | "remove";
  /** "add" only: one success toast is raised per email, as before. */
  emails?: string[];
  errorMessage?: { title: string; message: string };
};

export async function action({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<MembersActionResult> {
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
      return { ok: true, intent: "remove" };
    } catch (error) {
      return {
        ok: false,
        intent: "remove",
        errorMessage: formatErrorMessage({
          error,
          defaultMessage: "Request to remove user from workspace failed",
        }),
      };
    }
  }

  const members: Array<Member> = JSON.parse(String(formData.get("members")));
  try {
    await serverFetch(request).patch(serviceUrl.resourceWorkspace({ workspace }), { members });
    return { ok: true, intent: "add", emails: members.map((member) => member.email ?? "") };
  } catch (error) {
    return {
      ok: false,
      intent: "add",
      emails: [],
      errorMessage: formatErrorMessage({ error, defaultMessage: "Request to add members failed" }),
    };
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
  // The add-member modals hand this component their `closeModal` at submit time; the fetcher
  // settles asynchronously, so it is stashed here and invoked once the add succeeds - the same
  // "keep the modal up with a spinner" behaviour the previous mutateAsync/then chain had. Follows
  // Features/Parameters/GlobalParameters/GlobalParameters.tsx.
  const closeModalRef = React.useRef<(() => void) | null>(null);

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "add") {
      return;
    }
    const { ok, emails } = fetcher.data;
    if (!ok) {
      // Failures leave the modal open; AddMember/AddMemberSearch surface them inline via `error`.
      return;
    }
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

  const handleSubmit = (request: Array<Member>, closeModal?: () => void) => {
    closeModalRef.current = closeModal ?? null;
    fetcher.submit({ intent: "add", members: JSON.stringify(request) }, { method: "post" });
  };

  const isSubmitting = fetcher.state !== "idle";
  const submitError = fetcher.data && !fetcher.data.ok && fetcher.data.intent === "add" ? fetcher.data.errorMessage : null;
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
                      to={appLink.user({ userId: member.id ?? "" })}
                      state={{ fromWorkspace: workspace.name }}
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
