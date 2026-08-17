import React from "react";
import { useMutation, useQueryClient } from "react-query";
import { ConfirmModal, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { TrashCan } from "@carbon/react/icons";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { FlowUser, Member } from "Types";
import styles from "./RemoveMember.module.scss";

interface RemoveMemberProps {
  member: FlowUser;
  workspaceName: string;
  userId: string;
}

const RemoveMember: React.FC<RemoveMemberProps> = ({ member, workspaceName, userId }) => {
  const queryClient = useQueryClient();
  const leaveWorkspaceMutator = useMutation(resolver.deleteWorkspaceMembers);

  async function handleCreateLeaveWorkspaceRequest() {
    const leaveWorkspaceData: Array<Member> = [
      {
        id: member.id,
      },
    ];
    try {
      await leaveWorkspaceMutator.mutateAsync({ workspace: workspaceName, body: leaveWorkspaceData });
      queryClient.invalidateQueries(serviceUrl.resourceWorkspace({ workspace: workspaceName }));
      notify(
        <ToastNotification
          title="Remove User Requested"
          subtitle="Request to remove user from workspace successful"
          kind="success"
          data-cy="b-toast_remove_success"
        />,
      );
    } catch (error) {
      notify(
        <ToastNotification
          title="Something's Wrong"
          subtitle="Request to remove user from workspace failed"
          kind="error"
          data-cy="b-toast_remove_error"
        />,
      );
    }
  }

  return (
    <ConfirmModal
      affirmativeAction={handleCreateLeaveWorkspaceRequest}
      affirmativeButtonProps={{ kind: "danger", disabled: leaveWorkspaceMutator.isLoading, "data-testid": "remove-member" }}
      negativeButtonsProps={{ disabled: leaveWorkspaceMutator.isLoading }}
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
