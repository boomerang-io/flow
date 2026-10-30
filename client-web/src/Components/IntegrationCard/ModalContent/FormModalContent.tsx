import React from "react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { ModalForm, TextInput } from "@boomerang-io/carbon-addons-boomerang-react";
import "Styles/markdown.css";
import ReactMarkdown from "react-markdown";
import SecretInput from "Components/SecretInput";
import { REDACTED } from "Constants";
import IntegrationParameters, { IntegrationField } from "./IntegrationParameters";
import styles from "./ModalContent.module.scss";

type Field = IntegrationField & {
  value?: unknown;
  defaultValue?: unknown;
};

interface FormModalContentProps {
  closeModal: () => void;
  handleConnect: (values: Record<string, string>, closeModal: () => void) => void;
  handleDisable: (closeModal: () => void) => void;
  isSubmitting: boolean;
  errorMessage: { title: string; message: string } | null;
  data: any;
}

/**
 * A form integration: the workspace enters every field itself. Connect writes them as workspace
 * parameters; once connected the same form edits them, a stored secret kept until it is retyped,
 * and Disconnect removes them.
 */
const FormModalContent: React.FC<FormModalContentProps> = ({
  closeModal,
  handleConnect,
  handleDisable,
  isSubmitting,
  errorMessage,
  data,
}) => {
  const isConnected = data.status === "linked";
  const fields: Array<Field> = data.fields ?? [];
  const [values, setValues] = React.useState<Record<string, string>>(() =>
    Object.fromEntries(fields.map((field) => [field.name, String(field.value ?? field.defaultValue ?? "")])),
  );
  // The marker stands for a stored secret, so a required secret kept as it is counts as filled.
  const complete = fields.every((field) => !field.required || (values[field.name] ?? "") !== "");
  const setValue = (name: string, value: string) => setValues((current) => ({ ...current, [name]: value }));

  return (
    <ModalForm>
      {errorMessage && (
        <ModalBody>
          <InlineNotification lowContrast kind="error" title={errorMessage.title} subtitle={errorMessage.message} />
        </ModalBody>
      )}
      <ModalBody>
        {isConnected ? (
          <dl className={styles.facts}>
            <dt>Status</dt>
            <dd>Connected to this workspace</dd>
          </dl>
        ) : (
          <ReactMarkdown className="markdown-body" children={data.instructions} />
        )}
        <div className={styles.fields}>
          {fields.map((field) =>
            field.type === "password" ? (
              <SecretInput
                key={field.name}
                id={`${data.type}-${field.name}`}
                labelText={field.required ? `${field.label ?? field.name} (required)` : field.label ?? field.name}
                helperText={field.description}
                value={values[field.name]}
                isSet={String(field.value ?? "") === REDACTED}
                onChange={(value) => setValue(field.name, value)}
              />
            ) : (
              <TextInput
                key={field.name}
                id={`${data.type}-${field.name}`}
                labelText={field.required ? `${field.label ?? field.name} (required)` : field.label ?? field.name}
                helperText={field.description}
                value={values[field.name]}
                onChange={(event: React.ChangeEvent<HTMLInputElement>) => setValue(field.name, event.target.value)}
              />
            ),
          )}
        </div>
        <IntegrationParameters
          title={isConnected ? "Parameters this integration manages" : "Parameters connecting creates"}
          note={`Workspace parameters every workflow author in this workspace can use. They are read-only in Parameters, and ${
            isConnected ? "disconnecting removes them" : "Disconnect removes them again"
          }.`}
          fields={fields}
        />
      </ModalBody>
      <ModalFooter>
        <Button kind="secondary" type="button" onClick={closeModal}>
          {isConnected ? "Close" : "Cancel"}
        </Button>
        {isConnected && (
          <Button
            kind="danger"
            disabled={isSubmitting}
            onClick={(e: React.SyntheticEvent) => {
              e.preventDefault();
              handleDisable(closeModal);
            }}
          >
            Disconnect
          </Button>
        )}
        <Button
          kind="primary"
          disabled={!complete || isSubmitting}
          onClick={(e: React.SyntheticEvent) => {
            e.preventDefault();
            handleConnect(values, closeModal);
          }}
        >
          {isConnected ? "Save" : "Connect"}
        </Button>
      </ModalFooter>
    </ModalForm>
  );
};

export default FormModalContent;
