import React from "react";
import { useFetcher } from "react-router-dom";
import { ComposedModal, ModalForm, Loading } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, ModalBody, ModalFooter, TextInput, InlineNotification } from "@carbon/react";
import { resourceRoute } from "Config/resourceRoutes";
import { formatErrorMessage } from "@boomerang-io/utils";
import type { ActivateActionResult } from "../../../../app/routes/resActivate";
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
  // The submit goes through the /res/activate route action (app/routes/resActivate.tsx), which
  // makes the service-core PUT with the inbound cookie forwarded - this can run unauthenticated
  // pre-first-admin, and the action carries exactly what the browser's previous direct
  // PUT /api/activate carried. The action returns upstream failures as data (never throws), so
  // the modal keeps its inline error notification.
  const fetcher = useFetcher<ActivateActionResult>();
  const isLoading = fetcher.state !== "idle";
  const failedResult = !isLoading && fetcher.data && !fetcher.data.ok ? fetcher.data : undefined;

  React.useEffect(() => {
    if (fetcher.state === "idle" && fetcher.data?.ok) {
      setActivationCode(code);
    }
  }, [fetcher.state, fetcher.data, setActivationCode, code]);

  const handleValidateCode = (e: React.SyntheticEvent) => {
    e.preventDefault();
    fetcher.submit(
      { otc: code },
      { method: "post", action: resourceRoute.activateAction(), encType: "application/json" },
    );
  };

  let errorMessage;
  if (failedResult) {
    errorMessage = formatErrorMessage({
      // formatErrorMessage reads error.response.data; the action passes the upstream body
      // through as `data`, so rebuild the axios-error shape it expects.
      error: { response: { data: failedResult.data } },
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
        {Boolean(errorMessage) && (
          <InlineNotification lowContrast kind="error" title={errorMessage.title} subtitle={errorMessage.message} />
        )}
      </ModalBody>
      <ModalFooter>
        <Button disabled={!code || isLoading} type="submit">
          {isLoading ? "Validating..." : failedResult ? "Try again?" : "Submit"}
        </Button>
      </ModalFooter>
    </ModalForm>
  );
};
