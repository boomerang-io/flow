//@ts-nocheck
import React from "react";
import { CodeSnippet, Button, ModalBody, ModalFooter } from "@carbon/react";
import { ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import copy from "copy-to-clipboard";
import { serviceUrl } from "Config/servicesConfig";
import styles from "./BuildWebhookModalContent.module.scss";

interface BuildWebhookModalContentProps {
  closeModal: Function;
  workflowRef: string;
}

const BuildWebhookModalContent: React.FC<BuildWebhookModalContentProps> = ({ workflowRef, closeModal }) => {
  const resourceUrl = serviceUrl.resourceTrigger();
  const webhookURL = `${resourceUrl}/webhook?workflow=${workflowRef}`;

  return (
    <ModalForm>
      <ModalBody>
        <div className={styles.container}>
          <>
            <p>
              Webhooks are used to receive data as it happens, as opposed to polling or intermittently calling an API.
            </p>
            <p>
              In systems that accept webhook, you specify a URL and subscribe to events that occur. When an event
              occurs, a HTTP request with data about the event will be sent to the URL that you specified.
            </p>
            <h2 className={styles.sectionHeader}>Configuring Webhooks</h2>
            <p>Configure an external service to push events that execute this workflow with the following URL.</p>
            <CodeSnippet
              type="single"
              copyButtonDescription="Copy URL"
              feedback="Copied to clipboard"
              onClick={() => copy(webhookURL)}
              className={styles.codeSnippet}
            >
              POST{"  "}
              {`${resourceUrl}/webhook?workflow=${workflowRef}
                &access_token=`}
            </CodeSnippet>
          </>
          <h2 className={styles.sectionHeader}>Authentication</h2>
          <p className={styles.sectionDetail}>There are two main ways to add authentication:</p>
          <ul>
            <li>Authorization header</li>
            <li>
              Adding{" "}
              <CodeSnippet type="inline" hideCopyButton className={styles.codeSnippetInline}>
                &access_token=
              </CodeSnippet>{" "}
              to the URL. The actual token is only shown at creation time.
            </li>
          </ul>
          <h2 className={styles.sectionHeader}>Payload</h2>
          <p className={styles.sectionDetail}>
            The payload submitted to the Webhook will be available as a 'data' parameter which, if JSON, you can use the
            JSONPath dot notation to access specific elements.
          </p>
        </div>
      </ModalBody>
      <ModalFooter style={{ bottom: "0", position: "absolute", width: "100%" }}>
        <Button kind="secondary" type="button" onClick={closeModal}>
          Cancel
        </Button>
      </ModalFooter>
    </ModalForm>
  );
};

export default BuildWebhookModalContent;
