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
import { useFetcher } from "react-router-dom";
import * as Yup from "yup";
import { TokenType, TokenActorKind } from "Constants";
import { TokenScopeType } from "Types";
import type { TokenActionResult } from "Components/TokenSection/tokenRoute";
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
}

function CreateServiceTokenForm({
  closeModal,
  goToStep,
  saveValues,
  setIsTokenCreated,
  type,
  principal,
  actorKind,
}: CreateServiceTokenFormProps) {
  // Posts to the shared token action on whichever route rendered this modal (see
  // Components/TokenSection/tokenRoute.ts). Replaces the previous useMutation +
  // queryClient.invalidateQueries(getTokensUrl) pair: all three token surfaces are now
  // loader-driven, so there is no query cache to invalidate and the list is refreshed by
  // revalidating the route instead - which is also why the `onSuccess` escape hatch the admin
  // route needed is gone.
  const fetcher = useFetcher<TokenActionResult>();
  const isCreating = fetcher.state !== "idle";
  const createFailed = Boolean(fetcher.data && fetcher.data.intent === "create" && !fetcher.data.ok);

  // The fetcher settles asynchronously, so the "advance the modal to the Result step" work that
  // used to sit after `await mutateAsync` runs here once the action actually succeeds. The token
  // secret is only ever present on this response, so it is handed straight to saveValues.
  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "create" || !fetcher.data.ok) {
      return;
    }
    saveValues?.(fetcher.data.token);
    setIsTokenCreated();
    goToStep?.(1);
    // Only the fetcher settling should drive this; the callback props are fresh identities on
    // every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

  const createToken = (values: any) => {
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

    fetcher.submit({ intent: "create", body: JSON.stringify(request) }, { method: "post" });
  };

  const handleSelectDate = (setFieldValue: any, id: string, value: any) => {
    if (Array.isArray(value) && value[0]) {
      setFieldValue("date", String(moment.utc(value[0]).format("YYYY/MM/DD")));
    } else {
      setFieldValue("date", value?.target?.value);
    }
  };

  // Formik's `isSubmitting` is deliberately not destructured below any more: onSubmit fires the
  // fetcher and returns immediately, so Formik would flip it back to false before the request has
  // settled. fetcher.state (isCreating) is the real in-flight signal.
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
      {({ errors, touched, handleBlur, handleSubmit, setFieldValue, isValid, values }) => {
        const handlePermissionSelectionChange = (selection: PermissionSelection) => {
          setFieldValue("role", selection.role);
          setFieldValue("permissions", selection.permissions);
        };
        return (
          <ModalFlowForm className={styles.container} onSubmit={handleSubmit}>
            <ModalBody>
              {isCreating && <Loading />}
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
                <PermissionSelector onChange={handlePermissionSelectionChange} />
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
              {createFailed ? (
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
                disabled={!isValid || isCreating}
                type="submit"
                data-testid="create-token-submit"
              >
                {isCreating ? "Creating..." : createFailed ? "Try again" : "Create"}
              </Button>
            </ModalFooter>
          </ModalFlowForm>
        );
      }}
    </Formik>
  );
}

export default CreateServiceTokenForm;
