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
  workspaceName: string;
  quotaProperty: string;
  quotaValue: number;
  minValue: number;
  workspaceDetailsUrl: string;
}

const QuotaEditModalContent: React.FC<QuotaEditProps> = ({
  closeModal,
  detailedData,
  detailedTitle,
  inputLabel,
  inputUnits,
  stepValue,
  workspaceName,
  quotaProperty,
  quotaValue,
  minValue,
  workspaceDetailsUrl,
}) => {
  const queryClient = useQueryClient();
  const updateWorkspaceMutator = useMutation(resolver.patchUpdateWorkspace);

  const handleOnSubmit = async (values: { quotaFormValue: number | string }) => {
    let quotas = { [quotaProperty]: values.quotaFormValue };
    try {
      await updateWorkspaceMutator.mutateAsync({ workspace: workspaceName, body: { quotas: quotas } });
      queryClient.invalidateQueries(workspaceDetailsUrl);
      closeModal();
      notify(
        <ToastNotification kind="success" title="Update Workspace Quotas" subtitle="Workspace quota successfully updated" />,
      );
    } catch {
      notify(<ToastNotification kind="error" subtitle="Failed to update workspace quota" title="Something's Wrong" />);
    }
  };

  let buttonText = "Save";
  if (updateWorkspaceMutator.isLoading) {
    buttonText = "Saving...";
  } else if (updateWorkspaceMutator.error) {
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
                {updateWorkspaceMutator.isLoading && <Loading />}
                <dt className={styles.detailedTitle}>{detailedTitle}</dt>
                <dt className={styles.detailedData}>{detailedData}</dt>
                <div className={styles.inputContainer}>
                  <NumberInput
                    id="workspace-update-name-id"
                    data-testid="text-input-workspace-name"
                    label={inputLabel}
                    value={values.quotaFormValue}
                    step={stepValue}
                    min={minValue}
                    //Need a max value in order to work - need to update in case of invalid value
                    max={99999}
                    onChange={(evt: React.MouseEvent<HTMLButtonElement>, { value }: { value: number | string }) => {
                      setFieldValue("quotaFormValue", value);
                    }}
                    invalid={Boolean(errors.quotaFormValue && !touched.quotaFormValue)}
                    invalidText={errors.quotaFormValue}
                  />
                  {inputUnits && <span className={styles.inputUnits}>{inputUnits}</span>}
                </div>
                {updateWorkspaceMutator.error && (
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
                disabled={Boolean(errors.quotaFormValue) || updateWorkspaceMutator.isLoading || !dirty}
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
