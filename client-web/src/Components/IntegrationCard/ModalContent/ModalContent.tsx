import React from "react";
import { Button, InlineNotification, Link, ModalBody, ModalFooter, Toggle } from "@carbon/react";
import { Launch } from "@carbon/react/icons";
import { ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import ReactMarkdown from "react-markdown";
import "Styles/markdown.css";
import IntegrationParameters, { IntegrationField } from "./IntegrationParameters";
import styles from "./ModalContent.module.scss";

interface ModalContentProps {
  closeModal: () => void;
  error: any;
  handleEnable: (closeModal: () => void) => void;
  handleDisable: (closeModal: () => void) => void;
  handleShare: (shared: boolean) => void;
  isSharing: boolean;
  errorMessage: { title: string; message: string } | null;
  data: any;
}

/**
 * Connect: the integration's own instructions, then Connect opens its install page. Manage: what is connected,
 * a link to it, the switch that shares its credentials with tasks when it has any, and Disconnect as the danger
 * action.
 */
const ModalContent: React.FC<ModalContentProps> = ({
  closeModal,
  error,
  handleEnable,
  handleDisable,
  handleShare,
  isSharing,
  errorMessage,
  data,
}) => {
  const isConnected = data.status === "linked";
  const fields: Array<IntegrationField> = data.fields ?? [];
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
            {fields.length > 0 && (
              <div className={styles.share}>
                <Toggle
                  id={`share-${data.name}`}
                  labelText="Share the installation token with tasks"
                  labelA="Off"
                  labelB="On"
                  toggled={Boolean(data.shared)}
                  disabled={isSharing}
                  onToggle={(checked: boolean) => handleShare(checked)}
                />
                <IntegrationParameters
                  title={data.shared ? "Parameters this integration manages" : "Parameters sharing creates"}
                  note={`Every workflow author in this workspace can then act with the ${data.name} App's permissions. They are read-only in Parameters, and switching sharing off removes them.`}
                  fields={fields}
                />
              </div>
            )}
            <p className={styles.note}>
              {`Disconnecting removes ${data.name} from this workspace, with any parameters it shares here. Anything installed on ${data.name}'s side stays until you remove it there.`}
            </p>
          </>
        ) : (
          <>
            <ReactMarkdown className="markdown-body" children={data.instructions} />
            <IntegrationParameters
              title="Parameters it can share with tasks"
              note={`Once connected, switching on "Share the installation token with tasks" creates these workspace parameters, read-only in Parameters, for every workflow author in this workspace.`}
              fields={fields}
            />
          </>
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
