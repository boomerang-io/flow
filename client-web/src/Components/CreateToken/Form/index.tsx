import React from "react";
import { Button, DatePicker, DatePickerInput, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { Information } from "@carbon/react/icons";
import {
  ModalFlowForm,
  TextArea,
  TextInput,
  Loading,
  TooltipHover,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Formik } from "formik";
import moment from "moment";
import { useMutation, useQueryClient } from "react-query";
import * as Yup from "yup";
import { TokenType, TokenActorKind } from "Constants";
import { resolver } from "Config/servicesConfig";
import { TokenScopeType } from "Types";
import PermissionSelector, { PermissionSelection } from "./PermissionSelector";
import styles from "./form.module.scss";

type TokenActorKindType = (typeof TokenActorKind)[keyof typeof TokenActorKind];

interface CreateServiceTokenFormProps {
  // Injected by ModalFlow's cloneElement at runtime (see CreateToken.tsx, which renders this
  // component as a ModalFlow child without passing these directly) - optional here only to
  // reflect that the JSX call site doesn't supply them explicitly, not because the form works
  // without them.
  closeModal?: () => void;
  goToStep?: (args: any) => void;
  saveValues?: (args: any) => void;
  setIsTokenCreated: () => any;
  type: TokenScopeType;
  // Optional/nullable to match CreateServiceTokenButtonProps.principal (CreateToken.tsx) - some
  // callers (e.g. a User-type token) have no principal at all.
  principal?: string | null;
  actorKind?: TokenActorKindType;
  getTokensUrl: string;
  // See CreateToken.tsx's CreateServiceTokenButtonProps.onSuccess for why this exists.
  onSuccess?: () => void;
}

function CreateServiceTokenForm({
  closeModal,
  goToStep,
  saveValues,
  setIsTokenCreated,
  type,
  principal,
  actorKind,
  getTokensUrl,
  onSuccess,
}: CreateServiceTokenFormProps) {
  const queryClient = useQueryClient();
  const tokenRequestMutation = useMutation(resolver.postToken);

  const createToken = async (values: any) => {
    const request = {
      name: values.name,
      type: values.type,
      expirationDate: values.date ? parseInt(moment.utc(values.date).startOf("day").format("x"), 10) : null,
      description: values.description,
      principal: values.principal,
      ...(actorKind ? { actorKind } : {}),
      // Exactly one of role/permissions travels with the request.
      ...(type !== TokenType.User ? (values.role ? { role: values.role } : { permissions: values.permissions ?? [] }) : {}),
    };

    try {
      const response = await tokenRequestMutation.mutateAsync({ body: request });
      queryClient.invalidateQueries(getTokensUrl);
      onSuccess?.();
      saveValues?.(response.data);
      setIsTokenCreated();
      goToStep?.(1);
    } catch (error) {
      //noop
    }
  };

  const handleSelectDate = (setFieldValue: any, id: string, value: any) => {
    if (Array.isArray(value) && value[0]) {
      setFieldValue("date", String(moment.utc(value[0]).format("YYYY/MM/DD")));
    } else {
      setFieldValue("date", value?.target?.value);
    }
  };

  return (
    <Formik
      initialValues={{
        name: "",
        type: type,
        expirationDate: "",
        description: "",
        principal: principal,
        role: undefined,
        permissions: undefined,
      }}
      validateOnMount
      onSubmit={(values) => createToken(values)}
      validationSchema={Yup.object().shape({
        name: Yup.string()
          .required("Name is required")
          .matches(/^[a-z0-9-]+$/, "Name can only contain lowercase alphanumeric characters and dashes"),
        expirationDate: Yup.string()
          .max(10)
          .matches(/([12]\d{3}\/(0[1-9]|1[0-2])\/(0[1-9]|[12]\d|3[01]))/, "Enter a valid date"),
        description: Yup.string().nullable(),
      })}
    >
      {({ errors, touched, handleBlur, handleSubmit, setFieldValue, isValid, isSubmitting, values }) => {
        const handlePermissionSelectionChange = (selection: PermissionSelection) => {
          setFieldValue("role", selection.role);
          setFieldValue("permissions", selection.permissions);
        };
        return (
          <ModalFlowForm className={styles.container} onSubmit={handleSubmit}>
            <ModalBody>
              {isSubmitting && <Loading />}
              <p className={styles.modalHelper}>
                This token will allow{" "}
                {type === TokenType.Global
                  ? `system wide access to the APIs. `
                  : type === TokenType.User
                    ? `access to the APIs as if they were you. `
                    : `access to the APIs as if they were this ${
                        actorKind === TokenActorKind.Workflow ? "workflow" : "workspace"
                      }. `}{" "}
                Be careful how you distribute this token.
              </p>
              <TextInput
                id="name"
                invalid={Boolean(errors.name && touched.name)}
                invalidText={errors.name}
                labelText="Name"
                helperText="Must be unique and only contain lowercase alphanumeric characters and dashes"
                onBlur={handleBlur}
                onChange={(value: any) => setFieldValue("name", value.target.value)}
                placeholder="my-unique-task-name"
                value={values.name}
              />
              {type !== TokenType.User ? (
                <PermissionSelector
                  scope={type === TokenType.Global ? "global" : "workspace"}
                  principal={principal}
                  onChange={handlePermissionSelectionChange}
                />
              ) : null}
              <DatePicker
                dateFormat="Y/m/d"
                datePickerType="single"
                onChange={(value: any) => handleSelectDate(setFieldValue, "expirationDate", value)}
                minDate={moment.utc(new Date()).add(1, "days").format("YYYY/MM/DD")}
              >
                <DatePickerInput
                  autoComplete="off"
                  data-testid="token-expiration-id"
                  id="expirationDate"
                  dateFormat="MM-DD-YYYY"
                  invalid={Boolean(errors.expirationDate)}
                  invalidText={errors.expirationDate}
                  helperText="If no expiry is set, this token will never expire!"
                  labelText={
                    <div className={styles.inputLabelContainer}>
                      <span>Expiration Date (optional)</span>
                      <TooltipHover
                        direction="top"
                        tooltipContent="Expiration date will be saved in Coordinated Universal Time (UTC) with the token expiring at
                          the start of the entered day. The token will not expire by default if no expiration date is
                          entered."
                      >
                        <Information />
                      </TooltipHover>
                    </div>
                  }
                  onChange={(value: any) => handleSelectDate(setFieldValue, "expirationDate", value)}
                  placeholder="2063/04/05"
                />
              </DatePicker>
              <TextArea
                labelText="Description (optional)"
                placeholder="Provide a short description for this Token"
                id="description"
                data-testid="token-description"
                onChange={(value: any) => setFieldValue("description", value.target.value)}
                value={values.description}
              />
              {tokenRequestMutation.error ? (
                <InlineNotification
                  lowContrast
                  className={styles.errorNotification}
                  kind="error"
                  title="Error"
                  subtitle="Failed to create this token"
                  style={{ marginTop: "1rem" }}
                />
              ) : null}
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" onClick={closeModal}>
                Cancel
              </Button>
              <Button
                disabled={!isValid || isSubmitting || tokenRequestMutation.isLoading}
                type="submit"
                data-testid="create-token-submit"
              >
                {isSubmitting ? "Creating..." : tokenRequestMutation.error ? "Try again" : "Create"}
              </Button>
            </ModalFooter>
          </ModalFlowForm>
        );
      }}
    </Formik>
  );
}

export default CreateServiceTokenForm;
