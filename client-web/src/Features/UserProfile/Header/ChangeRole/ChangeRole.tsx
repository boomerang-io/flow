// @ts-nocheck
import React, { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "react-query";
import { Button, ModalBody, ModalFooter, RadioButton, RadioButtonGroup, InlineNotification } from "@carbon/react";
import { Loading, ModalFlowForm, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { UserType, UserTypeCopy } from "Constants";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { FlowUser } from "Types";
import styles from "./ChangeRole.module.scss";

interface ChangeRoleProps {
  closeModal: () => void;
  user: FlowUser | undefined;
}

// used to avoid id collisions
const ROLE_PREFIX = "platform-role-";

const rolesList = [
  { name: UserTypeCopy[UserType.Admin], id: UserType.Admin },
  { name: UserTypeCopy[UserType.User], id: UserType.User },
];

const ChangeRole: React.FC<ChangeRoleProps> = ({ closeModal, user }) => {
  const queryClient = useQueryClient();
  const role = user?.type;
  const [selectedRole, setSelectedRole] = useState(null);
  const [error, setError] = useState();

  useEffect(() => {
    setSelectedRole(role);
  }, [role]);

  const changeUserMutator = useMutation(resolver.patchManageUser);

  const handleOnSubmit = (e: React.SyntheticEvent) => {
    e.preventDefault();
    changeRoleConfirmed();
  };

  const changeRoleConfirmed = async () => {
    const { name } = user;
    const request = {
      type: selectedRole,
    };

    try {
      await changeUserMutator.mutateAsync({ body: request, userId: user.id });
      queryClient.invalidateQueries(serviceUrl.getUser({ userId: user.id }));
      closeModal();
      notify(
        <ToastNotification
          kind="success"
          title="Role Changed"
          subtitle={`Platform role for ${name} is updated to ${selectedRole}`}
        />
      );
    } catch (error) {
      setError({ title: "Something's Wrong", subtitle: `Request to change ${name}'s platform role failed` });
    }
  };

  return (
    <ModalFlowForm onSubmit={handleOnSubmit}>
      <ModalBody>
        {changeUserMutator.isLoading && <Loading />}
        <div className={styles.gridContainer}>
          <RadioButtonGroup
            labelPosition="right"
            name="platform-role"
            onChange={setSelectedRole}
            orientation="vertical"
            valueSelected={selectedRole}
          >
            {rolesList.map((option) => (
              <RadioButton
                key={option.id}
                id={`${ROLE_PREFIX}${option.id}`}
                labelText={option.name}
                value={option.id}
              />
            ))}
          </RadioButtonGroup>
        </div>
        {error && <InlineNotification lowContrast kind="error" {...error} />}
      </ModalBody>
      <ModalFooter>
        <Button kind="secondary" onClick={closeModal}>
          Cancel
        </Button>
        <Button type="submit" disabled={role === selectedRole || changeUserMutator.isLoading}>
          {changeUserMutator.isLoading ? "Updating..." : error ? "Try again" : "Submit"}
        </Button>
      </ModalFooter>
    </ModalFlowForm>
  );
};

export default ChangeRole;
