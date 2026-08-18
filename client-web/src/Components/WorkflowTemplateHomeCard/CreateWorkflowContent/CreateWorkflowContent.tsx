import React, { useRef } from "react";
import { Button, Dropdown, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { Loading, TextArea, TextInput } from "@boomerang-io/carbon-addons-boomerang-react";
import { ModalFlowForm, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import workflowIcons from "Assets/workflowIcons";
import classNames from "classnames/bind";
import { Formik } from "formik";
import capitalize from "lodash/capitalize";
import * as Yup from "yup";
import { FlowWorkspaceSummary, Workflow, WorkflowTemplate } from "Types";
import styles from "./createWorkflow.module.scss";

let classnames = classNames.bind(styles);

interface CreateWorkflowContentProps {
  template: WorkflowTemplate;
  createError: any;
  createWorkflow: (workspace: string, requestBody: { name: string; description: string; icon: string }) => Promise<void>;
  isLoading: boolean;
  workspaces: Array<FlowWorkspaceSummary>;
}

const CreateWorkflowContent: React.FC<CreateWorkflowContentProps> = ({
  template,
  createError,
  createWorkflow,
  isLoading,
  workspaces,
}) => {
  const formikRef = useRef<any>();

  const handleSubmit = (values: any) => {
    const requestBody = {
      name: values.name,
      description: values.description,
      icon: values.icon,
    };
    //@ts-ignore
    createWorkflow(values.workspace, requestBody);
  };

  const workspaceOptions = workspaces?.map((t) => ({ id: t.name, text: t.displayName }));

  return (
    <Formik
      validateOnMount
      innerRef={formikRef}
      initialValues={{
        name: template.name,
        description: template.description ?? "",
        icon: template.icon,
        workspace: "",
      }}
      onSubmit={handleSubmit}
      validationSchema={Yup.object().shape({
        workspace: Yup.string().required("Workspace is required"),
        name: Yup.string().required("Name is required").max(64, "Name must not be greater than 64 characters"),
        description: Yup.string().max(250, "Description must not be greater than 250 characters"),
      })}
    >
      {(props) => {
        const { values, touched, errors, isValid, handleChange, handleBlur, handleSubmit, setFieldValue } = props;
        return (
          <ModalFlowForm>
            {isLoading && <Loading />}
            <ModalBody aria-label="inputs" className={styles.formBody}>
              <Dropdown
                id="workspace"
                type="default"
                label="Workspace"
                ariaLabel="Dropdown"
                light={false}
                items={workspaceOptions}
                itemToString={(item) => (item ? item.text : "")}
                value={values.workspace}
                className={styles.field}
                invalid={Boolean(errors.workspace && touched.workspace)}
                onChange={({ selectedItem }: any) => {
                  console.log(selectedItem);
                  setFieldValue("workspace", selectedItem.id);
                }}
              />
              <TextInput
                id="name"
                labelText="Workflow Name"
                value={values.name}
                onBlur={handleBlur}
                onChange={handleChange}
                invalid={Boolean(errors.name && touched.name)}
                invalidText={errors.name}
              />
              <TextArea
                id="description"
                labelText="Description (optional)"
                onBlur={handleBlur}
                onChange={handleChange}
                invalid={Boolean(errors.description && touched.description)}
                invalidText={errors.description}
                style={{ resize: "none", width: "100%" }}
                value={values.description}
              />
              <h2 className={styles.iconsTitle}>Pick an icon (any icon)</h2>
              <div className={styles.icons}>
                {workflowIcons.map(({ name, Icon }: any, index) => (
                  <TooltipHover direction="top" tooltipText={capitalize(name)}>
                    <label
                      key={index}
                      className={classnames(styles.icon, {
                        [styles.activeIcon]: values.icon === name,
                      })}
                    >
                      <input
                        type="radio"
                        value={name}
                        readOnly
                        onClick={() => setFieldValue("icon", name)}
                        checked={values.icon === name}
                      />
                      <Icon key={`${name}-${index}`} alt={`${name} icon`} />
                    </label>
                  </TooltipHover>
                ))}
              </div>
              {createError && (
                <InlineNotification
                  lowContrast
                  kind="error"
                  title="Something's Wrong"
                  subtitle="Request to create workflow failed"
                />
              )}
            </ModalBody>
            <ModalFooter>
              <Button
                data-testid="workflows-create-workflow-submit"
                disabled={!isValid || isLoading}
                onClick={handleSubmit}
              >
                {isLoading ? "Creating..." : "Create and View"}
              </Button>
            </ModalFooter>
          </ModalFlowForm>
        );
      }}
    </Formik>
  );
};

export default CreateWorkflowContent;
