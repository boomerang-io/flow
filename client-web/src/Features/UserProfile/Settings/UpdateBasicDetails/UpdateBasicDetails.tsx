import React from "react";
import { useQueryClient, useMutation } from "react-query";
import { Formik } from "formik";
import { Button, ModalBody, ModalFooter, InlineNotification } from "@carbon/react";
import {
  notify,
  ToastNotification,
  ModalFlowForm,
  TextInput,
  Loading,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { resolver, serviceUrl } from "Config/servicesConfig";
import * as Yup from "yup";
import styles from "./UpdateBasicDetails.module.scss";
import { FlowUser } from "Types";

interface UpdateBasicDetailsProps {
  closeModal: () => void;
  user: FlowUser;
}

const UpdateBasicDetails: React.FC<UpdateBasicDetailsProps> = ({ closeModal, user }) => {
  const queryClient = useQueryClient();
  const updateMutator = useMutation(resolver.patchProfile);
  const handleUpdate = async (values: { displayName: string }) => {
    console.log("Values", values);
    const request = {
      displayName: values.displayName,
    };

    try {
      await updateMutator.mutateAsync({ body: request });
      queryClient.invalidateQueries(serviceUrl.getUserProfile());
      notify(
        <ToastNotification kind="success" title="Update Profile" subtitle="Profile successfully updated" />
      );
      closeModal();
    } catch (error) {
      notify(<ToastNotification kind="error" subtitle="Failed to update profile" title="Something's Wrong" />);
    }
  };

  let buttonText = "Save";
  if (updateMutator.isLoading) {
    buttonText = "Saving...";
  } else if (updateMutator.isError) {
    buttonText = "Try again";
  }

  //TODO - update the error message to include the value of the Text Input
  //TODO - update to not error on current team name
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
        const { values, setFieldValue, handleSubmit, errors, touched, handleChange } = formikProps;
        return (
          <ModalFlowForm>
            <ModalBody>
              <div className={styles.modalInputContainer}>
                {updateMutator.isLoading && <Loading />}
                <TextInput
                  id="displayName"
                  data-testid="text-input-profile-displayname"
                  labelText="Preferred Display Name"
                  helperText="Enter the name you prefer to be called by for a more personalized experience"
                  value={values.displayName}
                  onChange={handleChange}
                />
                {updateMutator.error && (
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
              {/* @ts-ignore */}
              <Button
                disabled={
                  errors.displayName ||
                  updateMutator.isLoading ||
                  updateMutator.error ||
                  updateMutator.isLoading
                }
                onClick={handleSubmit}
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
