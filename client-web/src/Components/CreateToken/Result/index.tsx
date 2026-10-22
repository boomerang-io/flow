import React from "react";
import CopyToClipboard from "react-copy-to-clipboard";
import { ModalBody, ModalFooter, Button } from "@carbon/react";
import { TextInput, ModalFlowForm } from "@boomerang-io/carbon-addons-boomerang-react";
import { CopyFile } from "@carbon/react/icons";
import styles from "./result.module.scss";

interface CreateServiceTokenResultProps {
  setShouldConfirmModalClose: (args: boolean) => void;
  formData: any;
  closeModal: () => void;
}

const CreateServiceTokenResult = (props: CreateServiceTokenResultProps | any) => {
  const handleCopyClick = () => {
    props.setShouldConfirmModalClose(false);
  };

  return (
    <ModalFlowForm>
      <ModalBody>
        <div className={styles.container}>
          <TextInput id="service-token" labelText="Token" className={styles.token} value={props.formData.token} />
          <p className={styles.saveText}>
            This is your unique token and it is not-recoverable. If you lose this token you will have to
            delete it and create another one.
          </p>
        </div>
      </ModalBody>
      <ModalFooter>
        <Button type="button" kind="secondary" onClick={props.closeModal}>
          Done
        </Button>
        <CopyToClipboard text={props.formData.token}>
          <Button type="button" onClick={handleCopyClick} renderIcon={CopyFile} iconDescription="Copy">
            Copy
          </Button>
        </CopyToClipboard>
      </ModalFooter>
    </ModalFlowForm>
  );
};

export default CreateServiceTokenResult;
