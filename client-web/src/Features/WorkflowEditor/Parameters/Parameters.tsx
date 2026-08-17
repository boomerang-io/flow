import React from "react";
import { ConfirmModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { Helmet } from "react-helmet";
import { stringToPassword } from "Utils/stringHelper";
import { InputType, WorkflowPropertyAction } from "Constants";
import { DataDrivenInput, ModalTriggerProps, WorkflowCanvas, WorkflowPropertyActionType } from "Types";
import styles from "./Parameters.module.scss";
import WorkflowPropertiesModal from "./PropertiesModal";
import WorkflowCloseButton from "./WorkflowCloseButton";

function formatDefaultValue({ type, value }: { type?: string; value?: string }) {
  if (!value) {
    return "---";
  } else if (type === InputType.Password) {
    return stringToPassword(value);
  } else {
    return value;
  }
}

interface WorkflowPropertyRowProps {
  title: string;
  value: string;
}

function WorkflowPropertyRow({ title, value }: WorkflowPropertyRowProps) {
  return (
    <dl className={styles.fieldContainer}>
      <dt className={styles.fieldKey}>{title}</dt>
      <dd className={styles.fieldValue}>{value}</dd>
    </dl>
  );
}

interface WorkflowPropertyHeaderProps {
  label?: string;
  description?: string;
}

const WorkflowPropertyHeader: React.FC<WorkflowPropertyHeaderProps> = ({ label, description }) => {
  return (
    <div className={styles.headerContainer}>
      <h1 className={styles.label}>{label}</h1>
      <p className={styles.description}>{formatDefaultValue({ value: description })}</p>
    </div>
  );
};

interface ParametersProps {
  handleUpdateParams: (parameters: Array<DataDrivenInput>, removedParameters: Array<DataDrivenInput>) => void;
  workflow: WorkflowCanvas;
}

function Parameters({ workflow, handleUpdateParams }: ParametersProps) {
  const handleUpdateProperties = ({ param, type }: { param: DataDrivenInput; type: WorkflowPropertyActionType }) => {
    let parameters = workflow.config ? [...workflow.config] : [];
    let removedParameters: Array<DataDrivenInput> = [];

    if (type === WorkflowPropertyAction.Update) {
      const parameterToUpdateIndex = parameters.findIndex((p) => p.key === param.key);
      const deletedParam = parameters.splice(parameterToUpdateIndex, 1, param)[0];
      removedParameters.push(deletedParam);
    }

    if (type === WorkflowPropertyAction.Delete) {
      const parameterToUpdateIndex = parameters.findIndex((p) => p.key === param.key);
      parameters.splice(parameterToUpdateIndex, 1);
    }

    if (type === WorkflowPropertyAction.Create) {
      parameters.push(param);
    }

    handleUpdateParams(parameters, removedParameters);
  };

  const deleteParameter = (param: DataDrivenInput) => {
    handleUpdateProperties({
      param,
      type: WorkflowPropertyAction.Delete,
    });
  };

  const { config } = workflow;
  const paramKeys = config && config.length > 0 ? config.map((input: DataDrivenInput) => input.key) : [];

  return (
    <div aria-label="Parameters" className={styles.container} role="region">
      <Helmet>
        <title>{`Parameters - ${workflow.name}`}</title>
      </Helmet>
      {config &&
        config.length > 0 &&
        config.map((configParam: DataDrivenInput, index: number) => (
          <section key={`${configParam.id}-${index}`} className={styles.property}>
            <WorkflowPropertyHeader label={configParam.label} description={configParam.description} />
            <WorkflowPropertyRow title="Name" value={configParam.name} />
            <WorkflowPropertyRow title="Type" value={configParam.type} />
            <WorkflowPropertyRow
              title="Default value"
              value={formatDefaultValue({ type: configParam.type, value: configParam.default })}
            />
            <WorkflowPropertyRow
              title="Options"
              value={formatDefaultValue({
                value: configParam.options?.map((option: { key: string }) => option.key).join(", "),
              })}
            />
            {configParam.required ? (
              <p className={styles.required}>Required</p>
            ) : (
              <p className={styles.notRequired}>Not required</p>
            )}
            {!configParam.readOnly ? (
              <>
                <WorkflowPropertiesModal
                  isEdit
                  propertyKeys={paramKeys.filter((name: string) => name !== configParam.name)}
                  property={configParam}
                  updateWorkflowProperties={handleUpdateProperties}
                />
                <ConfirmModal
                  affirmativeButtonProps={{ kind: "danger" }}
                  affirmativeAction={() => {
                    deleteParameter(configParam);
                  }}
                  affirmativeText="Delete"
                  negativeText="Cancel"
                  children="It will be gone. Forever."
                  title="Delete Parameter"
                  modalTrigger={({ openModal }: ModalTriggerProps) => (
                    <WorkflowCloseButton
                      className={styles.deleteProperty}
                      onClick={openModal}
                      data-testid="workflow-delete-parameter-button"
                    />
                  )}
                />
              </>
            ) : (
              <p className={styles.readOnlyText}>Read-only</p>
            )}
          </section>
        ))}
      <WorkflowPropertiesModal
        isEdit={false}
        propertyKeys={paramKeys}
        updateWorkflowProperties={handleUpdateProperties}
      />
    </div>
  );
}

export default Parameters;
