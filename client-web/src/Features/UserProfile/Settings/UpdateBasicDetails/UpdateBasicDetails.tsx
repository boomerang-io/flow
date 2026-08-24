import React from "react";
import { useFetcher } from "react-router-dom";
import { Formik } from "formik";
import { Button, ModalBody, ModalFooter, InlineNotification } from "@carbon/react";
import {
  notify,
  ToastNotification,
  ModalFlowForm,
  TextInput,
  Loading,
} from "@boomerang-io/carbon-addons-boomerang-react";
import * as Yup from "yup";
import type { UserProfileActionResult } from "../../UserProfile";
import styles from "./UpdateBasicDetails.module.scss";
import { FlowUser } from "Types";

interface UpdateBasicDetailsProps {
  closeModal: () => void;
  user: FlowUser;
}

const UpdateBasicDetails: React.FC<UpdateBasicDetailsProps> = ({ closeModal, user }) => {
  // The profile is root-loader-driven (Features/App/App.tsx), and React Router revalidates every
  // matched loader - root included - once this fetcher's action settles, so the updated profile
  // comes back with no explicit refresh call (queryClient.invalidateQueries(getUserProfile())
  // would be a silent no-op).
  // Bare useFetcher() -> the nearest matched route's action, i.e. Features/UserProfile/UserProfile.tsx
  // via app/routes/profile.tsx. That action issues the PATCH with serverFetch(request), which
  // forwards the caller's session cookie; the previous useMutation(resolver.patchProfile) call is
  // gone. See UserProfile.tsx's action for why the target stays `PATCH /profile` (session-scoped)
  // rather than a user-id-bearing route.
  const fetcher = useFetcher<UserProfileActionResult>();
  const isSubmitting = fetcher.state !== "idle";
  const result = fetcher.data;
  const isError = Boolean(result && !result.ok);

  // The fetcher settles asynchronously, so the success/failure handling that used to sit in
  // handleUpdate's try/catch runs here off the settled result instead.
  React.useEffect(() => {
    if (fetcher.state !== "idle" || !result) {
      return;
    }
    if (result.ok) {
      notify(<ToastNotification kind="success" title="Update Profile" subtitle="Profile successfully updated" />);
      closeModal();
    } else {
      notify(<ToastNotification kind="error" subtitle="Failed to update profile" title="Something's Wrong" />);
    }
    // closeModal's identity is not stable across renders; keying the effect on the settled fetcher
    // result is what makes it fire once per submission.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, result]);

  const handleUpdate = (values: { displayName: string }) => {
    fetcher.submit({ intent: "updateProfile", displayName: values.displayName }, { method: "post" });
  };

  let buttonText = "Save";
  if (isSubmitting) {
    buttonText = "Saving...";
  } else if (isError) {
    buttonText = "Try again";
  }

  //TODO - update the error message to include the value of the Text Input
  //TODO - update to not error on current workspace name
  return (
    <Formik
      initialValues={{
        displayName: user.displayName ?? "",
      }}
      onSubmit={handleUpdate}
      validationSchema={Yup.object().shape({
        displayName: Yup.string(),
      })}
    >
      {(formikProps) => {
        const { values, handleSubmit, errors, handleChange } = formikProps;
        return (
          <ModalFlowForm>
            <ModalBody>
              <div className={styles.modalInputContainer}>
                {isSubmitting && <Loading />}
                <TextInput
                  id="displayName"
                  data-testid="text-input-profile-displayname"
                  labelText="Preferred Display Name"
                  helperText="Enter the name you prefer to be called by for a more personalized experience"
                  value={values.displayName}
                  onChange={handleChange}
                />
                {isError && (
                  <InlineNotification
                    lowContrast
                    kind="error"
                    title="Update failed!"
                    subtitle="Give it another go or try again later."
                  />
                )}
              </div>
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" type="button" onClick={closeModal}>
                Cancel
              </Button>
              <Button
                disabled={Boolean(errors.displayName) || isSubmitting}
                onClick={() => handleSubmit()}
                data-testid="save-profile-displayname"
              >
                {buttonText}
              </Button>
            </ModalFooter>
          </ModalFlowForm>
        );
      }}
    </Formik>
  );
};

export default UpdateBasicDetails;
