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
import { useFetcher, useLoaderData, useNavigate, Link } from "react-router-dom";
import { useWorkspaceContext } from "Hooks";
import { appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { DataDrivenInput } from "Types";
import { actionError, isActionError, type ActionError } from "Utils/actionResult";
import ParametersTable from "../ParametersTable";

// Route module for app/routes/workspaceParameters.tsx, following
// Features/Parameters/GlobalParameters/GlobalParameters.tsx. The route is workspace-scoped
// (`/:workspace/parameters`), so both the loader and the action read the `:workspace` route param
// directly rather than the (client-only) workspace context - see
// Features/TaskManager/WorkspaceTasks/WorkspaceTasks.tsx for the same pattern.
//
// The loader owns the read that this page's table renders. It used to come from
// useWorkspaceContext().workspace.parameters - the deleted WorkspaceContainer's react-query
// cache - while the writes had already moved onto this route's action. Settling a
// fetcher revalidates loaders, not react-query, so with no loader here a create/edit/delete raised
// its success toast and left the table exactly as it was until the user navigated away and back
// (the old `queryClient.invalidateQueries` was dropped in the conversion, and
// `refetchOnWindowFocus: false` in app/root.tsx removed the last accidental refresh).
//
// It reads the workspace record - the same GET app/routes/workspaceLayout.tsx's loader makes -
// rather than
// `serviceUrl.workspace.resourceWorkspaceParameters`, because there is no dedicated
// parameter list/create route on the API (see that builder's TODO in Config/servicesConfig.ts);
// parameters are carried on the workspace and merged in through patchWorkspace.
type LoaderData = {
  parameters: DataDrivenInput[];
  errorLoading: boolean;
};

// A failed fetch resolves with an error flag rather than throwing, so the page chrome still
// renders and only the table area shows the error - same contract as GlobalParameters.tsx.
export async function loader({
  params,
  request,
}: {
  params: { workspace?: string };
  request: Request;
}): Promise<LoaderData> {
  try {
    const response = await serverFetch(request).get(
      serviceUrl.resourceWorkspace({ workspace: String(params.workspace) }),
    );
    return { parameters: response.data?.parameters ?? [], errorLoading: false };
  } catch (error) {
    return { parameters: [], errorLoading: true };
  }
}

type ActionResult =
  | { intent: "create" | "update" | "delete"; label: string }
  | ({ intent: "create" | "update" | "delete"; label: string } & ActionError);

export async function action({ params, request }: { params: { workspace?: string }; request: Request }) {
  const workspace = String(params.workspace);
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "delete") {
    const name = String(formData.get("name"));
    const label = String(formData.get("label"));
    try {
      await serverFetch(request).delete(serviceUrl.workspace.deleteWorkspaceParameter({ workspace, name }));
      return { intent: "delete" as const, label };
    } catch (error) {
      return actionError({
        intent: "delete" as const,
        label,
        error: formatErrorMessage({ error, defaultMessage: "Delete Configuration Failed" }),
      });
    }
  }

  const isEdit = intent === "update";
  const editIntent: "update" | "create" = isEdit ? "update" : "create";
  const parameter = JSON.parse(String(formData.get("parameter")));
  try {
    await serverFetch(request).patch(serviceUrl.resourceWorkspace({ workspace }), { parameters: [parameter] });
    return { intent: editIntent, label: parameter.label };
  } catch (error) {
    return actionError({
      intent: editIntent,
      label: parameter.label,
      // Matches the previous handleSubmit catch's (pre-existing, unchanged) default message -
      // written for the delete path and never updated when create/update was added.
      error: formatErrorMessage({ error, defaultMessage: "Delete Configuration Failed" }),
    });
  }
}

function WorkspaceParameters() {
  const navigate = useNavigate();
  const fetcher = useFetcher<ActionResult>();
  // The workspace object stays a client-side concern (header breadcrumb only); the parameters the
  // table renders come from this route's loader, which is what a fetcher settle revalidates.
  const { workspace } = useWorkspaceContext();
  const { parameters, errorLoading } = useLoaderData() as LoaderData;
  // handleSubmit hands this component a `closeModal` at submit time; the fetcher settles
  // asynchronously (fetcher.state -> "idle"), so the callback is stashed here and invoked once the
  // create/update settles (success or failure both closed the modal before, see the effect below) -
  // matching the previous mutateAsync/then-catch behaviour.
  const closeModalRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    const data = fetcher.data;
    const { intent, label } = data;
    const isError = isActionError(data);

    if (intent === "delete") {
      if (!isError) {
        notify(
          <ToastNotification
            kind="success"
            title={"Parameter Deleted"}
            subtitle={`Request to delete ${label} succeeded`}
            data-testid="delete-workspace-param-notification"
          />,
        );
      } else {
        notify(
          <ToastNotification
            kind="error"
            title={data.error.title ?? "Something's Wrong"}
            subtitle={data.error.message}
            data-testid="delete-workspace-param-notification"
          />,
        );
      }
      return;
    }

    if (!isError) {
      notify(
        <ToastNotification
          kind="success"
          title={intent === "update" ? "Parameter Updated" : "Parameter Created"}
          subtitle={`Request to ${intent} ${label} succeeded`}
          data-testid="create-update-workspace-prop-notification"
        />,
      );
    } else {
      notify(
        <ToastNotification
          kind="error"
          title={data.error.title ?? "Something's Wrong"}
          subtitle={data.error.message}
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
  const errorSubmitting = Boolean(fetcher.data && isActionError(fetcher.data) && fetcher.data.intent !== "delete");

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
        parameters={parameters}
        isLoading={false}
        isSubmitting={isSubmitting}
        errorSubmitting={errorSubmitting}
        errorLoading={errorLoading}
        handleDelete={handleDelete}
        handleSubmit={handleSubmit}
      />
    </>
  );
}

export default WorkspaceParameters;
