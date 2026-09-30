import React from "react";
import { Button, InlineNotification, Link, ModalBody, ModalFooter } from "@carbon/react";
import { Launch } from "@carbon/react/icons";
import { ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import ReactMarkdown from "react-markdown";
import "Styles/markdown.css";
import styles from "./ModalContent.module.scss";

interface ModalContentProps {
  closeModal: () => void;
  error: any;
  handleEnable: (closeModal: () => void) => void;
  handleDisable: (closeModal: () => void) => void;
  errorMessage: { title: string; message: string } | null;
  data: any;
}

/**
 * Connect: the integration's own instructions, then Connect opens its install page. Manage: what is connected,
 * a link to it, and Disconnect as the danger action.
 */
const ModalContent: React.FC<ModalContentProps> = ({
  closeModal,
  error,
  handleEnable,
  handleDisable,
  errorMessage,
  data,
}) => {
  const isConnected = data.status === "linked";
  return (
    <ModalForm>
      {error && (
        <ModalBody>
          <InlineNotification lowContrast kind="error" title={errorMessage?.title} subtitle={errorMessage?.message} />
        </ModalBody>
      )}
      <ModalBody>
        {isConnected ? (
          <>
            <dl className={styles.facts}>
              <dt>Status</dt>
              <dd>Connected to this workspace</dd>
              {data.link && (
                <>
                  <dt>Installation</dt>
                  <dd>
                    <Link href={data.link} target="_blank" rel="noopener noreferrer" renderIcon={Launch}>
                      Open {data.name}
                    </Link>
                  </dd>
                </>
              )}
            </dl>
            <p className={styles.note}>
              {`Disconnecting stops ${data.name} starting or updating workflows in this workspace. Anything installed on ${data.name}'s side stays until you remove it there.`}
            </p>
          </>
        ) : (
          <ReactMarkdown className="markdown-body" children={data.instructions} />
        )}
      </ModalBody>
      <ModalFooter>
        <Button kind="secondary" type="button" onClick={closeModal}>
          {isConnected ? "Close" : "Cancel"}
        </Button>
        <Button
          kind={isConnected ? "danger" : "primary"}
          onClick={(e: React.SyntheticEvent) => {
            e.preventDefault();
            isConnected ? handleDisable(closeModal) : handleEnable(closeModal);
          }}
        >
          {isConnected ? "Disconnect" : "Connect"}
        </Button>
      </ModalFooter>
    </ModalForm>
  );
};

export default ModalContent;
