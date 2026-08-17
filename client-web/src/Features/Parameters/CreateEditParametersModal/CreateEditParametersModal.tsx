//@ts-nocheck
import React from "react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { Add } from "@carbon/react/icons";
import { ComposedModal, ModalFlowForm, TextInput, Toggle } from "@boomerang-io/carbon-addons-boomerang-react";
import { updatedDiff } from "deep-object-diff";
import { Formik } from "formik";
import * as Yup from "yup";
import { InputType, PROPERTY_KEY_REGEX, PASSWORD_CONSTANT } from "Constants";
import { Property } from "Types";
import styles from "./createEditParametersModal.module.scss";

type Props = {
  handleClose?: () => void;
  handleSubmit: (isEdit: boolean, values: any, closeModal: () => void) => Promise<void>;
  isEdit?: boolean;
  isOpen?: boolean;
  isSubmitting?: boolean;
  error: boolean;
  parameter?: AbstractParam;
  parameters: AbstractParam[];
};

function CreateEditParametersModal({
  handleClose,
  handleSubmit,
  isEdit,
  isOpen,
  isSubmitting,
  error,
  parameter,
  parameters,
}: Props) {
  /**
   * arrays of values for making the key unique
   * filter out own value if editing a property, pass through all if creating
   */
  let parameterKeys: string[] | [] = [];
  if (Array.isArray(parameters)) {
    parameterKeys = parameters.map((p) => p.name);
    if (isEdit && parameter) {
      parameterKeys = parameterKeys.filter((item) => item !== parameter.name);
    }
  }

  const initialState = {
    label: parameter?.label ?? "",
    description: parameter?.description ?? "",
    name: parameter?.name ?? "",
    value: parameter?.value ?? "",
    secured: parameter?.type === InputType.Password ?? false,
  };

  const handleInternalSubmit = async (values: any, closeModal: () => void) => {
    const type = values.secured ? InputType.Password : InputType.Text;
    const newParameter = isEdit ? { ...values, type, id: parameter.id } : { ...values, type };
    delete newParameter.secured;

    if (isEdit) {
      const updatedFields = updatedDiff(initialState, newParameter);
      handleSubmit(true, updatedFields, closeModal);
    } else {
      handleSubmit(false, newParameter, closeModal);
    }
  };

  return (
    <ComposedModal
      isOpen={isOpen}
      composedModalProps={{ containerClassName: styles.modalContainer }}
      confirmModalProps={{ shouldCloseOnOverlayClick: false }}
      modalTrigger={({ openModal }) =>
        !isEdit ? (
          <Button
            data-testid="create-parameter-button"
            onClick={openModal}
            iconDescription="Create new parameter"
            renderIcon={Add}
            size="md"
            style={{ minWidth: "9rem" }}
          >
            Create new parameter
          </Button>
        ) : null
      }
      modalHeaderProps={{
        title: isEdit && parameter ? `Edit ${parameter.label.toUpperCase()}` : "Create Parameter",
        subtitle: "Parameters are available within the Workflows and Tasks.",
      }}
      onCloseModal={() => {
        if (isEdit) handleClose();
      }}
    >
      {({ closeModal }) => (
        <Form
          handleSubmit={handleInternalSubmit}
          initialState={initialState}
          isSubmitting={isSubmitting}
          isEdit={isEdit}
          error={error}
          closeModal={closeModal}
          parameterKeys={parameterKeys}
        />
      )}
    </ComposedModal>
  );
}

type FormProps = {
  closeModal: () => void;
  handleSubmit: (values: any, closeModal: () => void) => void;
  isSubmitting: boolean;
  isEdit: boolean;
  error: boolean;
  initialState: any;
  parameterKeys: Array<string>;
};

function Form({ closeModal, handleSubmit, isSubmitting, isEdit, error, initialState, parameterKeys }: FormProps) {
  // Check if key contains alpahanumeric, underscore, dash, and period chars
  const validateKey = (key: any) => {
    return PROPERTY_KEY_REGEX.test(key);
  };
  return (
    <Formik
      initialValues={initialState}
      onSubmit={(values) => handleSubmit(values, closeModal)}
      validateOnMount
      validationSchema={Yup.object().shape({
        label: Yup.string().required("Enter a label"),
        name: Yup.string()
          .required("Enter a Name")
          .max(128, "Name must not be greater than 128 characters")
          .notOneOf(parameterKeys || [], "Enter a unique name value for this parameter")
          .test(
            "is-valid-key",
            "Only alphanumeric, hyphen and underscore characters allowed. Must begin with a letter or underscore",
            validateKey,
          ),
        value: Yup.string().when("secured", {
          is: (value: boolean) => value && isEdit,
          then: Yup.string(),
          otherwise: Yup.string().required("Enter a value"),
        }),
        description: Yup.string(),
        secured: Yup.boolean(),
      })}
    >
      {(props) => {
        const { values, touched, errors, isValid, handleChange, handleBlur, handleSubmit, setFieldValue } = props;
        return (
          <ModalFlowForm onSubmit={handleSubmit}>
            <ModalBody aria-label="inputs">
              <TextInput
                id="name"
                labelText="Name"
                placeholder="Name"
                name="name"
                value={values.name}
                onBlur={handleBlur}
                onChange={handleChange}
                invalid={Boolean(errors.name && touched.name)}
                invalidText={errors.name}
              />
              <TextInput
                id="label"
                labelText="Label"
                placeholder="Label"
                name="label"
                value={values.label}
                onBlur={handleBlur}
                onChange={handleChange}
                invalid={Boolean(errors.label && touched.label)}
                invalidText={errors.label}
              />
              <TextInput
                id="description"
                labelText="Description (optional)"
                placeholder="Description"
                name="description"
                value={values.description}
                onBlur={handleBlur}
                onChange={handleChange}
              />
              <TextInput
                id="value"
                labelText="Value"
                placeholder={isEdit && values.secured ? PASSWORD_CONSTANT : "Value"}
                name="value"
                value={values.value}
                onBlur={handleBlur}
                onChange={handleChange}
                invalid={Boolean(errors.value && touched.value)}
                invalidText={errors.value}
                type={values.secured ? "password" : "text"}
                helperText={
                  isEdit && values.secured
                    ? "Secure values are stored in our database. You can provide a new value to override"
                    : null
                }
              />
              <Toggle
                id="secured-team-parameters-toggle"
                data-testid="secured-team-parameters-toggle"
                disabled={isEdit}
                labelText="Secured"
                name="secured"
                onToggle={(value: string) => setFieldValue("secured", value)}
                orientation="vertical"
                toggled={values.secured}
                helperText="Once a parameter is securely created - you will not be able to make it unsecure"
              />
              {error && (
                <InlineNotification
                  lowContrast
                  kind="error"
                  subtitle={`Request to ${isEdit ? "create" : "update"} parameter failed`}
                  title={"Something's Wrong"}
                  data-testid="create-update-parameter-notification"
                />
              )}
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" type="button" onClick={closeModal}>
                Cancel
              </Button>
              <Button
                data-testid="team-parameter-create-edit-submission-button"
                type="submit"
                disabled={!isValid || isSubmitting}
              >
                {isEdit ? (isSubmitting ? "Saving..." : "Save") : isSubmitting ? "Creating..." : "Create"}
              </Button>
            </ModalFooter>
          </ModalFlowForm>
        );
      }}
    </Formik>
  );
}

export default CreateEditParametersModal;
