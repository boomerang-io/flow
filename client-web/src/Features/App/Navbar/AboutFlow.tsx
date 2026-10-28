import React from "react";
import { Link, ModalBody } from "@carbon/react";
import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import styles from "./aboutFlow.module.scss";

export const PRODUCT_NAME = "Boomerang Flow";
const SOURCE_URL = "https://github.com/boomerang-io/flow";

interface AboutFlowModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** The product tag the running service-core was built from; empty when the build recorded none. */
  version?: string;
}

/**
 * What this install is and which version it runs. Opened from the help menu, but rendered outside
 * it: the menu unmounts its items when an item is clicked, which would take the modal with it.
 */
export default function AboutFlowModal({ isOpen, onClose, version }: AboutFlowModalProps) {
  return (
    <ComposedModal
      isOpen={isOpen}
      onCloseModal={onClose}
      composedModalProps={{ size: "sm" }}
      modalHeaderProps={{ title: `About ${PRODUCT_NAME}` }}
    >
      {() => (
        <ModalBody className={styles.body}>
          <p className={styles.version}>{version ? `Version ${version}` : "Version not recorded"}</p>
          <p className={styles.text}>Open source under the Apache License 2.0.</p>
          <Link href={SOURCE_URL} target="_blank" rel="noreferrer">
            Source code on GitHub
          </Link>
        </ModalBody>
      )}
    </ComposedModal>
  );
}
