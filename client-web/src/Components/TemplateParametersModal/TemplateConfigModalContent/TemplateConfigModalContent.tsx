// @ts-nocheck
import React, { Component } from "react";
import { TextInput, ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, ModalBody, ModalFooter } from "@carbon/react";
import { Formik } from "formik";
import * as Yup from "yup";
import clonedeep from "lodash/cloneDeep";
// import { InputProperty, InputType } from "Constants";
// import { FormikProps } from "formik";
import { ResultParameter } from "Types";
import styles from "./TemplateConfigModalContent.module.scss";

interface TemplateConfigModalContentProps {
  closeModal: () => void;
  forceCloseModal: () => void;
  result: ResultParameter;
  resultKeys: string[];
  isEdit: boolean;
  templateFields: ResultParameter[];
  setFieldValue: (id: string, value: any) => void;
  index: number;
}

class TemplateConfigModalContent extends Component<TemplateConfigModalContentProps> {
  handleConfirm = (values: any) => {
    let field = clonedeep(values);
    const { templateFields, setFieldValue, index } = this.props;

    if (this.props.isEdit) {
      // const fieldIndex = templateFields.findIndex((field) => field.name === this.props.field.key);
      let newProperties = [...templateFields];
      newProperties.splice(index, 1, field);
      setFieldValue("result", newProperties);
      this.props.forceCloseModal();
    } else {
      let newProperties = [...templateFields];
      newProperties.push(field);
      setFieldValue("result", newProperties);
      this.props.forceCloseModal();
    }
  };

  render() {
    const { result, isEdit, resultKeys } = this.props;
    return (
      <Formik
        validateOnMount
        onSubmit={this.handleConfirm}
        initialValues={{
          name: result?.name ?? "",
          description: result?.description ?? "",
        }}
        validationSchema={Yup.object().shape({
          name: Yup.string()
            .required("Enter a name")
            .max(64, "Name must not be greater than 64 characters")
            .notOneOf(resultKeys || [], "Enter a unique name value for this field"),
          description: Yup.string().max(200, "Description must not be greater than 200 characters"),
        })}
      >
        {(formikProps) => {
          const {
            values,
            touched,
            errors,
            handleBlur,
            handleChange,
            handleSubmit,
            // setFieldValue,
            isValid,
          } = formikProps;

          return (
            <ModalForm onSubmit={handleSubmit}>
              <ModalBody aria-label="inputs" className={styles.container}>
                <TextInput
                  id="name"
                  invalid={Boolean(errors.name && touched.name)}
                  invalidText={errors.name}
                  labelText="Name"
                  // disabled={isEdit}
                  onBlur={handleBlur}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) => handleChange(e)}
                  value={values.name}
                />
                <TextInput
                  id="description"
                  invalid={Boolean(errors.description && touched.description)}
                  invalidText={errors.description}
                  labelText="Description (optional)"
                  onBlur={handleBlur}
                  onChange={(e: React.ChangeEvent<HTMLInputElement>) => handleChange(e)}
                  value={values.description}
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
