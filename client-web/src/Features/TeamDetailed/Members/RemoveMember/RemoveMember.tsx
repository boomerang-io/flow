import React from "react";
import { useMutation, useQueryClient } from "react-query";
import { ConfirmModal, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { TrashCan } from "@carbon/react/icons";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { FlowUser, Member } from "Types";
import styles from "./RemoveMember.module.scss";

interface RemoveMemberProps {
  member: FlowUser;
  teamName: string;
  userId: string;
}

const RemoveMember: React.FC<RemoveMemberProps> = ({ member, teamName, userId }) => {
  const queryClient = useQueryClient();
  const leaveTeamMutator = useMutation(resolver.deleteTeamMembers);

  async function handleCreateLeaveTeamRequest() {
    const leaveTeamData: Array<Member> = [
      {
        id: member.id,
      },
    ];
    try {
      await leaveTeamMutator.mutateAsync({ team: teamName, body: leaveTeamData });
      queryClient.invalidateQueries(serviceUrl.resourceTeam({ team: teamName }));
      notify(
        <ToastNotification
          title="Remove User Requested"
          subtitle="Request to remove user from team successful"
          kind="success"
          data-cy="b-toast_remove_success"
        />,
      );
    } catch (error) {
      notify(
        <ToastNotification
          title="Something's Wrong"
          subtitle="Request to remove user from team failed"
          kind="error"
          data-cy="b-toast_remove_error"
        />,
      );
    }
  }

  return (
    <ConfirmModal
      affirmativeAction={handleCreateLeaveTeamRequest}
      affirmativeButtonProps={{ kind: "danger", disabled: leaveTeamMutator.isLoading, "data-testid": "remove-member" }}
      negativeButtonsProps={{ disabled: leaveTeamMutator.isLoading }}
      children={`Are you sure you want to remove ${member.name} from ${teamName}? The user will lose access to all team workflows.`}
      title={`Remove from Team`}
      modalTrigger={({ openModal }) => (
        <button className={styles.removeButton} disabled={member.id === userId} onClick={openModal}>
          Remove from Team
          <TrashCan fill={"#f94d56"} />
        </button>
      )}
    />
  );
};

export default RemoveMember;
