import { ModalFlow } from "@boomerang-io/carbon-addons-boomerang-react";
import { Button } from "@carbon/react";
import { Add } from "@carbon/react/icons";
import React from "react";
import styles from "./CreateToken.module.scss";
import CreateServiceTokenForm from "./Form";
import CreateServiceTokenResult from "./Result";
import { TokenType, TokenActorKind } from "Constants";
import { TokenScopeType } from "Types";

type TokenActorKindType = (typeof TokenActorKind)[keyof typeof TokenActorKind];

interface CreateServiceTokenButtonProps {
  type: TokenScopeType;
  principal?: string | null;
  getTokensUrl: string;
  // Orthogonal to `type` - badges a `key` token minted for a Workflow's own use (see
  // TokenActorKind). Undefined for a normal human-driven token.
  actorKind?: TokenActorKindType;
  // Fires after a successful create, in addition to the Form's own
  // queryClient.invalidateQueries(getTokensUrl). Needed by loader-driven callers (the admin
  // tokens route) whose list has no react-query cache entry for invalidateQueries to hit -
  // see Form/index.tsx. Callers still on react-query reads (workspace tokens tab, the
  // workflow editor's Configure tab via TokenSection) can omit it.
  onSuccess?: () => void;
  [key: string]: any; // This allows for any additional optional props
}

function CreateServiceTokenButton({
  type,
  principal,
  getTokensUrl,
  actorKind,
  onSuccess,
  ...otherProps
}: CreateServiceTokenButtonProps) {
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
          kind={type === TokenType.User || actorKind === TokenActorKind.Workflow ? "tertiary" : "primary"}
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
        actorKind={actorKind}
        getTokensUrl={getTokensUrl}
        onSuccess={onSuccess}
      />
      <CreateServiceTokenResult />
    </ModalFlow>
  );
}

export default CreateServiceTokenButton;
