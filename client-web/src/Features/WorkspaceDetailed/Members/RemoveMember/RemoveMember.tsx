import React from "react";
import { useFetcher } from "react-router-dom";
import { ConfirmModal, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { TrashCan } from "@carbon/react/icons";
import { Member } from "Types";
import type { MembersActionResult } from "../Members";
import styles from "./RemoveMember.module.scss";

interface RemoveMemberProps {
  member: Member;
  workspaceName: string;
  userId: string;
}

const RemoveMember: React.FC<RemoveMemberProps> = ({ member, workspaceName, userId }) => {
  // Posts to the Members tab's route action (see ../Members) - this component renders inside the
  // index route of /:workspace/manage, so a fetcher with no explicit action path resolves there.
  // Settling it revalidates the parent layout loader that supplies the member list.
  const fetcher = useFetcher<MembersActionResult>();
  const isRemoving = fetcher.state !== "idle";

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "remove") {
      return;
    }
    notify(
      fetcher.data.ok ? (
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
  }, [fetcher.state, fetcher.data]);

  function handleCreateLeaveWorkspaceRequest() {
    fetcher.submit({ intent: "remove", memberId: member.id ?? "" }, { method: "post" });
  }

  return (
    <ConfirmModal
      affirmativeAction={handleCreateLeaveWorkspaceRequest}
      affirmativeButtonProps={{ kind: "danger", disabled: isRemoving, "data-testid": "remove-member" }}
      negativeButtonProps={{ disabled: isRemoving }}
      children={`Are you sure you want to remove ${member.name} from ${workspaceName}? The user will lose access to all workspace workflows.`}
      title={`Remove from Workspace`}
      modalTrigger={({ openModal }) => (
        <button className={styles.removeButton} disabled={member.id === userId} onClick={openModal}>
          Remove from Workspace
          <TrashCan fill={"#f94d56"} />
        </button>
      )}
    />
  );
};

export default RemoveMember;
