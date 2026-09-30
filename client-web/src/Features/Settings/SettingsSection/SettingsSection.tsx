import React from "react";
import { Button } from "@carbon/react";
import { DynamicFormik } from "@boomerang-io/carbon-addons-boomerang-react";
import { Save } from "@carbon/react/icons";
import { FormikProps } from "formik";
import { useAppContext } from "Hooks";
import { UserRole } from "Constants";
import { DataDrivenInput } from "Types";
import { SettingsGroup } from "../Settings";
import styles from "./settingsSection.module.scss";

interface SettingsSectionProps {
  settingsGroup: SettingsGroup;
  onSave: (
    values: { [key: string]: any },
    settingsGroup: SettingsGroup,
    setFieldError: (key: string, value: string) => void,
  ) => void;
}

/**
 * One settings group as a form: the group's name and description, its inputs in a single
 * column, and a save bar that only wakes up when something changed. An operator sees the
 * values read-only and no save bar.
 */
const SettingsSection: React.FC<SettingsSectionProps> = ({ onSave, settingsGroup }) => {
  const { user: userData } = useAppContext();
  const isOperator = userData.type === UserRole.Operator;

  // Formik reads dotted keys as nested paths; the inputs carry the raw key as a test id so a
  // test (and the end-to-end journey) can find a field without depending on its label.
  const formattedInputs = settingsGroup.config.map((input: DataDrivenInput) => ({
    ...input,
    "data-testid": input.key,
  }));

  return (
    <section className={styles.section} id={settingsGroup.key} data-testid="settings-section" aria-labelledby={`${settingsGroup.key}-title`}>
      <header className={styles.header}>
        <h2 id={`${settingsGroup.key}-title`} className={styles.title}>
          {settingsGroup.name}
        </h2>
        <p className={styles.description}>{settingsGroup.description}</p>
      </header>
      <DynamicFormik
        enableReinitialize
        inputs={formattedInputs}
        onSubmit={(values: {}, props: FormikProps<any>) => onSave(values, settingsGroup, props.setFieldError)}
        initialErrors={{ initialerror: "required" }}
        toggleProps={({ input }: { input: DataDrivenInput }) => {
          return {
            orientation: "vertical",
            "data-testid": input.key,
          };
        }}
        inputProps={isOperator ? { readOnly: true } : {}}
      >
        {({ inputs, formikProps }: { inputs: React.ReactNode[]; formikProps: FormikProps<any> }) => {
          return (
            <>
              <div className={styles.form}>{inputs}</div>
              {!isOperator && (
                <footer className={styles.saveBar}>
                  <span className={styles.saveHint}>
                    {formikProps.dirty ? "You have unsaved changes." : "No unsaved changes."}
                  </span>
                  <Button kind="secondary" size="md" disabled={!formikProps.dirty} onClick={() => formikProps.resetForm()}>
                    Discard
                  </Button>
                  <Button
                    disabled={!formikProps.isValid || !formikProps.dirty}
                    iconDescription="Save settings"
                    onClick={() => formikProps.handleSubmit()}
                    renderIcon={Save}
                    size="md"
                  >
                    Save
                  </Button>
                </footer>
              )}
            </>
          );
        }}
      </DynamicFormik>
    </section>
  );
};

export default SettingsSection;
