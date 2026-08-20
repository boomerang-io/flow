import React, { useEffect, useState } from "react";
import { useFetcher, useRevalidator } from "react-router-dom";
import { Button, ModalBody, ModalFooter, RadioButton, RadioButtonGroup, InlineNotification } from "@carbon/react";
import { Loading, ModalFlowForm, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { UserType, UserTypeCopy } from "Constants";
import { FlowUser, PlatformRole } from "Types";
import type { UserDetailedActionResult } from "../../UserDetailed";
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
  // The page's own user-detail read is a loader (see UserDetailed.tsx), not a react-query cache
  // entry, so there's nothing to invalidate by query key - revalidate() re-runs the current
  // route's loader(s) instead, which is the loader-era equivalent of invalidateQueries.
  const revalidator = useRevalidator();
  // Bare useFetcher() -> the nearest matched route's action, i.e. UserDetailed.tsx's, which
  // targets the `:userId` route param rather than any id this component submits.
  const fetcher = useFetcher<UserDetailedActionResult>();
  const role = user?.type;
  const [selectedRole, setSelectedRole] = useState<string | undefined>(role);
  const [error, setError] = useState<{ title: string; subtitle: string } | undefined>();

  useEffect(() => {
    setSelectedRole(role);
  }, [role]);

  const isSubmitting = fetcher.state !== "idle";
  const result = fetcher.data;

  // Replaces the mutateAsync try/catch - the fetcher settles asynchronously, so success/failure
  // is handled off the settled result.
  useEffect(() => {
    if (fetcher.state !== "idle" || !result || result.intent !== "changeRole") {
      return;
    }
    if (result.ok) {
      revalidator.revalidate();
      closeModal();
      notify(
        <ToastNotification
          kind="success"
          title="Role Changed"
          subtitle={`Platform role for ${user?.name} is updated to ${selectedRole}`}
        />,
      );
    } else {
      setError({
        title: "Something's Wrong",
        subtitle: `Request to change ${user?.name}'s platform role failed`,
      });
    }
    // closeModal/revalidator identities are not stable; keying on the settled result is what makes
    // this fire once per submission.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, result]);

  const handleOnSubmit = (e: React.SyntheticEvent) => {
    e.preventDefault();
    setError(undefined);
    // `selectedRole` is whatever the RadioButtonGroup last handed setSelectedRole. Carbon v11's
    // RadioButtonGroup calls onChange(newSelection, name, evt), so the first argument is the
    // newly selected RadioButton's `value` - i.e. the role actually picked, not a fixed one.
    fetcher.submit({ intent: "changeRole", type: String(selectedRole) }, { method: "post" });
  };

  return (
    <ModalFlowForm onSubmit={handleOnSubmit}>
      <ModalBody>
        {isSubmitting && <Loading />}
        <div className={styles.gridContainer}>
          <RadioButtonGroup
            labelPosition="right"
            name="platform-role"
            onChange={(newSelection: string | number | undefined) =>
              setSelectedRole(newSelection === undefined ? undefined : String(newSelection))
            }
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
        <Button type="submit" disabled={role === (selectedRole as PlatformRole) || isSubmitting}>
          {isSubmitting ? "Updating..." : error ? "Try again" : "Submit"}
        </Button>
      </ModalFooter>
    </ModalFlowForm>
  );
};

export default ChangeRole;
