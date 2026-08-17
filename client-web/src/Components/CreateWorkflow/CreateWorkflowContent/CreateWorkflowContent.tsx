import React, { useRef } from "react";
import { InlineNotification } from "@carbon/react";
import { Button, ModalBody, ModalFooter } from "@carbon/react";
import { Loading, TextArea, TextInput, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import workflowIcons from "Assets/workflowIcons";
import classNames from "classnames/bind";
import { Formik } from "formik";
import { kebabCase } from "lodash";
import capitalize from "lodash/capitalize";
import * as Yup from "yup";
import { FlowTeam, CreateWorkflowSummary, WorkflowViewType } from "Types";
import styles from "./createWorkflow.module.scss";

let classnames = classNames.bind(styles);

interface CreateWorkflowContentProps {
  closeModal: () => void;
  createError: object;
  createWorkflow: (workflowSummary: CreateWorkflowSummary) => Promise<void>;
  isLoading: boolean;
  team?: FlowTeam;
  existingWorkflowNames: string[];
  teamQuotasEnabled: boolean;
  viewType: WorkflowViewType;
}

const CreateWorkflowContent: React.FC<CreateWorkflowContentProps> = ({
  closeModal,
  createError,
  createWorkflow,
  isLoading,
  team,
  existingWorkflowNames,
  teamQuotasEnabled,
  viewType,
}) => {
  const formikRef = useRef<any>();
  const hasReachedWorkflowLimit = team ? team.quotas.maxWorkflowCount <= team.quotas.currentWorkflowCount : false;
  const createWorkflowsDisabled = teamQuotasEnabled && hasReachedWorkflowLimit;

  const handleSubmit = (values: any) => {
    const requestBody = {
      name: values.name,
      displayName: values.displayName,
      description: values.description,
      icon: values.icon,
    };
    createWorkflow(requestBody);
  };

  return (
    <Formik
      innerRef={formikRef}
      initialErrors={{ name: "Name is required" }}
      initialValues={{
        name: "",
        displayName: "",
        description: "",
        icon: workflowIcons[2].name,
      }}
      onSubmit={handleSubmit}
      validationSchema={Yup.object().shape({
        name: Yup.string()
          .required("Name is required")
          .max(100, "Enter a name that is at most 100 characters in length")
          .test("regex", `Name must only contain letters, numbers, and dashes`, (value) => {
            const regex = /^[a-zA-Z0-9\-]+$/;
            if (value) {
              return regex.test(value);
            }
            return true;
          })
          .notOneOf(
            existingWorkflowNames,
            `There’s already a ${viewType} with that name in this team. Names must be unique.`,
          ),
        displayName: Yup.string().required("Please provide a name for your Workflow"),
        description: Yup.string().max(250, "Description must not be greater than 250 characters"),
      })}
    >
      {(props) => {
        const { values, touched, errors, isValid, handleChange, handleBlur, handleSubmit, setFieldValue } = props;

        return (
          <>
            {isLoading && <Loading />}
            <ModalBody aria-label="inputs" className={styles.formBody}>
              <TextInput
                id="displayName"
                label="Display Name"
                helperText="This is the name that will be displayed in the UI."
                placeholder="e.g. My Fantastical Workflow"
                value={values.displayName}
                onBlur={handleBlur}
                onChange={(e: React.FocusEvent<HTMLInputElement>) => {
                  handleChange(e);
                  setFieldValue("name", kebabCase(e.target.value.replace(/'/g, "-")));
                }}
                // onChange={handleChange}
                invalid={Boolean(errors.displayName && touched.displayName)}
                invalidText={errors.displayName}
              />
              <TextInput
                id="name"
                labelText="Name"
                placeholder="e.g. my-workflow"
                helperText="This is your unique identifier name within the Team. Can only contain letters, numbers, and dashes."
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
                  subtitle={`Request to create ${viewType} failed`}
                />
              )}
              {createWorkflowsDisabled && (
                <InlineNotification
                  lowContrast
                  kind="error"
                  title="Quotas exceeded"
                  subtitle="You cannot create new workflows for this team."
                />
              )}
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" onClick={closeModal} type="button">
                Cancel
              </Button>
              <Button
                data-testid="workflows-create-workflow-submit"
                disabled={!isValid || isLoading || createWorkflowsDisabled}
                onClick={handleSubmit}
              >
                {isLoading ? "Creating..." : "Create"}
              </Button>
            </ModalFooter>
          </>
        );
      }}
    </Formik>
  );
};

export default CreateWorkflowContent;
