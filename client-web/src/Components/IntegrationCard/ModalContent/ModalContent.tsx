import React from "react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import ReactMarkdown from "react-markdown";
import "Styles/markdown.css";

interface ModalContentProps {
  closeModal: () => void;
  error: any;
  handleEnable: (closeModal: () => void) => void;
  handleDisable: (closeModal: () => void) => void;
  errorMessage: { title: string; message: string } | null;
  data: any;
}

const ModalContent: React.FC<ModalContentProps> = ({
  closeModal,
  error,
  handleEnable,
  handleDisable,
  errorMessage,
  data,
}) => {
  return (
    <ModalForm>
      {error && (
        <ModalBody>
          <InlineNotification lowContrast kind="error" title={errorMessage?.title} subtitle={errorMessage?.message} />
        </ModalBody>
      )}
      <ModalBody>
        <ReactMarkdown className="markdown-body" children={data.instructions} />
        {data.status === "linked" && (
          <InlineNotification
            lowContrast
            kind="info"
            subtitle="Disabling this integration will remove any access to related events or triggers"
          />
        )}
      </ModalBody>
      <ModalFooter>
        <Button kind="secondary" type="button" onClick={closeModal}>
          Cancel
        </Button>
        <Button
          onClick={(e: React.SyntheticEvent) => {
            e.preventDefault();
            data.status === "unlinked" ? handleEnable(closeModal) : handleDisable(closeModal);
          }}
        >
          {data.status === "unlinked" ? "Enable" : "Disable"}
        </Button>
      </ModalFooter>
    </ModalForm>
  );
};

export default ModalContent;
