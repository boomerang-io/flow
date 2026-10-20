import React from "react";
import { Button } from "@carbon/react";
import { TextInput } from "@boomerang-io/carbon-addons-boomerang-react";
import { REDACTED } from "Constants";
import styles from "./SecretInput.module.scss";

/**
 * A field for a secret, following the one rule the API keeps for secrets on every layer: a set
 * secret reads as the redaction marker, sending the marker back keeps it, a blank clears it and any
 * other value replaces it. The marker is never shown or typed into, so editing never appends to it.
 */
interface SecretInputProps {
  id: string;
  labelText: React.ReactNode;
  /** The form's value: the marker for an untouched stored secret, "" once cleared, or new text. */
  value: string | undefined | null;
  onChange: (value: string) => void;
  onBlur?: (event: React.FocusEvent<HTMLInputElement>) => void;
  /** Whether a secret is stored: the value the read returned was the marker. */
  isSet: boolean;
  placeholder?: string;
  helperText?: React.ReactNode;
  invalid?: boolean;
  invalidText?: React.ReactNode;
  /** Suggest a reference to a stored secret instead of a literal. */
  suggestReference?: boolean;
  /** What the field says while a stored secret is kept. */
  setHelperText?: string;
  readOnly?: boolean;
  "data-testid"?: string;
}

export const SET_HELPER = "Set. Type a new value to replace it.";
export const CLEARED_HELPER = "Cleared. Save to remove the stored value.";
export const REFERENCE_HELPER = "You can reference a stored secret instead, such as $(workspace.params.<name>).";

export default function SecretInput({
  id,
  labelText,
  value,
  onChange,
  onBlur,
  isSet,
  placeholder,
  helperText,
  invalid,
  invalidText,
  suggestReference,
  setHelperText = SET_HELPER,
  readOnly,
  "data-testid": testId,
}: SecretInputProps) {
  const [cleared, setCleared] = React.useState(false);
  const shown = value === REDACTED || value == null ? "" : value;
  const typed = shown !== "";
  const keeping = isSet && !cleared && value === REDACTED;

  const help = typed ? helperText : cleared ? CLEARED_HELPER : keeping ? setHelperText : helperText;
  const reference = suggestReference ? REFERENCE_HELPER : null;

  return (
    <div className={styles.container}>
      <TextInput
        data-testid={testId}
        id={id}
        labelText={labelText}
        type="password"
        autoComplete="new-password"
        value={shown}
        placeholder={keeping ? "Set" : placeholder}
        readOnly={readOnly}
        onBlur={onBlur}
        onChange={(event: React.ChangeEvent<HTMLInputElement>) => {
          const typed = event.target.value;
          // Deleting what was typed over a stored secret returns to keeping it, unless it was cleared.
          onChange(typed === "" && isSet && !cleared ? REDACTED : typed);
        }}
        invalid={invalid}
        invalidText={invalidText}
        helperText={
          help || reference ? (
            <>
              {help}
              {help && reference ? " " : null}
              {reference}
            </>
          ) : undefined
        }
      />
      {isSet && !readOnly && (
        <Button
          data-testid={testId ? `${testId}-${cleared ? "undo" : "clear"}` : undefined}
          kind="ghost"
          size="sm"
          type="button"
          onClick={() => {
            setCleared(!cleared);
            onChange(cleared ? REDACTED : "");
          }}
        >
          {cleared ? "Undo clear" : "Clear"}
        </Button>
      )}
    </div>
  );
}

/**
 * {@link SecretInput} as a DynamicFormik custom component: the input carries `isSet` (and
 * optionally `suggestReference`, `setHelperText`), and the value is written through Formik with
 * the bracketed key DynamicFormik uses, so a key holding dots stays one field.
 */
export function DynamicSecretInput({
  formikProps,
  id,
  label,
  helperText,
  value,
  onBlur,
  invalid,
  invalidText,
  isSet,
  suggestReference,
  setHelperText,
  readOnly,
}: {
  formikProps: { setFieldValue: (field: string, value: unknown) => void };
  id: string;
  label: React.ReactNode;
  helperText?: React.ReactNode;
  value?: string;
  onBlur?: (event: React.FocusEvent<HTMLInputElement>) => void;
  invalid?: boolean;
  invalidText?: React.ReactNode;
  isSet?: boolean;
  suggestReference?: boolean;
  setHelperText?: string;
  readOnly?: boolean;
}) {
  return (
    <SecretInput
      id={id}
      data-testid={id}
      labelText={label}
      helperText={helperText}
      value={value}
      onBlur={onBlur}
      invalid={invalid}
      invalidText={invalidText}
      isSet={Boolean(isSet)}
      suggestReference={suggestReference}
      setHelperText={setHelperText}
      readOnly={readOnly}
      onChange={(next) => formikProps.setFieldValue(`['${id}']`, next)}
    />
  );
}
