//@ts-nocheck
import React from "react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { ComposedModal, ModalFlowForm, TextInput, Toggle } from "@boomerang-io/carbon-addons-boomerang-react";
import { Formik } from "formik";
import * as Yup from "yup";
import { InputType, PROPERTY_KEY_REGEX } from "Constants";
import { Property } from "Types";
import { ParameterScope, parameterReference } from "../ParametersTable/ParametersTable";
import styles from "./createEditParametersModal.module.scss";

type Props = {
  handleClose: () => void;
  handleSubmit: (isEdit: boolean, values: any, closeModal: () => void) => Promise<void>;
  isEdit?: boolean;
  isOpen?: boolean;
  isSubmitting?: boolean;
  error: boolean;
  parameter?: AbstractParam;
  parameters: AbstractParam[];
  scope: Exclude<ParameterScope, "workflow">;
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
  scope,
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

    // The backend looks the record up by `name` and otherwise replaces the whole stored
    // parameter - a partial diff would both drop the lookup key and null out any field the
    // diff omitted, so edits send the same full shape the create path does.
    handleSubmit(isEdit, newParameter, closeModal);
  };

  return (
    <ComposedModal
      isOpen={isOpen}
      composedModalProps={{ containerClassName: styles.modalContainer }}
      confirmModalProps={{ shouldCloseOnOverlayClick: false }}
      modalHeaderProps={{
        label: scope === "workspace" ? "Workspace parameter" : "Global parameter",
        title: isEdit ? "Edit parameter" : "Add parameter",
      }}
      onCloseModal={handleClose}
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
          reference={parameterReference(scope, parameter?.name ?? "name")}
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
  reference: string;
};

function Form({ closeModal, handleSubmit, isSubmitting, isEdit, error, initialState, parameterKeys, reference }: FormProps) {
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
        label: Yup.string(),
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
              {isEdit ? (
                <div className={styles.readOnlyName}>
                  <p className="cds--label">Name</p>
                  <p className={styles.name}>{values.name}</p>
                  <p className="cds--form__helper-text">{`Workflows read it as ${reference}. The name can't change once created.`}</p>
                </div>
              ) : (
                <TextInput
                  id="name"
                  labelText="Name"
                  helperText="Letters, numbers, hyphens and underscores; it can't change once created."
                  placeholder="e.g. githubToken"
                  name="name"
                  value={values.name}
                  onBlur={handleBlur}
                  onChange={handleChange}
                  invalid={Boolean(errors.name && touched.name)}
                  invalidText={errors.name}
                />
              )}
              <div className={styles.row}>
                <TextInput
                  id="label"
                  labelText="Label (optional)"
                  placeholder="e.g. GitHub token"
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
                  placeholder="What it's for"
                  name="description"
                  value={values.description}
                  onBlur={handleBlur}
                  onChange={handleChange}
                />
              </div>
              <TextInput
                id="value"
                labelText="Value"
                placeholder={isEdit && values.secured ? "Enter a new value to replace it" : "Value"}
                name="value"
                value={values.value}
                onBlur={handleBlur}
                onChange={handleChange}
                invalid={Boolean(errors.value && touched.value)}
                invalidText={errors.value}
                type={values.secured ? "password" : "text"}
                helperText={
                  isEdit && values.secured
                    ? "The current value is hidden because the parameter is secured."
                    : null
                }
              />
              <Toggle
                id="secured-workspace-parameters-toggle"
                data-testid="secured-workspace-parameters-toggle"
                disabled={isEdit}
                labelText="Secured"
                name="secured"
                onToggle={(value: string) => setFieldValue("secured", value)}
                orientation="vertical"
                toggled={values.secured}
                helperText="Hidden here and in logs. A secured parameter can't be made unsecured."
              />
              {error && (
                <InlineNotification
                  lowContrast
                  kind="error"
                  subtitle={`Request to ${isEdit ? "update" : "create"} parameter failed`}
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
                data-testid="workspace-parameter-create-edit-submission-button"
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
