//@ts-nocheck
import React, { Component } from "react";
import { ModalBody, ModalFooter, Button, FileUploaderDropContainer, FileUploaderItem } from "@carbon/react";
import { CheckmarkFilled, ErrorFilled } from "@carbon/react/icons";
import { ModalFlowForm } from "@boomerang-io/carbon-addons-boomerang-react";
import { Formik } from "formik";
import PropTypes from "prop-types";
import * as Yup from "yup";
import { requiredWorkflowProps } from "./constants";
import styles from "./importWorkflowContent.module.scss";

function checkIsValidWorkflow(data, workflowRef) {
  // Only check if the .json file contain the required key data
  // This validate can be improved
  console.log("checkIsValidWorkflow", data);
  let isValid = true;
  requiredWorkflowProps.forEach((prop) => {
    if (!data.hasOwnProperty(prop)) {
      isValid = false;
    }
  });

  console.log("checkIsValidWorkflowRef", data.name, workflowRef);
  if (data.name !== workflowRef) {
    isValid = false;
  }
  //Validate if workflow has the latest structure for dag
  if (!data.tasks) {
    isValid = false;
  }
  return isValid;
}

const FILE_UPLOAD_MESSAGE = "Choose a file or drag one here";
const createInvalidTextMessage = (message) => `Whoops! ${message}. Please choose a different one.`;
const VALID_TEXT_MESSAGE = (type) => `All set! This ${type} is valid, and will fully replace the existing ${type}.`;

class ImportWorkflowContent extends Component {
  static propTypes = {
    closeModal: PropTypes.func,
    confirmButtonText: PropTypes.string.isRequired,
    handleImportWorkflow: PropTypes.func,
    isLoading: PropTypes.bool,
    title: PropTypes.string.isRequired,
    workflowRef: PropTypes.string.isRequired,
    type: PropTypes.string,
  };

  /**
   * Return promise for reading file
   * @param file {File}
   * @return {Promise}
   */
  readFile = (file) => {
    const reader = new FileReader();

    return new Promise((resolve, reject) => {
      reader.onerror = () => {
        reader.abort();
        reject(new DOMException("Problem parsing input file"));
      };

      reader.onload = () => {
        try {
          resolve(JSON.parse(reader.result));
        } catch (e) {
          reject(new DOMException("Problem parsing input file as JSON"));
        }
      };

      reader.readAsText(file);
    });
  };

  handleSubmit = async (values) => {
    const fileData = await this.readFile(values.file);
    this.props.handleImportWorkflow(fileData, this.props.closeModal);
  };

  render() {
    const { title, confirmButtonText, workflowRef, type } = this.props;

    return (
      <Formik
        initialValues={{
          file: undefined,
        }}
        validateOnMount
        onSubmit={this.handleSubmit}
        validationSchema={Yup.object().shape({
          file: Yup.mixed()
            .test(
              "fileSize",
              "File is larger than 1MiB",
              // If it's bigger than 1MiB will display the error (1048576 bytes = 1 mebibyte)
              (file) => (file?.size ? file.size < 1048576 : true),
            )
            .test("validFile", "File is invalid", async (file) => {
              let isValid = true;
              if (file) {
                try {
                  let contents = await this.readFile(file);
                  isValid = checkIsValidWorkflow(contents, workflowRef);
                } catch (e) {
                  console.error(e);
                  isValid = false;
                }
              }

              // Need to return promise for yup to do async validation
              return Promise.resolve(isValid);
            }),
        })}
      >
        {(props) => {
          const { values, errors, isValid, handleSubmit, setFieldValue } = props;
          return (
            <ModalFlowForm title={title} onSubmit={handleSubmit}>
              <ModalBody
                style={{
                  height: "21rem",
                  width: "100%",
                }}
              >
                <FileUploaderDropContainer
                  accept={[".json"]}
                  labelText={FILE_UPLOAD_MESSAGE}
                  name="Workflow"
                  multiple={false}
                  onAddFiles={(event, { addedFiles }) => setFieldValue("file", addedFiles[0])}
                />
                {values.file && (
                  <FileUploaderItem
                    name={values.file.name}
                    status="edit"
                    onDelete={() => {
                      setFieldValue("file", undefined);
                    }}
                  />
                )}
                {values.file ? (
                  Boolean(errors.file) ? (
                    <div className={styles.validMessage}>
                      <ErrorFilled size={32} aria-label="error-import-icon" className={styles.errorIcon} />
                      <p className={styles.message}>{createInvalidTextMessage(errors.file)}</p>
                    </div>
                  ) : (
                    <div className={styles.validMessage}>
                      <CheckmarkFilled size={32} aria-label="success-import-icon" className={styles.successIcon} />
                      <p className={styles.message}>{VALID_TEXT_MESSAGE(type)}</p>
                    </div>
                  )
                ) : null}
              </ModalBody>
              <ModalFooter>
                <Button onClick={this.props.closeModal} kind="secondary">
                  Cancel
                </Button>
                <Button type="submit" disabled={!isValid || !Boolean(values.file)} kind="primary">
                  {confirmButtonText}
                </Button>
              </ModalFooter>
            </ModalFlowForm>
          );
        }}
      </Formik>
    );
  }
}

export default ImportWorkflowContent;
