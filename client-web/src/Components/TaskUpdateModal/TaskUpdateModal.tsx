import React from "react";
import { Button, ModalBody, ModalFooter } from "@carbon/react";
import { WarningFilled, WarningAlt, Checkmark } from "@carbon/react/icons";
import { DataDrivenInput, DynamicFormik, Loading, ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import cx from "classnames";
import { useQuery } from "react-query";
import EmptyState from "Components/EmptyState";
import TextEditorModal from "Components/TextEditorModal";
import { TEXT_AREA_TYPES } from "Constants/formInputTypes";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { DataDrivenInput as DataDrivenInputConfig, ObjectValues, Task, WorkflowNode } from "Types";
import styles from "./taskUpdateModal.module.scss";

interface TaskUpdateModalProps {
  availableParameters?: Array<string>;
  closeModal: () => void;
  latestTaskTemplate: Task;
  node: WorkflowNode["data"];
  onSave: ({ inputs, version }: { inputs: Record<string, string>; version: number }) => void;
}

const UpdateType = {
  Add: "add",
  Remove: "remove",
  NoChange: "none",
} as const;

const TextEditorInput = (props: any) => {
  return (
    <div key={props.id} style={{ position: "relative", cursor: "pointer", paddingBottom: "1rem" }}>
      <TextEditorModal {...props} {...props.item} />
    </div>
  );
};

const formatAutoSuggestProperties = (availableParameters: Array<string> = []) => {
  return availableParameters.map((parameter) => ({
    value: `$(${parameter})`,
    label: parameter,
  }));
};

const isTextAreaType = (type: string): type is keyof typeof TEXT_AREA_TYPES => type in TEXT_AREA_TYPES;

const textAreaProps =
  (availableParameters?: Array<string>) =>
  ({ input, formikProps }: { input: DataDrivenInputConfig; formikProps: any }) => {
    const { values, setFieldValue } = formikProps;
    const { key, type, ...rest } = input;
    const inputKey = key ?? input.name;
    const itemConfig = isTextAreaType(type) ? TEXT_AREA_TYPES[type] : undefined;

    return {
      autoSuggestions: formatAutoSuggestProperties(availableParameters),
      formikSetFieldValue: (value: string) => setFieldValue(inputKey, value),
      initialValue: values[inputKey],
      availableParameters: availableParameters,
      item: input,
      ...itemConfig,
      ...rest,
    };
  };

const toggleProps = () => {
  return {
    orientation: "vertical",
  };
};

export default function TaskUpdateModal(props: TaskUpdateModalProps) {
  const { latestTaskTemplate, closeModal, availableParameters, node, onSave } = props;

  const getTaskTemplateUrl = serviceUrl.task.getTask({ name: node.taskRef, version: node.taskRef });

  const templateQuery = useQuery<Task>({
    queryKey: getTaskTemplateUrl,
    queryFn: resolver.query(getTaskTemplateUrl),
  });

  // Handle edge case of not finding the versions for some reason
  if (templateQuery.isLoading) {
    return (
      <ModalForm>
        <Loading />
      </ModalForm>
    );
  }

  // Handle edge case of not finding the versions for some reason
  if (templateQuery.isError) {
    return (
      <ModalForm>
        <EmptyState
          title="Something's off here"
          message="We couldn't find ones of the tasks needed for the upgrade. Please contact support for assistance upgrading."
        />
      </ModalForm>
    );
  }
  if (templateQuery.data) {
    const currentTaskTemplate = templateQuery.data;
    const currentTaskConfig = currentTaskTemplate.spec.params ?? [];
    const latestTaskConfig = latestTaskTemplate.spec.params ?? [];

    const removedInputs = currentTaskConfig
      .filter((input) => !latestTaskConfig.find((newInput) => newInput.key === input.key))
      .map((input) => input?.key);

    const addedInputs = latestTaskConfig
      .filter((input) => !currentTaskConfig.find((currentInput) => currentInput.key === input.key))
      .map((input) => `['${input?.key}']`);

    const handleSubmit = (values: Record<string, string>) => {
      onSave({ version: latestTaskTemplate.version, inputs: values });
      closeModal();
    };

    const initialValues: Record<string, unknown> = { taskName: node.name };
    currentTaskConfig.forEach((input) => {
      const inputKey = input.key ?? input.name;
      const initialValue = node.params.find((param) => param.name === input.key)?.["value"] ?? "";
      initialValues[inputKey] = Boolean(initialValue) ? initialValue : input.defaultValue;
    });

    return (
      <DynamicFormik
        allowCustomPropertySyntax
        validateOnMount
        dataDrivenInputProps={{
          TextEditor: TextEditorInput,
        }}
        initialValues={initialValues}
        inputs={latestTaskConfig}
        onSubmit={handleSubmit}
        textEditorProps={textAreaProps(availableParameters)}
        toggleProps={toggleProps}
      >
        {({ inputs, formikProps }) => {
          return (
            <ModalForm noValidate onSubmit={formikProps.handleSubmit}>
              <ModalBody className={styles.versionsContainer}>
                <VersionSection subtitle="Current version in this workflow" version={currentTaskTemplate.version}>
                  {currentTaskConfig.map((input) => {
                    const inputKey = input.key ?? input.name;
                    return (
                      <StateHighlighter
                        key={inputKey}
                        type={removedInputs.includes(input.key) ? UpdateType.Remove : UpdateType.NoChange}
                      >
                        <DataDrivenInput
                          {...input}
                          readOnly
                          id={`${inputKey}-current`}
                          orientation={input.type === "boolean" ? "vertical" : undefined}
                          value={Boolean(initialValues[inputKey]) ? initialValues[inputKey] : input.defaultValue}
                        />
                      </StateHighlighter>
                    );
                  })}
                </VersionSection>
                <div style={{ width: "1rem" }} />
                <VersionSection latest subtitle="Latest version available" version={latestTaskTemplate.version}>
                  {inputs.map((input, index) => {
                    if (!React.isValidElement<{ id?: string }>(input)) {
                      return null;
                    }
                    const inputId = input.props.id ?? String(index);
                    return (
                      <StateHighlighter
                        key={inputId}
                        type={addedInputs.includes(inputId) ? UpdateType.Add : UpdateType.NoChange}
                      >
                        {input}
                      </StateHighlighter>
                    );
                  })}
                </VersionSection>
              </ModalBody>
              <ModalFooter>
                <Button kind="secondary" onClick={closeModal}>
                  Cancel
                </Button>
                <Button disabled={Boolean(Object.values(formikProps.errors).length)} type="submit">
                  Update task
                </Button>
              </ModalFooter>
            </ModalForm>
          );
        }}
      </DynamicFormik>
    );
  }

  return null;
}

interface VersionSectionProps {
  children: React.ReactNode;
  latest?: boolean;
  subtitle: string;
  version: number;
}

function VersionSection({ children, latest = false, subtitle, version }: VersionSectionProps) {
  return (
    <section className={styles.versionSection}>
      <header className={cx(styles.versionHeader, { [styles.latest]: latest })}>
        <h2 className={styles.versionSubtitle}>{`Version ${version}`}</h2>
        <h1 className={styles.versionTitle}>{subtitle}</h1>
      </header>
      <div className={styles.versionInputsContainer}>{children}</div>
    </section>
  );
}

const ChangeToAppearanceMap: Record<
  ObjectValues<typeof UpdateType>,
  { icon: React.ReactNode; className: string; text: string }
> = {
  [UpdateType.Add]: {
    icon: <WarningFilled fill="#F1C21B" />,
    className: "add",
    text: "This field has been added.",
  },
  [UpdateType.Remove]: {
    icon: <WarningAlt fill="#DA1E28" />,
    className: "remove",
    text: "This field has been removed.",
  },
  [UpdateType.NoChange]: {
    icon: <Checkmark />,
    className: "",
    text: "This field has not changed",
  },
};

interface StateHighlighterProps {
  children: React.ReactNode;
  hidden?: boolean;
  type: ObjectValues<typeof UpdateType>;
}
function StateHighlighter({ children, hidden = false, type }: StateHighlighterProps) {
  const { className, icon, text } = ChangeToAppearanceMap[type] ?? {};

  if (hidden) {
    return <div className={styles.stateHighlighter}>{children}</div>;
  }

  return (
    <>
      <div className={cx(styles.stateHighlighter, styles[className])}>{children}</div>
      {text && (
        <p className={styles.stateHighlighterText}>
          {icon}
          <span>{text}</span>
        </p>
      )}
    </>
  );
}
