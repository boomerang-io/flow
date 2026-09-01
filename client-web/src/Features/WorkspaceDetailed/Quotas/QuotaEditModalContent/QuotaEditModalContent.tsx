import React from "react";
import { Formik } from "formik";
import * as Yup from "yup";
import { useFetcher } from "react-router-dom";
import { Button, ModalBody, ModalFooter, NumberInput, InlineNotification } from "@carbon/react";
import { Loading, ModalForm, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import type { QuotasActionResult } from "../Quotas";
import { isActionError } from "Utils/actionResult";
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
}) => {
  // Posts to the Quotas tab's route action (see ../Quotas) - the displayed values live on the
  // parent layout route's workspace record, which the fetcher's completion revalidates.
  const fetcher = useFetcher<QuotasActionResult>();
  const isSubmitting = fetcher.state !== "idle";
  const failed = Boolean(fetcher.data && isActionError(fetcher.data) && fetcher.data.intent === "update");

  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "update") {
      return;
    }
    if (!isActionError(fetcher.data)) {
      closeModal();
      notify(
        <ToastNotification kind="success" title="Update Workspace Quotas" subtitle="Workspace quota successfully updated" />,
      );
    } else {
      notify(<ToastNotification kind="error" subtitle="Failed to update workspace quota" title="Something's Wrong" />);
    }
  }, [fetcher.state, fetcher.data, closeModal]);

  const handleOnSubmit = (values: { quotaFormValue: number | string }) => {
    fetcher.submit(
      { intent: "update", quotaProperty, quotaValue: String(values.quotaFormValue) },
      { method: "post" },
    );
  };

  let buttonText = "Save";
  if (isSubmitting) {
    buttonText = "Saving...";
  } else if (failed) {
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
                {isSubmitting && <Loading />}
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
                {failed && (
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
                disabled={Boolean(errors.quotaFormValue) || isSubmitting || !dirty}
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
