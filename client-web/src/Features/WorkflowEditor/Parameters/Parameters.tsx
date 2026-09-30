import React from "react";
import { Helmet } from "react-helmet";
import ParametersTable from "Features/Parameters/ParametersTable";
import { WorkflowPropertyAction } from "Constants";
import { DataDrivenInput, WorkflowCanvas, WorkflowPropertyActionType } from "Types";
import styles from "./Parameters.module.scss";
import WorkflowPropertiesModal from "./PropertiesModal";

interface ParametersProps {
  handleUpdateParams: (parameters: Array<DataDrivenInput>, removedParameters: Array<DataDrivenInput>) => void;
  workflow: WorkflowCanvas;
}

function Parameters({ workflow, handleUpdateParams }: ParametersProps) {
  const [editor, setEditor] = React.useState<{ isOpen: boolean; parameter?: DataDrivenInput }>({ isOpen: false });

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

  const config = workflow.config ?? [];
  const paramKeys = config.map((input: DataDrivenInput) => input.key).filter((key): key is string => key !== undefined);

  return (
    <div aria-label="Parameters" className={styles.container} role="region">
      <Helmet>
        <title>{`Parameters - ${workflow.name}`}</title>
      </Helmet>
      <ParametersTable
        scope="workflow"
        parameters={config}
        onAdd={() => setEditor({ isOpen: true })}
        onEdit={(parameter) => setEditor({ isOpen: true, parameter })}
        onDelete={(param) => handleUpdateProperties({ param, type: WorkflowPropertyAction.Delete })}
      />
      <WorkflowPropertiesModal
        isEdit={Boolean(editor.parameter)}
        isOpen={editor.isOpen}
        key={editor.parameter?.name ?? "new"}
        onClose={() => setEditor({ isOpen: false })}
        property={editor.parameter}
        propertyKeys={paramKeys.filter((name: string) => name !== editor.parameter?.name)}
        updateWorkflowProperties={handleUpdateProperties}
        workflowName={workflow.displayName ?? workflow.name}
      />
    </div>
  );
}

export default Parameters;
