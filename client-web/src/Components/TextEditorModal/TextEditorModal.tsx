//@ts-nocheck
import React, { useState } from "react";
import PropTypes from "prop-types";
import { isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import { ComposedModal, TextArea } from "@boomerang-io/carbon-addons-boomerang-react";
import TextEditorView from "./TextEditorView";
import styles from "./TextEditorModal.module.scss";

const TextEditorModal = (props) => {
  const [value, setValue] = useState(props.initialValue);
  console.log({ value });
  return (
    <ComposedModal
      composedModalProps={{
        containerClassName: styles.modalContainer,
      }}
      modalHeaderProps={{
        title: `Update ${props.label}`,
        subtitle: props.subtitle,
      }}
      confirmModalProps={{
        title: "Are you sure?",
        children: "Your changes will not be saved",
      }}
      modalTrigger={({ openModal }) => (
        <TextArea
          readOnly
          helperText={props.helperText}
          id={props.key}
          labelText={props.label}
          onClick={openModal}
          onKeyDown={(e) => isAccessibleKeyboardEvent(e) && openModal()}
          placeholder={props.placeholder}
          style={{ cursor: "pointer" }}
          value={value}
          tooltipContent={props.description}
        />
      )}
    >
      {({ closeModal }) => (
        <TextEditorView
          {...props}
          closeModal={closeModal}
          language={props.type?.includes("::") ? props.type.split("::")[1] : undefined}
          setTextAreaValue={setValue}
          value={value}
        />
      )}
    </ComposedModal>
  );
};

TextEditorModal.propTypes = {
  item: PropTypes.shape({
    description: PropTypes.string.isRequired,
    label: PropTypes.string.isRequired,
  }).isRequired,
};

export default TextEditorModal;
