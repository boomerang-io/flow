// @ts-nocheck
import React, { Component } from "react";
import { Button, ModalBody, ModalFooter } from "@carbon/react";
import {
  ComboBox,
  Creatable,
  ModalForm,
  TextArea,
  TextInput,
  Toggle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Formik } from "formik";
import { FormikProps } from "formik";
import clonedeep from "lodash/cloneDeep";
import * as Yup from "yup";
import TextEditorModal from "Components/TextEditorModal";
import { validateUrlWithProperties } from "Utils/urlPropertySyntaxHelper";
import { InputProperty, InputType, PROPERTY_KEY_REGEX } from "Constants";
import { DataDrivenInput } from "Types";
import styles from "./TemplateConfigModalContent.module.scss";

const inputTypeOptions = [
  { label: "Boolean", value: InputType.Boolean },
  { label: "Email", value: InputType.Email },
  { label: "Number", value: InputType.Number },
  { label: "Password", value: InputType.Password },
  { label: "Select", value: InputType.Select },
  { label: "Text", value: InputType.Text },
  { label: "Text Area", value: InputType.TextArea },
  { label: "Text Editor", value: InputType.TextEditor },
  { label: "Text Editor - JavaScript/JSON", value: InputType.TextEditorJs },
  { label: "Text Editor - Shell", value: InputType.TextEditorShell },
  { label: "Text Editor - Text", value: InputType.TextEditorText },
  { label: "Text Editor - YAML", value: InputType.TextEditorYaml },
  { label: "Time", value: "time" },
  { label: "URL", value: "url" },
];

interface TextEditorInputProps extends DataDrivenInput {
  autoSuggestions: string[];
  initialValue: any;
  item?: { description: string; label: string };
  formikSetFieldValue: (key: string, value: string) => void;
  style: {};
  value: any;
}

const TextEditorInput: React.FC<TextEditorInputProps> = (props) => {
  return (
    <div key={props.id} style={{ position: "relative", cursor: "pointer", paddingBottom: "1rem" }}>
      {
        //@ts-ignore
        <TextEditorModal {...props} {...props.item} />
      }
    </div>
  );
};

interface TemplateConfigModalContentProps {
  closeModal: () => void;
  forceCloseModal: () => void;
  field: DataDrivenInput;
  fieldKeys: string[];
  isEdit: boolean;
  templateFields: DataDrivenInput[];
  setFieldValue: (id: string, value: any) => void;
}

class TemplateConfigModalContent extends Component<TemplateConfigModalContentProps> {
  state = {
    defaultValueType: InputType.Text,
  };

  handleOnTypeChange = (value: any, setFieldValue: (key: string, value: any) => void) => {
    this.setState({ defaultValueType: value });
    setFieldValue(InputProperty.Type, value);
    setFieldValue(InputProperty.DefaultValue, value === InputType.Boolean ? false : undefined);
  };

  // Check if key contains alpahanumeric, underscore, dash, and period chars
  validateKey = (name: string) => {
    return PROPERTY_KEY_REGEX.test(name);
  };

  handleConfirm = (values: any) => {
    let field = clonedeep(values);
    const { templateFields, setFieldValue } = this.props;
    // Remove in case they are present if the user changed their mind
    if (field.type !== InputType.Select) {
      delete field.options;
    } else {
      // Create options in correct type for service - { key, value }
      field.options = field.options.map((field: string) => ({ key: field, value: field }));
    }

    if (field.type === InputType.Boolean) {
      if (!field.defaultValue) field.defaultValue = false;
    }
    if (this.props.isEdit) {
      const fieldIndex = templateFields.findIndex((field) => field.name === this.props.field.name);
      let newProperties = [...templateFields];
      newProperties.splice(fieldIndex, 1, field);
      setFieldValue("currentConfig", newProperties);
      this.props.forceCloseModal();
    } else {
      let newProperties = [...templateFields];
      newProperties.push(field);
      setFieldValue("currentConfig", newProperties);
      this.props.forceCloseModal();
    }
  };

