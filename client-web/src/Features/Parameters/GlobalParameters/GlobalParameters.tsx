import React, { useEffect, useRef } from "react";
import {
  notify,
  ToastNotification,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { Helmet } from "react-helmet";
import { useFetcher, useLoaderData } from "react-router-dom";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { HttpMethod } from "Constants";
import { DataDrivenInput } from "Types";
import ParametersTable from "../ParametersTable";
import styles from "./globalParameters.module.scss";

// Route module: this file's `loader`/`action` are attached to the route in AppRoutes.tsx
// (path={AppPath.Properties}) rather than being defined inline there, so the data-fetching
// code stays next to the component that consumes it - the same place useQuery/useMutation
// calls used to live before this route moved off react-query.

type LoaderData = {
  parameters: DataDrivenInput[];
  errorLoading: boolean;
};

// Mirrors the previous `parametersQuery.isError` behaviour: a failed fetch doesn't throw (which
// would replace this whole route with the router's errorElement, losing the header/layout) - it
// resolves with an error flag so the page chrome still renders and only the table area shows the
// error state, exactly as it did under react-query.
//
// Server loader (see CLAUDE.md client-web SSR direction: server loaders are the default now
// that ssr:true is on) - runs in Node, so it uses serverFetch(request) rather than the browser
// `resolver`/`axios` instance in Config/servicesConfig.ts (no browser cookie jar server-side;
// see Config/serverFetch.ts for the session-cookie-forwarding contract, which is unverified
// end-to-end until the auth exchange endpoint in specifications/authentication.md lands).
export async function loader({ request }: { request: Request }): Promise<LoaderData> {
  try {
    const response = await serverFetch(request).get(serviceUrl.getGlobalParameters());
    return { parameters: response.data, errorLoading: false };
  } catch (error) {
    return { parameters: [], errorLoading: true };
  }
}

type ActionResult = {
  ok: boolean;
  intent: "create" | "update" | "delete";
  label: string;
  errorMessage?: { title: string; message: string };
};

// Typed by the one field this action reads rather than the router's full ActionFunctionArgs -
// that's also what keeps it easy to call directly (see GlobalParameters.spec.tsx) without having
// to fabricate the params/context/pattern fields a real navigation would supply.
export async function action({ request }: { request: Request }): Promise<ActionResult> {
  // Plain form-encoded submission (the fetcher.submit default) rather than encType:"application/json" -
  // DataDrivenInput carries UI-only fields (onChange/onBlur handlers) that aren't valid JSON, so the
  // payload the component builds is serialized into a couple of string fields instead.
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "delete") {
    const name = String(formData.get("name"));
    const label = String(formData.get("label"));
    try {
      await serverFetch(request).delete(serviceUrl.getGlobalParameter({ name }));
      return { ok: true, intent: "delete", label };
    } catch (error) {
      return {
        ok: false,
        intent: "delete",
        label,
        errorMessage: formatErrorMessage({ error, defaultMessage: "Delete Parameter Failed" }),
      };
    }
  }

  const isEdit = intent === "update";
  const parameter = JSON.parse(String(formData.get("parameter")));
  try {
    const response = isEdit
      ? await serverFetch(request)({
          url: serviceUrl.getGlobalParameters(),
          data: parameter,
          method: HttpMethod.Put,
        })
      : await serverFetch(request)({
          url: serviceUrl.getGlobalParameters(),
          data: parameter,
          method: HttpMethod.Post,
        });
    return { ok: true, intent: isEdit ? "update" : "create", label: response.data.label };
  } catch (error) {
    return { ok: false, intent: isEdit ? "update" : "create", label: parameter.label };
  }
}

function GlobalParameters() {
  const { parameters, errorLoading } = useLoaderData() as LoaderData;
  const fetcher = useFetcher<ActionResult>();
  // handleSubmit hands this component a `closeModal` at submit time (see CreateEditParametersModal);
  // the fetcher settles asynchronously (fetcher.state -> "idle"), so the callback is stashed here
  // and invoked from the effect below once the create/update actually succeeds - the same "stay
  // open with a spinner, close only on success" behaviour the old mutateAsync/then chain had.
  const closeModalRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    const { ok, intent, label, errorMessage } = fetcher.data;

    if (intent === "delete") {
      notify(
        ok ? (
          <ToastNotification
            kind="success"
            title="Parameter Deleted"
            subtitle={`Successfully deleted ${label}`}
            data-testid="delete-parameter-notification"
          />
        ) : (
          <ToastNotification
            kind="error"
            title={errorMessage?.title ?? "Something's Wrong"}
            subtitle={errorMessage?.message}
            data-testid="delete-parameter-notification"
          />
        ),
      );
      return;
    }

    if (ok) {
      closeModalRef.current?.();
      closeModalRef.current = null;
      notify(
        <ToastNotification
          kind="success"
          title={intent === "update" ? "Parameter Updated" : "Parameter Created"}
          subtitle={`Request to ${intent} ${label} succeeded`}
          data-testid="create-update-parameter-notification"
        />,
      );
    }
    // create/update failures leave the modal open - ParametersTable surfaces them inline via
    // `errorSubmitting`, matching the previous mutation.isError-driven behaviour.
  }, [fetcher.state, fetcher.data]);

  const handleSubmit = async (isEdit: boolean, parameter: DataDrivenInput, closeModal: () => void) => {
    closeModalRef.current = closeModal;
    fetcher.submit({ intent: isEdit ? "update" : "create", parameter: JSON.stringify(parameter) }, { method: "post" });
  };

  const handleDelete = async (parameter: DataDrivenInput) => {
    fetcher.submit({ intent: "delete", name: parameter.name, label: parameter.label ?? "" }, { method: "post" });
  };

  const isSubmitting = fetcher.state !== "idle";
  const errorSubmitting = Boolean(fetcher.data && !fetcher.data.ok && fetcher.data.intent !== "delete");

  return (
    <div className={styles.container}>
      <Helmet>
        <title>Parameters</title>
      </Helmet>
      <Header
        className={styles.header}
        includeBorder={false}
        header={
          <>
            <HeaderTitle className={styles.headerTitle}>Parameters</HeaderTitle>
            <HeaderSubtitle>Set global parameters that are accessible to all workflows.</HeaderSubtitle>
          </>
        }
      />
      <ParametersTable
        parameters={parameters}
        isLoading={false}
        isSubmitting={isSubmitting}
        errorLoading={errorLoading}
        errorSubmitting={errorSubmitting}
        handleDelete={handleDelete}
        handleSubmit={handleSubmit}
      />
    </div>
  );
}

export default GlobalParameters;
