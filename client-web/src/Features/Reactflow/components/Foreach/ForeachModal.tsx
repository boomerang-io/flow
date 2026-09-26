import React from "react";
import { Button, ModalBody, ModalFooter } from "@carbon/react";
import { ComboBox, ComposedModal, ModalForm, TextArea, TextInput } from "@boomerang-io/carbon-addons-boomerang-react";
import { Formik } from "formik";
import * as Yup from "yup";
import { TaskTemplateStatus } from "Constants";
import type { Task } from "Types";
import { FOREACH_MAX_ITEMS, FOREACH_TASK_TYPES, foreachItemsError } from "../shared/foreach";
import styles from "./ForeachModal.module.scss";

interface ForeachModalProps {
  isOpen: boolean;
  /** Every task by name, latest version first - the same map the canvas nodes read. */
  tasks: Record<string, Array<Task>>;
  /** Names and ids already on the canvas; the new task's name must differ from all of them. */
  takenNames: Array<string>;
  /** Suggests a unique name for a task, the way dropping it from the palette names it. */
  suggestName: (task: Task) => string;
  onCancel: () => void;
  onSubmit: (values: { task: Task; items: string; taskName: string }) => void;
}

type TaskOption = { label: string; value: string };

interface ForeachFormValues {
  taskRef: string;
  items: string;
  taskName: string;
}

/**
 * Dropping "For each" from the palette opens this: pick the task to repeat and give the items,
 * then the task's usual form opens on the new node. What lands on the canvas is an ordinary
 * task with `foreach` set.
 */
export default function ForeachModal(props: ForeachModalProps) {
  const { isOpen, onCancel, onSubmit, suggestName, takenNames, tasks } = props;

  const repeatableTasks = Object.values(tasks)
    .map((versions) => versions[0])
    .filter((task) => task && task.status === TaskTemplateStatus.Active && FOREACH_TASK_TYPES.includes(task.type))
    .sort((a, b) => a.displayName.localeCompare(b.displayName));
  const options: Array<TaskOption> = repeatableTasks.map((task) => ({ label: task.displayName, value: task.name }));

  return (
    <ComposedModal
      isOpen={isOpen}
      onCloseModal={onCancel}
      modalHeaderProps={{
        label: "Workflow",
        title: "Repeat a task for each item",
        subtitle: "Pick the task and the list it runs over. The task's own parameters come next.",
      }}
    >
      {() => (
        <Formik<ForeachFormValues>
          initialValues={{ taskRef: "", items: "", taskName: "" }}
          validateOnMount
          validationSchema={Yup.object().shape({
            taskRef: Yup.string().required("Select a task"),
            items: Yup.string().test("items", function (value) {
              const message = foreachItemsError(value);
              return message ? this.createError({ message }) : true;
            }),
            taskName: Yup.string()
              .required("Enter a task name")
              .notOneOf(takenNames, "Enter a unique value for task name"),
          })}
          onSubmit={(values) => {
            const task = repeatableTasks.find((candidate) => candidate.name === values.taskRef);
            if (task) {
              onSubmit({ task, items: values.items, taskName: values.taskName.trim() });
            }
          }}
        >
          {({
            errors,
            handleBlur,
            handleChange,
            handleSubmit,
            isValid,
            setFieldTouched,
            setFieldValue,
            touched,
            values,
          }) => (
            <ModalForm noValidate onSubmit={handleSubmit}>
              <ModalBody className={styles.body}>
                <ComboBox
                  id="foreach-task"
                  titleText="Task"
                  placeholder="Select a task"
                  helperText="The task to run once per item"
                  items={options}
                  onChange={({ selectedItem }: { selectedItem: TaskOption | null }) => {
                    const ref = selectedItem?.value ?? "";
                    setFieldValue("taskRef", ref);
                    setFieldTouched("taskRef", true, false);
                    const task = repeatableTasks.find((candidate) => candidate.name === ref);
                    // Follow the chosen task until the name has been typed by hand.
                    if (task && !touched.taskName) {
                      setFieldValue("taskName", suggestName(task));
                    }
                  }}
                  invalid={Boolean(errors.taskRef) && Boolean(touched.taskRef)}
                  invalidText={errors.taskRef}
                />
                <TextArea
                  id="items"
                  name="items"
                  labelText="Items"
                  helperText={`A JSON array from a parameter or an earlier task's result. At most ${FOREACH_MAX_ITEMS} items.`}
                  placeholder="$(tasks.stage.results.batches)"
                  invalid={Boolean(errors.items) && Boolean(touched.items)}
                  invalidText={errors.items}
                  onBlur={handleBlur}
                  onChange={handleChange}
                  value={values.items}
                />
                <TextInput
                  id="taskName"
                  name="taskName"
                  labelText="Task Name"
                  placeholder="Enter a task name"
                  invalid={Boolean(errors.taskName) && Boolean(touched.taskName)}
                  invalidText={errors.taskName}
                  onBlur={handleBlur}
                  onChange={(event: React.ChangeEvent<HTMLInputElement>) => {
                    setFieldTouched("taskName", true, false);
                    handleChange(event);
                  }}
                  value={values.taskName}
                />
              </ModalBody>
              <ModalFooter>
                <Button kind="secondary" onClick={onCancel}>
                  Cancel
                </Button>
                <Button type="submit" disabled={!isValid}>
                  Next: task parameters
                </Button>
              </ModalFooter>
            </ModalForm>
          )}
        </Formik>
      )}
    </ComposedModal>
  );
}
