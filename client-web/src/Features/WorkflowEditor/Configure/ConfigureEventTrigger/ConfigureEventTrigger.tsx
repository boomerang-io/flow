import React from "react";
import { Button, ModalBody, ModalFooter, CodeSnippet } from "@carbon/react";
import { ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import copy from "copy-to-clipboard";
import { serviceUrl } from "Config/servicesConfig";
import styles from "./ConfigureEventTrigger.module.scss";

const EXAMPLE_CLOUD_EVENT = `{
  "specversion": "1.0",
  "type": "io.boomerang.event",
  "subject": "Hello World",
  "source": "/australia",
  "id": "C234-1234-1234",
  "time": "2023-10-24T17:31:00Z",
  "datacontenttype": "application/json",
  "data": {
    "images": ["645e2bc24c223dc1cc3", "10196e304f6634cc582"],
    "pushed_at": 1.417566161e9,
    "pusher": "trustedbuilder",
    "tag": "latest"
  }
}`;

type Props = {
  closeModal: (...args: any) => void;
  workflowRef: string;
  workspace: string;
};

export default function ConfigureStorage({ closeModal, workflowRef, workspace }: Props) {
  const resourceUrl = serviceUrl.resourceTrigger();
  // The workflow by its name in the workspace; ref alone takes a workflow id.
  const webhookURL = `${resourceUrl}/event?workspace=${workspace}&ref=${workflowRef}`;

  return (
    <ModalForm>
      <ModalBody>
        <h2 className={styles.sectionHeader}>Sending Events</h2>
        <p>
          Forward events from external systems with the following URL. Add a token to the URL using{" "}
          <CodeSnippet type="inline" hideCopyButton className={styles.codeSnippetInline}>
            &access_token=
          </CodeSnippet>
          . Actual tokens are only shown at token creation time.
        </p>
        <CodeSnippet
          type="single"
          copyButtonDescription="Copy URL"
          feedback="Copied to clipboard"
          onClick={() => copy(webhookURL)}
          className={styles.codeSnippet}
        >
          POST{"  "}
          {`${webhookURL}&access_token=TOKEN`}
        </CodeSnippet>
        <h2 className={styles.sectionHeader}>Example Payload</h2>
        <p>
          A filter's field is a path into the event as it is sent: an attribute such as <b>type</b> or{" "}
          <b>subject</b>, or the data, such as <b>data.tag</b>. The run receives the attributes as the{" "}
          <b>event</b> parameter and the data as the <b>data</b> parameter, so a task reads{" "}
          <b>$(params.data.tag)</b>.
        </p>
        <CodeSnippet type="multi" hideCopyButton className={styles.codeSnippet}>
          {EXAMPLE_CLOUD_EVENT}
        </CodeSnippet>
      </ModalBody>
      <ModalFooter className={styles.stickyFooter}>
        <Button kind="secondary" type="button" onClick={closeModal}>
          Close
        </Button>
      </ModalFooter>
    </ModalForm>
  );
}
