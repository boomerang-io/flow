// @ts-nocheck
import React, { Component } from "react";
import { Button, Dropdown, ModalBody, ModalFooter, TextInput as CarbonTextInput } from "@carbon/react";
import {
  ComboBox,
  Creatable,
  ModalFlowForm,
  TextArea,
  TextInput,
  Toggle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Formik, FormikProps } from "formik";
import clonedeep from "lodash/cloneDeep";
import * as Yup from "yup";
import { validateUrlWithProperties } from "Utils/urlPropertySyntaxHelper";
import {
  InputProperty,
  InputType,
  InputTypeCopy,
  WorkflowPropertyAction,
  PROPERTY_KEY_REGEX,
  PASSWORD_CONSTANT,
} from "Constants";
import { DataDrivenInput, FormikSetFieldValue, WorkflowPropertyActionType } from "Types";
import styles from "./PropertiesModalContent.module.scss";

const textInputItem = { label: InputTypeCopy[InputType.Text], value: InputType.Text };

const inputTypeItems = [
  { label: InputTypeCopy[InputType.Boolean], value: InputType.Boolean },
  { label: InputTypeCopy[InputType.Email], value: InputType.Email },
  { label: InputTypeCopy[InputType.Number], value: InputType.Number },
  { label: InputTypeCopy[InputType.Password], value: InputType.Password },
  { label: InputTypeCopy[InputType.Select], value: InputType.Select },
  { label: InputTypeCopy[InputType.Text], value: InputType.Text },
  { label: InputTypeCopy[InputType.TextArea], value: InputType.TextArea },
  { label: InputTypeCopy[InputType.URL], value: InputType.URL },
];

interface PropertiesModalContentProps {
  closeModal(): void;
  isEdit: boolean;
  property?: DataDrivenInput;
  propertyKeys: string[];
  updateWorkflowProperties: (args: { param: DataDrivenInput; type: WorkflowPropertyActionType }) => void;
}

class PropertiesModalContent extends Component<PropertiesModalContentProps> {
  state = {
    defaultValueType: "text",
  };

  handleOnFieldValueChange = (value: any, id: string, setFieldValue: FormikSetFieldValue) => {
    setFieldValue(id, value);
  };

  handleOnTypeChange = (selectedItem: any, setFieldValue: FormikSetFieldValue) => {
    this.setState({ defaultValueType: selectedItem.value });
    setFieldValue(InputProperty.Type, selectedItem);
    setFieldValue(InputProperty.DefaultValue, selectedItem.value === InputType.Boolean ? false : undefined);
  };

  // Only save an array of strings to match api and simplify renderDefaultValue()
  handleOptionsChange = (values: [string], setFieldValue: (id: string, values: [string]) => void) => {
    setFieldValue(InputProperty.Options, values);
  };

  // Check if key contains alpahanumeric, underscore, dash, and period chars
  validateKey = (key: string) => {
    return PROPERTY_KEY_REGEX.test(key);
  };

  handleConfirm = (values: DataDrivenInput) => {
    let param = clonedeep(values);
    param.type = param.type.value;

    // Remove in case they are present if the user changed their mind
    if (param.type !== InputType.Select) {
      delete param.options;
    } else {
      // Create options in correct type for service - { key, value }
      param.options = param?.options.map((param) => ({ key: param, value: param }));
    }

    if (param.type === InputType.Boolean) {
      if (!param.defaultValue) param.defaultValue = false;
    }

    if (this.props.isEdit) {
      this.props.updateWorkflowProperties({
        param,
        type: WorkflowPropertyAction.Update,
      });
      this.props.closeModal();
    } else {
      this.props.updateWorkflowProperties({
        param,
        type: WorkflowPropertyAction.Create,
      });
      this.props.closeModal();
    }
  };

