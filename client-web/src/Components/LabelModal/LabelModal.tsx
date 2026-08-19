//@ts-nocheck
import React from "react";
import * as Yup from "yup";
import { Formik } from "formik";
import { Button, ModalBody, ModalFooter } from "@carbon/react";
import { ComposedModal, ModalForm, TextInput } from "@boomerang-io/carbon-addons-boomerang-react";
import styles from "./LabelModal.module.scss";

interface LabelModalProps {
  action: Function;
  isEdit?: boolean;
  labelsKeys: string[];
  modalTrigger: React.ReactNode;
  selectedLabel?: { key: string; value: string };
}

const keyPrefixRegex = /^[a-zA-Z0-9][a-zA-Z0-9-]{1,62}([.\1][a-zA-Z0-9-]{1,63})*$/;
const keyNameAndValueRegex = /^[a-zA-Z0-9][a-zA-Z0-9-_.]{0,61}[a-zA-Z0-9]$/;

const LabelModal: React.FC<LabelModalProps> = ({ action, isEdit = false, labelsKeys, modalTrigger, selectedLabel }) => {
  return (
    <ComposedModal
      composedModalProps={{
        containerClassName: styles.modalContainer,
      }}
      modalHeaderProps={{
        title: `${isEdit ? "Edit" : "Add"} Label`,
        subtitle: `${isEdit ? "Edit" : "Create"} a key value pair for a label`,
      }}
      modalTrigger={modalTrigger}
    >
      {({ closeModal }) => (
        <AddLabelModalContent
          action={action}
          closeModal={closeModal}
          isEdit={isEdit}
          labelsKeys={labelsKeys}
          selectedLabel={selectedLabel}
        />
      )}
    </ComposedModal>
  );
};

export default LabelModal;

interface AddLabelModalContentProps {
  action: Function;
  closeModal: Function;
  isEdit: boolean;
  labelsKeys: string[];
  selectedLabel?: { key: string; value: string };
}

const AddLabelModalContent: React.FC<AddLabelModalContentProps> = ({
  labelsKeys,
  closeModal,
  action,
  isEdit,
  selectedLabel,
}) => {
  const affirmativeText = isEdit ? "Save" : "Add";

  function validateKey(key) {
    const keyParts: Array<string> | string = key?.split("/") ?? "";
    const prefix = keyParts[0];
    const name = keyParts[1];

    const isValidPrefix =
      key?.includes("/") && keyParts?.length === 2 ? keyPrefixRegex.test(prefix) && prefix?.length <= 253 : true;
    const isValidName = keyParts.length === 2 ? keyNameAndValueRegex.test(name) : keyNameAndValueRegex.test(key);
    if (isValidPrefix && isValidName) return true;
    else if (!isValidPrefix)
      return this.createError({
        message:
          "Key Prefix must be a DNS subdomain not longer than 253 characters and each part not longer than 63 characters.",
      });
    else
      return this.createError({
        message:
          "Key Name must have only alphanumeric and between them dashes, underscores, dots and not longer than 63 characters.",
      });
  }

  const validateValue = (value) => {
    return keyNameAndValueRegex.test(value);
  };

  return (
    <Formik
      initialValues={{
        key: selectedLabel?.key ?? "",
        value: selectedLabel?.value ?? "",
      }}
      onSubmit={(values) => {
        action(values);
        closeModal();
      }}
      validationSchema={Yup.object().shape({
        key: Yup.string()
          .required("Enter a label key")
          .notOneOf(labelsKeys, "Label key must be unique")
          .test("validate-k8s-key", "Type a valid Kubernetes key", validateKey),
        value: Yup.string()
          .required("Enter a label value")
          .test(
            "validate-k8s-value",
            "Value must have only alphanumeric and between them dashes, underscores, dots and not longer than 63 characters.",
            validateValue,
          ),
      })}
    >
      {(formikProps) => {
        const { values, handleSubmit, errors, touched, handleChange, handleBlur, dirty } = formikProps;
        return (
          <ModalForm>
            <ModalBody>
              <div className={styles.modalInputContainer}>
                <TextInput
                  id="key"
                  labelText="Label Key"
                  value={values.key}
                  onChange={handleChange}
                  invalid={Boolean(errors.key && touched.key)}
                  invalidText={errors.key}
                  onBlur={handleBlur}
                />
                <TextInput
                  id="value"
                  labelText="Label Value"
                  value={values.value}
                  onChange={handleChange}
                  invalid={Boolean(errors.value && touched.value)}
                  onBlur={handleBlur}
                  invalidText={errors.value}
                />
              </div>
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" type="button" onClick={closeModal}>
                Cancel
              </Button>
              <Button disabled={Boolean(errors.key) || Boolean(errors.value) || !dirty} onClick={handleSubmit}>
                {affirmativeText}
              </Button>
            </ModalFooter>
          </ModalForm>
        );
      }}
    </Formik>
  );
};
