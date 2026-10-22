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
  // Orthogonal to `type` - badges a `key` token minted for a Workflow's own use (see
  // TokenActorKind). Undefined for a normal human-driven token.
  actorKind?: TokenActorKindType;
  [key: string]: any; // This allows for any additional optional props
}

// `getTokensUrl`/`onSuccess` are gone: every token surface is loader-driven now, so the Form
// submits the shared route action and revalidates the route rather than invalidating a
// react-query cache entry keyed by that URL. See Components/TokenSection/tokenRoute.ts.
function CreateServiceTokenButton({ type, principal, actorKind, ...otherProps }: CreateServiceTokenButtonProps) {
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
      />
      <CreateServiceTokenResult />
    </ModalFlow>
  );
}

export default CreateServiceTokenButton;
