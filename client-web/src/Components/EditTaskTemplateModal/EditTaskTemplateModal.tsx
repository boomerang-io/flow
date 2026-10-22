//@ts-nocheck
import React from "react";
import PropTypes from "prop-types";
import { Button } from "@carbon/react";
import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import EditTaskTemplateForm from "./EditTaskTemplateForm";
import { Edit } from "@carbon/react/icons";
import styles from "./editTaskTemplateModal.module.scss";

EditTaskTemplateModal.propTypes = {
  canEdit: PropTypes.bool,
  isActive: PropTypes.bool,
  isOldVersion: PropTypes.bool,
  setFieldValue: PropTypes.func.isRequired,
  taskTemplates: PropTypes.array.isRequired,
  values: PropTypes.object.isRequired,
};

function EditTaskTemplateModal({ isActive, isOldVersion, setFieldValue, taskTemplates, values, canEdit }) {
  const handleEditTaskTemplateModal = async ({ newValues }) => {
    let newEnvs = newValues.envs.map((env) => {
      let index = env.indexOf(":");
      return { name: env.substring(0, index), value: env.substring(index + 1, env.length) };
    });
    setFieldValue("name", newValues.name);
    setFieldValue("displayName", newValues.displayName);
    setFieldValue("description", newValues.description);
    setFieldValue("category", newValues.category);
    setFieldValue("arguments", newValues.arguments);
    setFieldValue("command", newValues.command);
    setFieldValue("image", newValues.image);
    setFieldValue("icon", newValues.icon.value);
    setFieldValue("envs", newEnvs);
    setFieldValue("script", newValues.script);
    setFieldValue("workingDir", newValues.workingDir);
  };
  return (
    <ComposedModal
      composedModalProps={{ containerClassName: styles.modalContainer }}
      confirmModalProps={{
        title: "Close this?",
        children: "Your request will not be saved",
      }}
      modalTrigger={({ openModal }) => (
        <Button
          renderIcon={Edit}
          iconDescription="edit-template"
          disabled={isOldVersion || !isActive || !canEdit}
          kind="ghost"
          size="md"
          onClick={openModal}
        />
      )}
      modalHeaderProps={{
        title: "Edit the basics",
      }}
    >
      {({ closeModal }) => (
        <EditTaskTemplateForm
          closeModal={closeModal}
          handleEditTaskTemplateModal={handleEditTaskTemplateModal}
          taskTemplates={taskTemplates}
          templateData={values}
        />
      )}
    </ComposedModal>
  );
}

export default EditTaskTemplateModal;