  renderDefaultValue = (formikProps: FormikProps) => {
    const { values, handleBlur, handleChange, setFieldValue } = formikProps;

    switch (values?.type?.value) {
      case InputType.Boolean:
        return (
          <Toggle
            data-testid="toggle"
            id={InputProperty.DefaultValue}
            labelText="Default value"
            onToggle={(value: string | boolean) =>
              this.handleOnFieldValueChange(value.toString(), InputProperty.DefaultValue, setFieldValue)
            }
            orientation="vertical"
            toggled={values?.default === "true"}
          />
        );
      case InputType.Select:
        // If editing an option, values will be an array of { key, value}
        let options = clonedeep(values.options);
        return (
          <>
            <Creatable
              data-testid="creatable"
              id={InputProperty.Options}
              onChange={(createdItems: any) => this.handleOptionsChange(createdItems, setFieldValue)}
              label="Options"
              placeholder="Enter option"
              values={options || []}
            />
            <ComboBox
              data-testid="select"
              id={InputProperty.DefaultValue}
              onChange={({ selectedItem }: any) =>
                this.handleOnFieldValueChange(selectedItem, InputProperty.DefaultValue, setFieldValue)
              }
              items={options || []}
              initialSelectedItem={values.default || {}}
              titleText="Default option"
              placeholder="Select option"
            />
          </>
        );
      case InputType.TextArea:
        return (
          <TextArea
            data-testid="text-area"
            id={InputProperty.DefaultValue}
            labelText="Default value (optional)"
            onBlur={handleBlur}
            onChange={handleChange}
            placeholder="No default"
            style={{ resize: "none" }}
            value={values.default || ""}
          />
        );
      default:
        // Fallback to text input here because it covers text, password, and url
        return (
          <TextInput
            data-testid="text-input"
            id={InputProperty.DefaultValue}
            labelText="Default value (optional)"
            onBlur={handleBlur}
            onChange={handleChange}
            placeholder={
              this.props.isEdit && values.type.value === InputType.Password ? PASSWORD_CONSTANT : "No default"
            }
            type={values.type.value}
            value={values.default || ""}
            helperText={
              values.type.value === InputType.Password
                ? "Hidden here and in logs. Enter a new default to replace it."
                : null
            }
          />
        );
    }
  };

  determineDefaultValueSchema = (defaultType: string) => {
    switch (defaultType) {
      case InputType.Text:
      case InputType.TextArea:
      case InputType.Password:
        return Yup.string();
      case InputType.Boolean:
        return Yup.boolean();
      case InputType.Number:
        return Yup.number();
      case InputType.URL:
        return Yup.string().test("hasValidUrlPropSyntax", "", (value: string) => {
          return validateUrlWithProperties({ value });
        });
      case InputType.Email:
        return Yup.string().email("Enter a valid email");
      default:
        return Yup.mixed();
    }
  };

