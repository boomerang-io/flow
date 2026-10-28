import React from "react";
import { Link, ModalBody } from "@carbon/react";
import { Information } from "@carbon/react/icons";
import { ComposedModal, HeaderMenuItem } from "@boomerang-io/carbon-addons-boomerang-react";
import type { ModalTriggerProps } from "Types";
import styles from "./aboutFlow.module.scss";

const SOURCE_URL = "https://github.com/boomerang-io/flow";

interface AboutFlowMenuItemProps {
  name: string;
  /** The product tag the running service-core was built from; empty when the build recorded none. */
  version?: string;
}

/** The help menu's About item: what this install is and which version it runs. */
export default function AboutFlowMenuItem({ name, version }: AboutFlowMenuItemProps) {
  return (
    <ComposedModal
      composedModalProps={{ size: "sm" }}
      modalHeaderProps={{ title: `About ${name}` }}
      modalTrigger={({ openModal }: ModalTriggerProps) => (
        <HeaderMenuItem
          type="button"
          icon={<Information />}
          text={`About ${name}`}
          onClick={openModal}
          data-testid="about-flow"
        />
      )}
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
