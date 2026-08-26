//@ts-nocheck
import React, { Suspense, lazy, useState } from "react";
import PropTypes from "prop-types";
import { isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import { ComposedModal, TextArea } from "@boomerang-io/carbon-addons-boomerang-react";
import styles from "./TextEditorModal.module.scss";

// CodeMirror 5 (imported by TextEditorView) touches `navigator`/`document` at module scope and
// cannot be evaluated in Node - genuinely SSR-infeasible, not just an unguarded access site (see
// CLAUDE.md client-web SSR rules). ComposedModal below only invokes its `children` render prop
// once `state.isOpen` is true (client-only, post user click), so the component itself never
// renders during SSR - but a *static* import still gets eagerly evaluated by Node the moment this
// module loads, crashing regardless of whether it's rendered. Deferring to `React.lazy` makes the
// import itself lazy: the dynamic import only fires when React actually renders
// `<TextEditorView>`, which - because of the isOpen gate above - only ever happens client-side.
const TextEditorView = lazy(() => import("./TextEditorView"));

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
        <Suspense fallback={null}>
          <TextEditorView
            {...props}
            closeModal={closeModal}
            language={props.type?.includes("::") ? props.type.split("::")[1] : undefined}
            setTextAreaValue={setValue}
            value={value}
          />
        </Suspense>
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
