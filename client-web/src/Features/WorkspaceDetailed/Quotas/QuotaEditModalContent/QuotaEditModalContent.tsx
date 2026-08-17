import React from "react";
import { Formik } from "formik";
import * as Yup from "yup";
import { useMutation, useQueryClient } from "react-query";
import { Button, ModalBody, ModalFooter, NumberInput, InlineNotification } from "@carbon/react";
import { Loading, ModalForm, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { resolver } from "Config/servicesConfig";
import styles from "./QuotaEditModalContent.module.scss";

interface QuotaEditProps {
  closeModal: () => void;
  detailedData: string;
  detailedTitle: string;
  inputLabel: string;
  inputUnits: string;
  stepValue: number;
  teamName: string;
  quotaProperty: string;
  quotaValue: number;
  minValue: number;
  teamDetailsUrl: string;
}

const QuotaEditModalContent: React.FC<QuotaEditProps> = ({
  closeModal,
  detailedData,
  detailedTitle,
  inputLabel,
  inputUnits,
  stepValue,
  teamName,
  quotaProperty,
  quotaValue,
  minValue,
  teamDetailsUrl,
}) => {
  const queryClient = useQueryClient();
  const updateTeamMutator = useMutation(resolver.patchUpdateTeam);

  const handleOnSubmit = async (values: { quotaFormValue: number | string }) => {
    let quotas = { [quotaProperty]: values.quotaFormValue };
    try {
      await updateTeamMutator.mutateAsync({ team: teamName, body: { quotas: quotas } });
      queryClient.invalidateQueries(teamDetailsUrl);
      closeModal();
      notify(
        <ToastNotification kind="success" title="Update Team Quotas" subtitle="Team quota successfully updated" />,
      );
    } catch {
      notify(<ToastNotification kind="error" subtitle="Failed to update team quota" title="Something's Wrong" />);
    }
  };

  let buttonText = "Save";
  if (updateTeamMutator.isLoading) {
    buttonText = "Saving...";
  } else if (updateTeamMutator.error) {
    buttonText = "Try again";
  }

  return (
    <Formik
      initialValues={{
        quotaFormValue: quotaValue,
      }}
      onSubmit={handleOnSubmit}
      validationSchema={Yup.object().shape({
        quotaFormValue: Yup.number().required("Value is required"),
      })}
    >
      {(formikProps) => {
        const { values, setFieldValue, errors, touched, dirty } = formikProps;
        return (
          <ModalForm>
            <ModalBody className={styles.modalBodyContainer}>
              <div className={styles.modalInputContainer}>
                {updateTeamMutator.isLoading && <Loading />}
                <dt className={styles.detailedTitle}>{detailedTitle}</dt>
                <dt className={styles.detailedData}>{detailedData}</dt>
                <div className={styles.inputContainer}>
                  <NumberInput
                    id="team-update-name-id"
                    data-testid="text-input-team-name"
                    labelText={inputLabel}
                    value={values.quotaFormValue}
                    step={stepValue}
                    min={minValue}
                    //Need a max value in order to work - need to update in case of invalid value
                    max={99999}
                    onChange={(evt: React.ChangeEvent<HTMLInputElement>, { value }: { value: number }) => {
                      //@ts-ignore
                      setFieldValue("quotaFormValue", value);
                    }}
                    invalid={Boolean(errors.quotaFormValue && !touched.quotaFormValue)}
                    invalidText={errors.quotaFormValue}
                  />
                  {inputUnits && <span className={styles.inputUnits}>{inputUnits}</span>}
                </div>
                {updateTeamMutator.error && (
                  <InlineNotification
                    lowContrast
                    kind="error"
                    title="Quota changed failed!"
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
                disabled={errors.quotaFormValue || updateTeamMutator.isLoading || !dirty}
                onClick={() => handleOnSubmit(values)}
              >
                {buttonText}
              </Button>
            </ModalFooter>
          </ModalForm>
        );
      }}
    </Formik>
  );
};

export default QuotaEditModalContent;
