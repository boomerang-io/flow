import React from "react";
import { Breadcrumb, BreadcrumbItem } from "@carbon/react";
import {
  notify,
  ToastNotification,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { paramCase } from "change-case";
import { Helmet } from "react-helmet";
import { useMutation, useQueryClient } from "react-query";
import { useHistory, Link } from "react-router-dom";
import { useWorkspaceContext } from "Hooks";
import { appLink } from "Config/appConfig";
import { resolver, serviceUrl } from "Config/servicesConfig";
import { DataDrivenInput } from "Types";
import ParametersTable from "../ParametersTable";

function WorkspaceParameters() {
  const history = useHistory();
  const queryClient = useQueryClient();
  const { workspace } = useWorkspaceContext();

  /** Add / Update / Delete Workspace parameter */
  const parameterMutation = useMutation(resolver.patchWorkspace);
  const deleteParameterMutation = useMutation(resolver.deleteWorkspaceParameter);

  const handleSubmit = async (isEdit: boolean, parameter: DataDrivenInput, closeModal: () => void) => {
    try {
      await parameterMutation.mutateAsync({
        workspace: workspace?.name,
        body: { parameters: [parameter] },
      });
      if (isEdit) {
        notify(
          <ToastNotification
            kind="success"
            title={"Parameter Updated"}
            subtitle={`Request to update ${parameter.label} succeeded`}
            data-testid="create-update-workspace-prop-notification"
          />,
        );
      } else {
        notify(
          <ToastNotification
            kind="success"
            title={"Parameter Created"}
            subtitle={`Request to create ${parameter.label} succeeded`}
            data-testid="create-update-workspace-prop-notification"
          />,
        );
      }
      queryClient.invalidateQueries([serviceUrl.resourceWorkspace({ workspace: workspace?.name })]);
      closeModal();
    } catch (err) {
      //TODO switch this to an inline
      const errorMessages = formatErrorMessage({ error: err, defaultMessage: "Delete Configuration Failed" });
      notify(
        <ToastNotification
          kind="error"
          title={errorMessages.title}
          subtitle={errorMessages.message}
          data-testid="create-param-notification"
        />,
      );
      closeModal();
    }
  };

  const handleDelete = async (parameter: DataDrivenInput) => {
    try {
      await deleteParameterMutation.mutateAsync({ workspace: workspace?.name, name: parameter.name });
      notify(
        <ToastNotification
          kind="success"
          title={"Parameter Deleted"}
          subtitle={`Request to delete ${parameter.label} succeeded`}
          data-testid="delete-workspace-param-notification"
        />,
      );
      queryClient.invalidateQueries([serviceUrl.resourceWorkspace({ workspace: workspace?.name })]);
    } catch (err) {
      const errorMessages = formatErrorMessage({ error: err, defaultMessage: "Delete Configuration Failed" });
      notify(
        <ToastNotification
          kind="error"
          title={errorMessages.title}
          subtitle={errorMessages.message}
          data-testid="delete-workspace-param-notification"
        />,
      );
    }
  };

  /** Check if there is an active workspace or redirect to home */
  if (!workspace) {
    return history.push(appLink.home());
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
        isSubmitting={parameterMutation.isLoading}
        errorSubmitting={parameterMutation.isError}
        errorLoading={false}
        handleDelete={handleDelete}
        handleSubmit={handleSubmit}
      />
    </>
  );
}

export default WorkspaceParameters;
