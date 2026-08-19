import { ComposedModal, ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, ModalBody } from "@carbon/react";
import React from "react";
import PropertiesTable from "./PropertiesTable";
import styles from "./outputPropertisLog.module.scss";
import { WorkflowRun } from "Types";

type Props = {
  isOutput?: boolean;
  results: WorkflowRun["results"];
  taskName: string;
};

function OutputPropertiesLog({ taskName, results, isOutput }: Props) {
  let propertyList: { id: string; key: string; value: string; description?: string }[] = [];
  results.forEach((result, index) =>
    propertyList.push({
      id: `${result.name}-${index}`,
      key: result.name,
      description: result.description ? result.description : "---",
      value: !result.value
        ? "---"
        : Array.isArray(result.value) || typeof result.value === "string"
        ? result.value
        : JSON.stringify(result.value),
    }),
  );

  return (
    <ComposedModal
      composedModalProps={{ containerClassName: styles.container, shouldCloseOnOverlayClick: true }}
      confirmModalProps={{
        title: "Are you sure?",
        children: "Your changes will not be saved",
      }}
      modalHeaderProps={{
        title: "Output Parameters",
        label: `${taskName}`,
      }}
      modalTrigger={({ openModal }) => (
        <Button kind="ghost" size="sm" onClick={openModal}>
          View Parameters
        </Button>
      )}
    >
      {() => (
        <ModalForm>
          <ModalBody>
            <PropertiesTable hasJsonValues={!isOutput && !Array.isArray(propertyList)} data={propertyList} />
          </ModalBody>
        </ModalForm>
      )}
    </ComposedModal>
  );
}

export default OutputPropertiesLog;
