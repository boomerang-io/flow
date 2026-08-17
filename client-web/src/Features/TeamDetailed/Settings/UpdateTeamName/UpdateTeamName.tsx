import React from "react";
import { useQueryClient, useMutation } from "react-query";
import { Formik } from "formik";
import { useHistory } from "react-router-dom";
import { Button, ModalBody, ModalFooter, InlineNotification } from "@carbon/react";
import {
  notify,
  ToastNotification,
  ModalFlowForm,
  TextInput,
  Loading,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { resolver, serviceUrl } from "Config/servicesConfig";
import kebabcase from "lodash/kebabCase";
import * as Yup from "yup";
import { appLink } from "Config/appConfig";
import styles from "./UpdateTeamName.module.scss";
import { FlowTeam } from "Types";

interface UpdateTeamNameProps {
  closeModal: () => void;
  team: FlowTeam;
}

const UpdateTeamName: React.FC<UpdateTeamNameProps> = ({ closeModal, team }) => {
  const queryClient = useQueryClient();
  const history = useHistory();

  const validateTeamNameMutator = useMutation(resolver.postTeamValidateName);
  const updateTeamMutator = useMutation(resolver.patchUpdateTeam);
  const updateTeamName = async (values: { name: string }) => {
    const newTeamName = kebabcase(values.name?.replace(`'`, "-"));
    try {
      await updateTeamMutator.mutateAsync({
        team: team.name,
        body: {
          name: newTeamName,
          displayName: values.name,
        },
      });
      queryClient.invalidateQueries(serviceUrl.resourceTeam({ team: newTeamName }));
      history.push(appLink.manageTeamSettings({ team: newTeamName }));
      notify(
        <ToastNotification kind="success" title="Update Team Settings" subtitle="Team settings successfully updated" />
      );
      closeModal();
    } catch (error) {
      notify(<ToastNotification kind="error" subtitle="Failed to update team settings" title="Something's Wrong" />);
    }
  };

  let buttonText = "Save";
  if (updateTeamMutator.isLoading) {
    buttonText = "Saving...";
  } else if (updateTeamMutator.isError) {
    buttonText = "Try again";
  } else if (validateTeamNameMutator.isLoading) {
    buttonText = "Validating...";
  }

  //TODO - update the error message to include the value of the Text Input
  //TODO - update to not error on current team name
  return (
    <Formik
      initialValues={{
        name: team.displayName,
      }}
      onSubmit={updateTeamName}
      validationSchema={Yup.object().shape({
        name: Yup.string()
          .required("Enter a team name")
          .max(100, "Enter team name that is at most 100 characters in length")
          .test("isUnique", "TAKEN", async (value) => {
            let isValid = true;
            if (value) {
              try {
                await validateTeamNameMutator.mutateAsync({ body: { name: kebabcase(value.replace(`'`, "-")) } });
              } catch (e) {
                console.error(e);
                isValid = false;
              }
            }
            // Need to return promise for yup to do async validation
            return Promise.resolve(isValid);
          }),
      })}
    >
      {(formikProps) => {
        const { values, setFieldValue, handleSubmit, errors, touched } = formikProps;
        return (
          <ModalFlowForm>
            <ModalBody>
              <div className={styles.modalInputContainer}>
                {updateTeamMutator.isLoading && <Loading />}
                <TextInput
                  id="team-update-name-id"
                  data-testid="text-input-team-name"
                  labelText="Display Name"
                  helperText="The display name of your team must make a unique name identifier."
                  value={values.name}
                  onChange={(value: React.ChangeEvent<HTMLInputElement>) => {
                    setFieldValue("name", value.target.value);
                  }}
                  invalid={Boolean(errors.name && !touched.name)}
                  invalidText={
                    errors.name === "TAKEN"
                      ? `Please try again, the name '${values.name}' is unavailable.`
                      : errors.name
                  }
                />
                {updateTeamMutator.error && (
                  <InlineNotification
                    lowContrast
                    kind="error"
                    title="Name changed failed!"
                    subtitle="Give it another go or try again later."
                  />
                )}
                <div className={styles.text}>
                  {values.name ? (
                    <p>
                      Your updated unique team name identifier will be "
                      <b>{kebabcase(values ? values.name.replace(`'`, "-") : "")}</b>", which has been adjusted to
                      remove spaces and special characters.
                    </p>
                  ) : (
                    <p>
                      Your updated unique team name identifier will be adjusted to remove spaces and special characters.
                    </p>
                  )}
                </div>
              </div>
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" type="button" onClick={closeModal}>
                Cancel
              </Button>
              {/* @ts-ignore */}
              <Button
                disabled={
                  errors.name ||
                  updateTeamMutator.isLoading ||
                  validateTeamNameMutator.error ||
                  validateTeamNameMutator.isLoading
                }
                onClick={handleSubmit}
                data-testid="save-team-name"
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

export default UpdateTeamName;
