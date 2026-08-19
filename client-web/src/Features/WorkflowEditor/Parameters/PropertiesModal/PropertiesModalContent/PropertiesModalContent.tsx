// @ts-nocheck
import React, { Component } from "react";
import { Button, ModalBody, ModalFooter } from "@carbon/react";
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
            label="Default Value"
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
              label="Default Option"
              placeholder="Select option"
            />
          </>
        );
      case InputType.TextArea:
        return (
          <TextArea
            data-testid="text-area"
            id={InputProperty.DefaultValue}
            labelText="Default Value"
            onBlur={handleBlur}
            onChange={handleChange}
            placeholder="Default Value"
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
            labelText="Default Value"
            onBlur={handleBlur}
            onChange={handleChange}
            placeholder={
              this.props.isEdit && values.type.value === InputType.Password ? PASSWORD_CONSTANT : "Default Value"
            }
            type={values.type.value}
            value={values.default || ""}
            helperText={
              values.type.value === InputType.Password
                ? "Passwords are saved securely in our database. To update the saved value, provide a new default value."
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
          [InputProperty.Label]: Yup.string()
            .required("Enter a Label")
            .max(128, "Label must not be greater than 128 characters"),
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
                <TextInput
                  readOnly={isEdit}
                  helperText="Reference value for parameter in workflow. It can't be changed after parameter creation."
                  id={InputProperty.Name}
                  invalid={Boolean(errors.name && touched.name)}
                  invalidText={errors.name}
                  labelText={isEdit ? "Name (read-only)" : "Name"}
                  onBlur={handleBlur}
                  onChange={handleChange}
                  placeholder=".e.g. token"
                  value={values.name}
                />
                <ComboBox
                  id={InputProperty.Type}
                  onChange={({ selectedItem }: any) =>
                    this.handleOnTypeChange(
                      selectedItem !== null ? selectedItem : { label: "", value: "" },
                      setFieldValue,
                    )
                  }
                  items={inputTypeItems}
                  initialSelectedItem={values.type}
                  itemToString={(item: { label: string }) => item && item.label}
                  placeholder="Select an item"
                  titleText="Type"
                />
                <TextInput
                  id={InputProperty.Label}
                  invalid={Boolean(errors.label && touched.label)}
                  invalidText={errors.label}
                  labelText="Label"
                  placeholder="e.g. Token"
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
                <Toggle
                  data-testid="toggle-test-id"
                  id={InputProperty.Required}
                  labelText="Required"
                  onToggle={(value: string) =>
                    this.handleOnFieldValueChange(value, InputProperty.Required, setFieldValue)
                  }
                  orientation="vertical"
                  toggled={values.required}
                />
                {this.renderDefaultValue(formikProps)}
              </ModalBody>
              <ModalFooter>
                <Button kind="secondary" onClick={this.props.closeModal} type="button">
                  Cancel
                </Button>
                <Button disabled={!isValid || !dirty} type="submit" data-testid="parameter-modal-confirm-button">
                  {isEdit ? "Update" : "Create"}
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