  renderDefaultValue = (formikProps: FormikProps<{ [x: string]: string | boolean | string[] }>) => {
    const { values, handleBlur, handleChange, setFieldValue, errors, touched } = formikProps;
    switch (values?.type) {
      case InputType.Boolean:
        return (
          <Toggle
            data-testid="toggle"
            id={InputProperty.DefaultValue}
            label="Default Value"
            helperText="Initial value that can be changed"
            onToggle={(value: any) => setFieldValue(InputProperty.DefaultValue, value.toString())}
            orientation="vertical"
            toggled={values.default === "true"}
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
              onChange={(createdItems: string[]) => setFieldValue(InputProperty.Options, createdItems)}
              label="Options"
              placeholder="Enter option"
              values={options || []}
            />
            <ComboBox
              data-testid="select"
              id={InputProperty.DefaultValue}
              onChange={({ selectedItem }: { selectedItem: string }) =>
                setFieldValue(InputProperty.DefaultValue, selectedItem)
              }
              items={options || []}
              initialSelectedItem={values.default || {}}
              label="Default Option"
            />
          </>
        );
      case InputType.TextArea:
        return (
          <TextArea
            data-testid="text-area"
            id={InputProperty.DefaultValue}
            labelText="Default Value (optional)"
            helperText="Initial value that can be changed"
            onBlur={handleBlur}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) => handleChange(e)}
            style={{ resize: "none" }}
            value={values.default || ""}
            invalid={Boolean(errors.label && touched.label)}
            invalidText={errors.label}
          />
        );
      case InputType.TextEditor:
      case InputType.TextEditorJs:
      case InputType.TextEditorText:
      case InputType.TextEditorShell:
      case InputType.TextEditorYaml:
        return (
          <TextEditorInput
            data-testid="texteditor"
            key="texteditor"
            id={InputProperty.DefaultValue}
            label="Default Value (optional)"
            helperText="Initial value that can be changed"
            onBlur={handleBlur}
            style={{ resize: "none" }}
            autoSuggestions={[]}
            formikSetFieldValue={(value: string) => setFieldValue(InputProperty.DefaultValue, value)}
            initialValue={values.default}
            type={values.type}
            value={values.default}
            invalid={Boolean(errors.label && touched.label)}
            invalidText={errors.label}
          />
        );
      default:
        // Fallback to text input here because it covers text, password, and url
        return (
          <TextInput
            data-testid="text-input"
            id={InputProperty.DefaultValue}
            labelText="Default Value (optional)"
            helperText="Initial value that can be changed"
            onBlur={handleBlur}
            onChange={(e: React.ChangeEvent<HTMLInputElement>) => handleChange(e)}
            type={values.type}
            value={values.default || ""}
            invalid={Boolean(errors.label && touched.label)}
            invalidText={errors.label}
          />
        );
    }
  };

  determineDefaultValueSchema = (defaultType: string) => {
    switch (defaultType) {
      case InputType.Text:
      case InputType.TextArea:
      case InputType.TextEditor:
      case InputType.Password:
        return Yup.string();
      case InputType.Boolean:
        return Yup.boolean();
      case InputType.Number:
        return Yup.number();
      case InputType.Email:
        return Yup.string().email();
      case InputType.URL:
        return Yup.string().test("hasValidUrlPropSyntax", "", (value: string) => {
          return validateUrlWithProperties({ value });
        });
      default:
        return Yup.mixed();
    }
  };

  render() {
    const { field, isEdit, fieldKeys } = this.props;
    let defaultValueType = this.state.defaultValueType;
    return (
      <Formik
        validateOnMount
        onSubmit={this.handleConfirm}
        initialValues={{
          [InputProperty.Name]: field?.name ?? "",
          [InputProperty.Label]: field?.label ?? "",
          [InputProperty.Description]: field?.description ?? "",
          [InputProperty.Placeholder]: field?.placeholder ?? "",
          [InputProperty.HelperText]: field?.helperText ?? "",
          [InputProperty.ReadOnly]: field?.readOnly ?? false,
          [InputProperty.Required]: field?.required ?? false,
          [InputProperty.Type]: field?.type ?? "",
          [InputProperty.DefaultValue]: field?.default ?? "",
          // Read in values as an array of strings. Service returns object { key, value }
          [InputProperty.Options]:
            field?.options?.map((option) => (typeof option === "object" ? option.key : option)) ?? [],
        }}
        validationSchema={Yup.object().shape({
          [InputProperty.Name]: Yup.string()
            .required("Enter a Name")
            .max(64, "Name must not be greater than 64 characters")
            .notOneOf(fieldKeys || [], "Enter a unique name value for this field")
            .test(
              "is-valid-key",
              "Only alphanumeric, hyphen and underscore characters allowed. Must begin with a letter or underscore",
              this.validateKey,
            ),
          [InputProperty.Label]: Yup.string()
            .required("Enter a Label")
            .max(64, "Label must not be greater than 64 characters"),
          [InputProperty.Description]: Yup.string().max(200, "Description must not be greater than 200 characters"),
          [InputProperty.Placeholder]: Yup.string().max(100, "Placeholder must not be greater than 100 characters"),
          [InputProperty.HelperText]: Yup.string().max(50, "Helper Text must not be greater than 50 characters"),
          [InputProperty.ReadOnly]: Yup.boolean(),
          [InputProperty.Required]: Yup.boolean(),
          [InputProperty.Type]: Yup.string().required(),
          [InputProperty.Options]: Yup.array().when(InputProperty.Type, {
            is: (type) => type === InputType.Select,
            then: Yup.array().required("Enter an option").min(1, "Enter at least one option"),
          }),
          [InputProperty.DefaultValue]: this.determineDefaultValueSchema(defaultValueType),
        })}
      >
        {(formikProps) => {
          const { values, touched, errors, handleBlur, handleChange, handleSubmit, setFieldValue, isValid } =
            formikProps;

          return (
            <ModalForm onSubmit={handleSubmit}>
              <ModalBody aria-label="inputs" className={styles.container}>
                <ComboBox
                  id={InputProperty.Type}
                  onChange={({ selectedItem }: { selectedItem: { label: string; value: string } }) =>
                    this.handleOnTypeChange(selectedItem !== null ? selectedItem.value : "", setFieldValue)
                  }
                  items={inputTypeOptions}
                  initialSelectedItem={inputTypeOptions.find((option) => option.value === values.type)}
                  itemToString={(item: { label: string }) => item && item.label}
                  placeholder="Select a type"
                  titleText="Type"
                />
                <TextInput
                  helperText="Reference value for field in task template config"
                  id={InputProperty.Name}
                  invalid={Boolean(errors.name && touched.name)}
                  invalidText={errors.name}
                  labelText="Name"
                  disabled={isEdit}
                  onBlur={handleBlur}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) => handleChange(e)}
                  placeholder="e.g. email"
                  value={values.name}
                />
                <TextInput
                  id={InputProperty.Label}
                  invalid={Boolean(errors.label && touched.label)}
                  invalidText={errors.label}
                  labelText="Label"
                  placeholder="e.g. Email"
                  value={values.label}
                  onBlur={handleBlur}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) => handleChange(e)}
                />
                <TextInput
                  id={InputProperty.HelperText}
                  invalid={Boolean(errors.helperText && touched.helperText)}
                  invalidText={errors.helperText}
                  labelText="Helper Text (optional)"
                  helperText="Assist user in completing the field"
                  onBlur={handleBlur}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) => handleChange(e)}
                  value={values.helperText}
                />
                <TextInput
                  id={InputProperty.Description}
                  invalid={Boolean(errors.description && touched.description)}
                  invalidText={errors.description}
                  labelText="Description (optional)"
                  helperText="Provide additional information about field to show in a tooltip"
                  onBlur={handleBlur}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) => handleChange(e)}
                  value={values.description}
                />
                {InputType.Boolean !== values.type && (
                  <TextInput
                    id={InputProperty.Placeholder}
                    invalid={Boolean(errors.placeholder && touched.placeholder)}
                    invalidText={errors.placeholder}
                    labelText="Placeholder (optional)"
                    helperText="Give the user a hint for the field value"
                    onBlur={handleBlur}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) => handleChange(e)}
                    value={values.placeholder}
                  />
                )}
                {this.renderDefaultValue(formikProps)}
                <Toggle
                  id={InputProperty.Required}
                  labelText="Required"
                  onToggle={(value: string) => setFieldValue(InputProperty.Required, value)}
                  orientation="vertical"
                  toggled={values.required}
                />
                <Toggle
                  id={InputProperty.ReadOnly}
                  labelText="Read-only"
                  onToggle={(value: string) => setFieldValue(InputProperty.ReadOnly, value)}
                  orientation="vertical"
                  toggled={values.readOnly}
                />
              </ModalBody>
              <ModalFooter>
                <Button kind="secondary" onClick={this.props.closeModal} type="button">
                  Cancel
                </Button>
                <Button disabled={!isValid} type="submit">
                  {isEdit ? "Save" : "Create"}
                </Button>
              </ModalFooter>
            </ModalForm>
          );
        }}
      </Formik>
    );
  }
}

export default TemplateConfigModalContent;
