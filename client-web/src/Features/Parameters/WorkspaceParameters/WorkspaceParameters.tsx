import React, { useEffect, useRef } from "react";
import { Breadcrumb, BreadcrumbItem } from "@carbon/react";
import {
  notify,
  ToastNotification,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { Helmet } from "react-helmet";
import { useFetcher, useNavigate, useRevalidator, Link } from "react-router-dom";
import { useWorkspaceContext } from "Hooks";
import { appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { DataDrivenInput } from "Types";
import ParametersTable from "../ParametersTable";

// This route (app/routes/workspaceParameters.tsx) has no loader of its own - the parameters this
// page displays come from useWorkspaceContext().workspace.parameters (WorkspaceContainer's own
// client-side query, see Features/App/App.tsx), so only the writes below move to a route action.
// See Features/Parameters/GlobalParameters/GlobalParameters.tsx for the reference conversion this
// otherwise follows. The route is workspace-scoped (`/:workspace/parameters`), so the action reads
// the `:workspace` route param directly rather than the (client-only) workspace context - see
// Features/TaskManager/WorkspaceTasks/WorkspaceTasks.tsx for the same pattern.
type ActionResult = {
  ok: boolean;
  intent: "create" | "update" | "delete";
  label: string;
  errorMessage?: { title: string; message: string };
};

export async function action({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<ActionResult> {
  const workspace = String(params.workspace);
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "delete") {
    const name = String(formData.get("name"));
    const label = String(formData.get("label"));
    try {
      await serverFetch(request).delete(serviceUrl.workspace.deleteWorkspaceParameter({ workspace, name }));
      return { ok: true, intent: "delete", label };
    } catch (error) {
      return {
        ok: false,
        intent: "delete",
        label,
        errorMessage: formatErrorMessage({ error, defaultMessage: "Delete Configuration Failed" }),
      };
    }
  }

  const isEdit = intent === "update";
  const parameter = JSON.parse(String(formData.get("parameter")));
  try {
    await serverFetch(request).patch(serviceUrl.resourceWorkspace({ workspace }), { parameters: [parameter] });
    return { ok: true, intent: isEdit ? "update" : "create", label: parameter.label };
  } catch (error) {
    return {
      ok: false,
      intent: isEdit ? "update" : "create",
      label: parameter.label,
      // Matches the previous handleSubmit catch's (pre-existing, unchanged) default message -
      // written for the delete path and never updated when create/update was added.
      errorMessage: formatErrorMessage({ error, defaultMessage: "Delete Configuration Failed" }),
    };
  }
}

function WorkspaceParameters() {
  const navigate = useNavigate();
  const revalidator = useRevalidator();
  const fetcher = useFetcher<ActionResult>();
  const { workspace } = useWorkspaceContext();
  // handleSubmit hands this component a `closeModal` at submit time; the fetcher settles
  // asynchronously (fetcher.state -> "idle"), so the callback is stashed here and invoked once the
  // create/update settles (success or failure both closed the modal before, see the effect below) -
  // matching the previous mutateAsync/then-catch behaviour.
  const closeModalRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    const { ok, intent, label, errorMessage } = fetcher.data;

    if (intent === "delete") {
      if (ok) {
        notify(
          <ToastNotification
            kind="success"
            title={"Parameter Deleted"}
            subtitle={`Request to delete ${label} succeeded`}
            data-testid="delete-workspace-param-notification"
          />,
        );
        revalidator.revalidate();
      } else {
        notify(
          <ToastNotification
            kind="error"
            title={errorMessage?.title ?? "Something's Wrong"}
            subtitle={errorMessage?.message}
            data-testid="delete-workspace-param-notification"
          />,
        );
      }
      return;
    }

    if (ok) {
      notify(
        <ToastNotification
          kind="success"
          title={intent === "update" ? "Parameter Updated" : "Parameter Created"}
          subtitle={`Request to ${intent} ${label} succeeded`}
          data-testid="create-update-workspace-prop-notification"
        />,
      );
      revalidator.revalidate();
    } else {
      notify(
        <ToastNotification
          kind="error"
          title={errorMessage?.title ?? "Something's Wrong"}
          subtitle={errorMessage?.message}
          data-testid="create-param-notification"
        />,
      );
    }
    closeModalRef.current?.();
    closeModalRef.current = null;
  }, [fetcher.state, fetcher.data]);

  const handleSubmit = async (isEdit: boolean, parameter: DataDrivenInput, closeModal: () => void) => {
    closeModalRef.current = closeModal;
    fetcher.submit({ intent: isEdit ? "update" : "create", parameter: JSON.stringify(parameter) }, { method: "post" });
  };

  const handleDelete = async (parameter: DataDrivenInput) => {
    fetcher.submit({ intent: "delete", name: parameter.name, label: parameter.label ?? "" }, { method: "post" });
  };

  /** Check if there is an active workspace or redirect to home */
  if (!workspace) {
    navigate(appLink.home());
    return null;
  }

  const NavigationComponent = () => {
    return (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.home()}>Home</Link>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <p>{workspace?.name}</p>
        </BreadcrumbItem>
      </Breadcrumb>
    );
  };

  const isSubmitting = fetcher.state !== "idle";
  const errorSubmitting = Boolean(fetcher.data && !fetcher.data.ok && fetcher.data.intent !== "delete");

  return (
    <>
      <Helmet>
        <title>Workspace Parameters</title>
      </Helmet>
      <Header
        includeBorder={false}
        nav={<NavigationComponent />}
        header={
          <>
            <HeaderTitle>Workspace Parameters</HeaderTitle>
            <HeaderSubtitle>
              Set workspace-level parameters that are accessible to all workflows owned by the workspace.
            </HeaderSubtitle>
          </>
        }
      />
      <ParametersTable
        parameters={workspace.parameters ?? []}
        isLoading={false}
        isSubmitting={isSubmitting}
        errorSubmitting={errorSubmitting}
        errorLoading={false}
        handleDelete={handleDelete}
        handleSubmit={handleSubmit}
      />
    </>
  );
}

export default WorkspaceParameters;
