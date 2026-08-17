import { ModalFlow } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button } from "@carbon/react";
import { Add } from "@carbon/react/icons";
import React from "react";
import styles from "./CreateToken.module.scss";
import CreateServiceTokenForm from "./Form";
import CreateServiceTokenResult from "./Result";
import { TokenType } from "Constants";
import { TokenScopeType } from "Types";

interface CreateServiceTokenButtonProps {
  type: TokenScopeType;
  principal?: string | null;
  getTokensUrl: string;
  [key: string]: any; // This allows for any additional optional props
}

function CreateServiceTokenButton({ type, principal, getTokensUrl, ...otherProps }: CreateServiceTokenButtonProps) {
  const [isTokenCreated, setIsTokenCreated] = React.useState(false);
  return (
    <ModalFlow
      composedModalProps={{
        containerClassName: isTokenCreated && styles.succesModalContainer,
        onAfterClose: () => setIsTokenCreated(false),
      }}
      modalTrigger={({ openModal }) => (
        <Button
          iconDescription="Create Token"
          onClick={openModal}
          renderIcon={Add}
          style={{ width: "12rem" }}
          size="md"
          data-testid="create-token-button"
          kind={type === TokenType.User || type === TokenType.Workflow ? "tertiary" : "primary"}
          {...otherProps}
        >
          Create token
        </Button>
      )}
      modalHeaderProps={{
        title: !isTokenCreated ? `Create new token` : "Token successfully created ",
      }}
      confirmModalProps={{
        title: "Close this?",
        children: "Make sure you have saved your token. We will not show it to you again.",
      }}
    >
      <CreateServiceTokenForm
        setIsTokenCreated={() => setIsTokenCreated(true)}
        type={type}
        principal={principal}
        getTokensUrl={getTokensUrl}
      />
      <CreateServiceTokenResult />
    </ModalFlow>
  );
}

export default CreateServiceTokenButton;
