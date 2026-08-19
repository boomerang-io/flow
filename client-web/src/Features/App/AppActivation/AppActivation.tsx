import React from "react";
import { useMutation } from "react-query";
import { ComposedModal, ModalForm, Loading } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, ModalBody, ModalFooter, TextInput, InlineNotification } from "@carbon/react";
import { resolver } from "Config/servicesConfig";
import { formatErrorMessage } from "@boomerang-io/utils";
import styles from "./AppActivation.module.scss";

interface PlatformActivationProps {
  setActivationCode: (code: string) => void;
}

const AppActivation: React.FC<PlatformActivationProps> = ({ setActivationCode }) => {
  return (
    <ComposedModal
      isOpen
      composedModalProps={{
        containerClassName: styles.container,
        shouldCloseOnOverlayClick: false,
        shouldCloseOnEsc: false,
      }}
      modalHeaderProps={{
        title: `G’day! Let’s activate Boomerang Flow`,
        subtitle: (
          <>
            <span className={styles.break}>
              To ensure that Boomerang Flow is secure, we have generated a one-time token during the installation
              process that can be used to complete the post-installation steps and activate this Boomerang Flow
              instance.
            </span>
            <span className={styles.break}>Your user will be created and granted admin rights.</span>
          </>
        ),
      }}
    >
      {() => <Form setActivationCode={setActivationCode} />}
    </ComposedModal>
  );
};

export default AppActivation;

const Form: React.FC<PlatformActivationProps> = ({ setActivationCode }) => {
  const [code, setCode] = React.useState("");
  const { mutateAsync: validateActivationCodeMutator, isLoading, error } = useMutation(resolver.putActivationApp);

  const handleValidateCode = async (e: React.SyntheticEvent) => {
    e.preventDefault();
    try {
      await validateActivationCodeMutator({ body: { otc: code } });
      setActivationCode(code);
    } catch (err) {
      // no-op
    }
  };

  let errorMessage;
  if (error) {
    errorMessage = formatErrorMessage({
      error,
      defaultTitle: "Invalid Code",
      defaultMessage: "That doesn't match what we have saved",
    });
  }

  return (
    <ModalForm onSubmit={handleValidateCode}>
      {isLoading && <Loading />}
      <ModalBody>
        <TextInput
          id="activation-code"
          labelText="Activation code"
          helperText="Look for it in your shell"
          onChange={(e) => setCode(e.target.value)}
        />
        {error && (
          <InlineNotification lowContrast kind="error" title={errorMessage.title} subtitle={errorMessage.message} />
        )}
      </ModalBody>
      <ModalFooter>
        <Button disabled={!code || isLoading} type="submit">
          {isLoading ? "Validating..." : error ? "Try again?" : "Submit"}
        </Button>
      </ModalFooter>
    </ModalForm>
  );
};
