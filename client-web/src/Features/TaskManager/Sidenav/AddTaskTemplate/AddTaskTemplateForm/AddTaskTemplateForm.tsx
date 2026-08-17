//@ts-nocheck
import React from "react";
import {
  Button,
  FileUploaderDropContainer,
  FileUploaderItem,
  InlineNotification,
  ModalBody,
  ModalFooter,
} from "@carbon/react";
import {
  Creatable,
  Loading,
  ModalForm,
  TextInput,
  TextArea,
  RadioGroup,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { sentenceCase } from "change-case";
import { Formik } from "formik";
import orderBy from "lodash/orderBy";
import { useMutation } from "react-query";
import YAML from "yaml";
import * as Yup from "yup";
import SelectIcon from "Components/SelectIcon";
import { taskIcons } from "Utils/taskIcons";
import { resolver } from "Config/servicesConfig";
import { Task } from "Types";
import styles from "./addTaskTemplateForm.module.scss";

interface AddTaskTemplateFormProps {
  closeModal: () => void;
  taskNames: Array<string>;
  isSubmitting: boolean;
  createError: any;
  handleAddTaskTemplate: () => void;
  handleImportTaskTemplate: () => void;
}

const FILE_UPLOAD_MESSAGE = "Choose a file or drag one here";

/**
 * Return promise for reading file
 * @param file {File}
 * @return {Promise}
 */
const readFile = (file) => {
  const reader = new FileReader();
  return new Promise((resolve, reject) => {
    reader.onerror = () => {
      reader.abort();
      reject(new DOMException("Problem parsing input file"));
    };

    reader.onload = () => {
      try {
        // resolve(JSON.parse(reader.result));
        resolve(reader.result);
      } catch (e) {
        reject(new DOMException("Problem parsing input file as YAML"));
      }
    };
    reader.readAsText(file);
  });
};

function AddTaskTemplateForm({
  closeModal,
  isSubmitting,
  createError,
  taskNames,
  handleAddTaskTemplate,
}: AddTaskTemplateFormProps) {
  const OPTION_CREATE = "Start from scratch";
  const OPTION_IMPORT = "Import a template";
  const [selectedOption, setSelectedOption] = React.useState(OPTION_CREATE);
  const radioOptions = [
    {
      id: "create-radio-id",
      labelText: OPTION_CREATE,
      value: OPTION_CREATE,
    },
    {
      id: "import-radio-id",
      labelText: OPTION_IMPORT,
      value: OPTION_IMPORT,
    },
  ];
  const orderedIcons = orderBy(taskIcons, ["name"]);

  const {
    mutateAsync: validateYamlMuatator,
    error: validateYamlError,
    isLoading: validateYamlIsLoading,
    reset: resetValidateYaml,
  } = useMutation(resolver.postValidateYaml);

  const handleCreate = async (values) => {
    const hasFile = values.file;
    let newEnvs = values.envs.map((env) => {
      let index = env.indexOf(":");
      return { name: env.substring(0, index), value: env.substring(index + 1, env.length) };
    });
    const newTaskTemplateSpec = {
      arguments: Boolean(values.arguments) ? values.arguments.trim().split(/\n{1,}/) : [],
      command: Boolean(values.command) ? values.command.trim().split(/\n{1,}/) : [],
      envs: newEnvs,
      image: values.image,
      // params: hasFile && Boolean(values.config) ? values.config : [],
      results:
        hasFile && Boolean(values.currentRevision) && Boolean(values.currentRevision.results)
          ? values.currentRevision.results
          : [],
      script: values.script,
      workingDir: values.workingDir,
    };
    const body: Task = {
      name: values.name,
      displayName: values.displayName,
      description: values.description,
      status: "active",
      category: values.category,
      version: 1,
      icon: values.icon.value,
      type: "template",
      changelog: { reason: "Add new task" },
      spec: newTaskTemplateSpec,
    };
    await handleAddTaskTemplate({ name: values.name, replace: "false", body, closeModal });
  };

  const handleImport = async (values) => {
    if (!values.file.type === "application/json") {
      const body = values.fileData;
      body.name = values.name;
      body.displayName = values.displayName;
      body.category = values.category;
      body.description = values.description;
      body.icon = values.icon.value;
      body.changelog = { reason: "Import new task" };
    } else {
      const body = values.fileData;
      body.metadata.name = values.name;
      body.metadata.annotations["boomerang.io/displayName"] = values.displayName;
      body.metadata.annotations["boomerang.io/category"] = values.category;
      body.spec.description = values.description;
      body.metadata.annotations["boomerang.io/icon"] = values.icon.value;
      // body.changelog = { reason: "Import new task" };
    }
    await handleImportTaskTemplate({ type: values.file.type, name: values.name, replace: "false", body, closeModal });
  };

  const getTemplateData = async ({ file, setFieldValue, setFieldTouched }) => {
    try {
      const yamlFile = await readFile(file);
      setFieldValue("file", file);
      await validateYamlMuatator({ body: yamlFile });
      if (file.type === "application/json") {
        const jsonData = JSON.parse(yamlFile);
        setFieldValue("name", jsonData.name);
        setFieldTouched("name", true);
        setFieldValue("displayName", jsonData.displayName);
        setFieldTouched("displayName", true);
        setFieldValue("description", jsonData.description);
        setFieldTouched("description", true);
        setFieldValue("category", jsonData.category);
        setFieldTouched("category", true);
        const selectedIcon = orderedIcons.find((icon) => icon.name === jsonData?.icon);
        selectedIcon &&
          setFieldValue("icon", { value: selectedIcon.name, label: selectedIcon.name, icon: selectedIcon.Icon });
        setFieldValue("fileData", jsonData);
      } else {
        const yamlData = YAML.parse(yamlFile);
        setFieldValue("name", yamlData.metadata.name);
        setFieldTouched("name", true);
        setFieldValue("displayName", yamlData.metadata.annotations["boomerang.io/displayName"]);
        setFieldTouched("displayName", true);
        setFieldValue("description", yamlData.spec.description);
        setFieldTouched("description", true);
        setFieldValue("category", yamlData.metadata.annotations["boomerang.io/category"]);
        setFieldTouched("category", true);
        const selectedIcon = orderedIcons.find(
          (icon) => icon.name === yamlData.metadata.annotations["boomerang.io/icon"],
        );
        selectedIcon &&
          setFieldValue("icon", { value: selectedIcon.name, label: selectedIcon.name, icon: selectedIcon.Icon });
        setFieldValue("fileData", yamlData);
      }
    } catch (e) {
      // noop
    }
  };

  return (
    <Formik
      initialValues={{
        name: "",
        displayName: "",
        category: "",
        icon: {},
        description: "",
        arguments: "",
        command: "",
        script: "",
        workingDir: "",
        envs: [],
        fileData: {},
        file: undefined,
      }}
      validationSchema={Yup.object().shape({
        name: Yup.string()
          .required("Name is required")
          .matches(/^[a-z0-9-]+$/, "Name can only contain lowercase alphanumeric characters and dashes")
          .notOneOf(taskNames, "Enter a unique value for task name"),
        displayName: Yup.string().required("Enter a display name"),
        category: Yup.string().required("Enter a category"),
        description: Yup.string()
          .lowercase()
          .min(4, "Description must be at least four characters")
          .max(200, "The description must be less than 200 characters")
          .required("Description is required"),
        icon: Yup.object().shape({
          value: Yup.string().required(),
          label: Yup.string().required(),
        }),
        arguments: Yup.string(),
        command: Yup.string().nullable(),
        script: Yup.string().nullable(),
        workingDir: Yup.string().nullable(),
        image: Yup.string().nullable(),
        file: Yup.mixed().test(
          "fileSize",
          "File is larger than 1MiB",
          // If it's bigger than 1MiB will display the error (1048576 bytes = 1 mebibyte)
          (file) => (file?.size ? file.size < 1048576 : true),
        ),
        // .test("validFile", "File is invalid", async (file) => {
        //   let isValid = true;
        //   if (file) {
        //     try {
        //       let contents = await readFile(file);
        //       isValid = checkIsValidTask(contents);
        //     } catch (e) {
        //       console.error(e);
        //       isValid = false;
        //     }
        //   }
        //   // Need to return promise for yup to do async validation
        //   return Promise.resolve(isValid);
        // }),
      })}
      onSubmit={selectedOption === OPTION_CREATE ? handleCreate : handleImport}
      initialErrors={[{ name: "Name required" }]}
    >
      {(props) => {
        const {
          handleSubmit,
          isValid,
          values,
          errors,
          touched,
          handleChange,
          setFieldValue,
          setFieldTouched,
          handleBlur,
          resetForm,
        } = props;
        return (
          <ModalForm onSubmit={handleSubmit} className={styles.container}>
            <ModalBody>
              <div className={styles.typeRadio}>
                <RadioGroup name="options" options={radioOptions} onChange={setSelectedOption} value={selectedOption} />
              </div>
              {isSubmitting && <Loading />}
              {selectedOption === OPTION_IMPORT && (
                <div>
                  <label className={styles.fileUploaderLabel} htmlFor="uploadTemplate">
                    Import task template file (optional)
                  </label>
                  <p className={styles.fileUploaderHelper}>
                    Supported file types are .yaml and .json. Supports uploading only one file.
                  </p>
                  <FileUploaderDropContainer
                    id="uploadTemplate"
                    className={styles.fileUploader}
                    accept={[".yaml", ".json"]}
                    labelText={FILE_UPLOAD_MESSAGE}
                    name="Task"
                    multiple={false}
                    onAddFiles={(event, { addedFiles }) => {
                      getTemplateData({ file: addedFiles[0], setFieldValue, setFieldTouched });
                    }}
                  />
                  {values.file && (
                    <FileUploaderItem
                      name={values.file.name}
                      status={validateYamlIsLoading ? "uploading" : "edit"}
                      onDelete={() => {
                        setFieldValue("file", undefined);
                        if (validateYamlError) {
                          resetValidateYaml();
                        }
                        if (!errors.file) {
                          resetForm();
                        }
                      }}
                    />
                  )}
                  {Boolean(errors.file || validateYamlError) && (
                    <InlineNotification
                      className={styles.fileUploaderNotification}
                      lowContrast
                      kind="error"
                      title="Oops there was a problem with the upload. Delete it and try again."
                      subtitle="The file should contain the required fields in this form."
                    />
                  )}
                </div>
              )}
              {Boolean(
                (values.file && !errors.file && !validateYamlError && !validateYamlIsLoading) ||
                  selectedOption === OPTION_CREATE,
              ) && (
                <>
                  <TextInput
                    id="name"
                    invalid={Boolean(errors.name && touched.name)}
                    invalidText={errors.name}
                    labelText="Name"
                    helperText="Must be unique and only contain lowercase alphanumeric characters and dashes"
                    onBlur={handleBlur}
                    onChange={handleChange}
                    placeholder="my-unique-task-name"
                    value={values.name}
                  />
                  <TextInput
                    id="displayName"
                    invalid={Boolean(errors.displayName && touched.displayName)}
                    invalidText={errors.displayName}
                    labelText="Display Name"
                    onBlur={handleBlur}
                    onChange={handleChange}
                    placeholder="My Unique Task Name"
                    value={values.displayName}
                  />
                  <TextInput
                    id="category"
                    invalid={Boolean(errors.category && touched.category)}
                    invalidText={errors.category}
                    labelText="Category"
                    helperText="Categories have strict matching, type as you want to see it"
                    onBlur={handleBlur}
                    onChange={handleChange}
                    placeholder="e.g. communication"
                    value={values.category}
                  />
                  <SelectIcon
                    onChange={({ selectedItem }) => Boolean(selectedItem) && setFieldValue("icon", selectedItem)}
                    selectedIcon={values.icon}
                    iconOptions={orderedIcons}
                  />
                  <div className={styles.description}>
                    <p className={styles.descriptionLength}>{values.description?.length ?? 0}/200</p>
                    <TextArea
                      id="description"
                      invalid={Boolean(errors.description && touched.description)}
                      invalidText={errors.description}
                      labelText="Description"
                      onBlur={handleBlur}
                      onChange={handleChange}
                      value={values.description}
                    />
                  </div>
                </>
              )}
              {selectedOption === OPTION_CREATE && (
                <>
                  <TextInput
                    id="image"
                    labelText="Image (optional)"
                    helperText="Path to container image. If not specified, will use the systems default."
                    name="image"
                    value={values.image}
                    onBlur={handleBlur}
                    onChange={handleChange}
                    invalid={Boolean(errors.image && touched.image)}
                    invalidText={errors.image}
                  />
                  <TextArea
                    id="command"
                    labelText="Command (optional)"
                    helperText="Overrides the entry point of the container. Delimited by a new line."
                    name="command"
                    value={values.command}
                    onBlur={handleBlur}
                    onChange={handleChange}
                    invalid={Boolean(errors.command && touched.command)}
                    invalidText={errors.command}
                  />
                  <TextArea
                    id="arguments"
                    labelText="Arguments (optional)"
                    helperText="Enter arguments delimited by a new line"
                    placeholder="e.g. system sleep"
                    name="arguments"
                    value={values.arguments}
                    onBlur={handleBlur}
                    onChange={handleChange}
                    invalid={Boolean(errors.arguments && touched.arguments)}
                    invalidText={errors.arguments}
                  />
                  <TextArea
                    id="script"
                    invalid={Boolean(errors.script && touched.script)}
                    invalidText={errors.script}
                    labelText="Script (optional)"
                    onBlur={handleBlur}
                    onChange={handleChange}
                    value={values.script}
                  />
                  <TextInput
                    id="workingDir"
                    invalid={Boolean(errors.workingDir && touched.workingDir)}
                    invalidText={errors.workingDir}
                    labelText="Working Directory (optional)"
                    onBlur={handleBlur}
                    onChange={handleChange}
                    value={values.workingDir}
                  />
                  <Creatable
                    createKeyValuePair
                    id="envs"
                    onChange={(createdItems: string[]) => setFieldValue("envs", createdItems)}
                    keyLabelText="Environments (optional)"
                    placeholder="Enter env"
                    values={values.envs || []}
                  />
                </>
              )}
              {Boolean(createError) && (
                <InlineNotification
                  lowContrast
                  kind="error"
                  title="Failed Creating Task Template"
                  subtitle={`Unable to create the task template. Please contact support.`}
                />
              )}
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" onClick={closeModal} type="button">
                Cancel
              </Button>
              <Button disabled={!isValid || isSubmitting} type="submit">
                {selectedOption === OPTION_CREATE
                  ? !isSubmitting
                    ? "Create"
                    : "Creating..."
                  : !isSubmitting
                  ? "Import"
                  : "Importing..."}
              </Button>
            </ModalFooter>
          </ModalForm>
        );
      }}
    </Formik>
  );
}

export default AddTaskTemplateForm;
