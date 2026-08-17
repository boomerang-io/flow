import React, { useState } from "react";
import { OverflowMenu, OverflowMenuItem } from "@carbon/react";
import { ConfirmModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { DataDrivenInput } from "Types";
import CreateEditParametersModal from "../../CreateEditParametersModal";

interface OverflowMenuComponentProps {
  parameter: DataDrivenInput;
  parameters: DataDrivenInput[];
  isSubmitting: boolean;
  errorSubmitting: boolean;
  handleDelete: (parameter: DataDrivenInput) => Promise<void>;
  handleSubmit: (isEdit: boolean, parameter: any, closeModal: () => void) => Promise<void>;
}

const OverflowMenuComponent: React.FC<OverflowMenuComponentProps> = ({
  parameter,
  parameters,
  isSubmitting,
  errorSubmitting,
  handleDelete,
  handleSubmit,
}) => {
  const [deleteModalIsOpen, setDeleteModalIsOpen] = useState(false);
  const [editModalIsOpen, setEditModalIsOpen] = useState(false);

  const menuOptions = [
    {
      itemText: "Edit",
      onClick: () => setEditModalIsOpen(true),
    },
    {
      itemText: "Delete",
      onClick: () => setDeleteModalIsOpen(true),
      hasDivider: true,
      isDelete: true,
    },
  ];

  const handleEditClose = () => {
    setEditModalIsOpen(false);
  };

  const affirmativeAction = () => {
    handleDelete(parameter);
    setDeleteModalIsOpen(false);
  };

  return (
    <>
      <OverflowMenu
        flipped
        ariaLabel="Overflow menu"
        iconDescription="Overflow menu icon"
        data-testid="team-parameter-menu-button"
      >
        {menuOptions.map((option, index) => (
          <OverflowMenuItem key={index} {...option} />
        ))}
      </OverflowMenu>
      <CreateEditParametersModal
        handleClose={handleEditClose}
        handleSubmit={handleSubmit}
        isEdit
        isOpen={editModalIsOpen}
        isSubmitting={isSubmitting}
        error={errorSubmitting}
        parameter={parameter}
        parameters={parameters}
      />
      <ConfirmModal
        isOpen={deleteModalIsOpen}
        affirmativeAction={affirmativeAction}
        affirmativeButtonProps={{ kind: "danger" }}
        affirmativeText="Delete"
        negativeText="Cancel"
        onCloseModal={() => setDeleteModalIsOpen(false)}
        title={`Delete ${parameter?.label}?`}
      >
        Your parameter will be gone. Forever.
      </ConfirmModal>
    </>
  );
};

export default OverflowMenuComponent;