  render() {
    const { property, isEdit, propertyKeys } = this.props;
    let defaultValueType = this.state.defaultValueType;

    return (
      <Formik
        validateOnMount
        onSubmit={this.handleConfirm}
        initialValues={{
          [InputProperty.Name]: property?.name ?? "",
          [InputProperty.Label]: property?.label ?? "",
          [InputProperty.Description]: property?.description ?? "",
          [InputProperty.Required]: property?.required ?? false,
          [InputProperty.Type]: property ? inputTypeItems.find((type) => type.value === property.type) : textInputItem,
          [InputProperty.DefaultValue]: property?.default ?? "",
          // Read in values as an array of strings. Service returns object { key, value }
          [InputProperty.Options]:
            property?.options?.map((option) => (typeof option === "object" ? option.key : option)) ?? [],
        }}
        validationSchema={Yup.object().shape({
          [InputProperty.Name]: Yup.string()
            .required("Enter a Name")
            .max(128, "Name must not be greater than 128 characters")
            .notOneOf(propertyKeys || [], "Enter a unique name value for this workflow")
            .test(
              "is-valid-key",
              "Only alphanumeric, hyphen and underscore characters allowed. Must begin with a letter or underscore",
              this.validateKey,
            ),
          [InputProperty.Label]: Yup.string().max(128, "Label must not be greater than 128 characters"),
          [InputProperty.Description]: Yup.string().max(128, "Description must not be greater than 128 characters"),
          [InputProperty.Required]: Yup.boolean(),
          [InputProperty.Type]: Yup.object({ label: Yup.string().required(), value: Yup.string().required() }),
          [InputProperty.Options]: Yup.array().when(InputProperty.Type, {
            is: (type) => type.value === InputType.Select,
            then: Yup.array().required("Enter an option").min(1, "Enter at least one option"),
          }),
          [InputProperty.DefaultValue]: this.determineDefaultValueSchema(defaultValueType),
        })}
      >
        {(formikProps) => {
          const { dirty, values, touched, errors, handleBlur, handleChange, handleSubmit, setFieldValue, isValid } =
            formikProps;
          return (
            <ModalFlowForm onSubmit={handleSubmit}>
              <ModalBody aria-label="inputs" className={styles.container}>
                <div className={styles.fields}>
                  {isEdit ? (
                    <div>
                      <p className="cds--label">Name</p>
                      <p className={styles.name}>{values.name}</p>
                      <p className="cds--form__helper-text">
                        {`Tasks read it as $(params.${values.name}). The name can't change once created.`}
                      </p>
                    </div>
                  ) : (
                    <TextInput
                      helperText="Letters, numbers, hyphens and underscores; it can't change once created."
                      id={InputProperty.Name}
                      invalid={Boolean(errors.name && touched.name)}
                      invalidText={errors.name}
                      labelText="Name"
                      onBlur={handleBlur}
                      onChange={handleChange}
                      placeholder="e.g. commitSHA"
                      value={values.name}
                    />
                  )}
                  <div className={styles.row}>
                    <TextInput
                      helperText="Shown when a run starts. Empty shows the name."
                      id={InputProperty.Label}
                      invalid={Boolean(errors.label && touched.label)}
                      invalidText={errors.label}
                      labelText="Label (optional)"
                      placeholder="e.g. Commit"
                      value={values.label}
                      onBlur={handleBlur}
                      onChange={handleChange}
                    />
                    <TextInput
                      id={InputProperty.Description}
                      invalid={Boolean(errors.description && touched.description)}
                      invalidText={errors.description}
                      labelText="Description (optional)"
                      onBlur={handleBlur}
                      onChange={handleChange}
                      value={values.description}
                    />
                  </div>
                  <Dropdown
                    id={InputProperty.Type}
                    items={inputTypeItems}
                    itemToString={(item: { label: string }) => (item ? item.label : "")}
                    label="Type"
                    onChange={({ selectedItem }: any) => this.handleOnTypeChange(selectedItem ?? textInputItem, setFieldValue)}
                    selectedItem={values.type}
                    titleText="Type"
                  />
                  {this.renderDefaultValue(formikProps)}
                  <Toggle
                    data-testid="toggle-test-id"
                    id={InputProperty.Required}
                    labelText="Required"
                    labelA="Not required"
                    labelB="Required — Run workflow and schedules ask for a value"
                    onToggle={(value: string) =>
                      this.handleOnFieldValueChange(value, InputProperty.Required, setFieldValue)
                    }
                    orientation="vertical"
                    toggled={values.required}
                  />
                </div>
                <aside className={styles.preview} aria-label="Preview">
                  <p className={styles.previewTitle}>When a run starts</p>
                  <div className={styles.previewField}>
                    <CarbonTextInput
                      disabled
                      helperText={values.description || undefined}
                      id="parameter-preview"
                      labelText={`${values.label || values.name || "Your parameter"}${values.required ? " *" : ""}`}
                      placeholder={values.default ? String(values.default) : ""}
                    />
                  </div>
                  <p className={styles.previewNote}>The same field appears in Run workflow and in a schedule's parameters.</p>
                </aside>
              </ModalBody>
              <ModalFooter>
                <Button kind="secondary" onClick={this.props.closeModal} type="button">
                  Cancel
                </Button>
                <Button disabled={!isValid || !dirty} type="submit" data-testid="parameter-modal-confirm-button">
                  {isEdit ? "Save" : "Add"}
                </Button>
              </ModalFooter>
            </ModalFlowForm>
          );
        }}
      </Formik>
    );
  }
}

export default PropertiesModalContent;
