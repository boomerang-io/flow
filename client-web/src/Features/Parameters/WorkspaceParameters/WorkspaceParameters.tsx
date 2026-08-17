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
import { useTeamContext } from "Hooks";
import { appLink } from "Config/appConfig";
import { resolver, serviceUrl } from "Config/servicesConfig";
import { DataDrivenInput } from "Types";
import ParametersTable from "../ParametersTable";

function TeamParameters() {
  const history = useHistory();
  const queryClient = useQueryClient();
  const { team } = useTeamContext();

  /** Add / Update / Delete Team parameter */
  const parameterMutation = useMutation(resolver.patchTeam);
  const deleteParameterMutation = useMutation(resolver.deleteTeamParameter);

  const handleSubmit = async (isEdit: boolean, parameter: DataDrivenInput, closeModal: () => void) => {
    try {
      await parameterMutation.mutateAsync({
        team: team?.name,
        body: { parameters: [parameter] },
      });
      if (isEdit) {
        notify(
          <ToastNotification
            kind="success"
            title={"Parameter Updated"}
            subtitle={`Request to update ${parameter.label} succeeded`}
            data-testid="create-update-team-prop-notification"
          />,
        );
      } else {
        notify(
          <ToastNotification
            kind="success"
            title={"Parameter Created"}
            subtitle={`Request to create ${parameter.label} succeeded`}
            data-testid="create-update-team-prop-notification"
          />,
        );
      }
      queryClient.invalidateQueries([serviceUrl.resourceTeam({ team: team?.name })]);
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
      await deleteParameterMutation.mutateAsync({ team: team?.name, name: parameter.name });
      notify(
        <ToastNotification
          kind="success"
          title={"Parameter Deleted"}
          subtitle={`Request to delete ${parameter.label} succeeded`}
          data-testid="delete-team-param-notification"
        />,
      );
      queryClient.invalidateQueries([serviceUrl.resourceTeam({ team: team?.name })]);
    } catch (err) {
      const errorMessages = formatErrorMessage({ error: err, defaultMessage: "Delete Configuration Failed" });
      notify(
        <ToastNotification
          kind="error"
          title={errorMessages.title}
          subtitle={errorMessages.message}
          data-testid="delete-team-param-notification"
        />,
      );
    }
  };

  /** Check if there is an active team or redirect to home */
  if (!team) {
    return history.push(appLink.home());
  }

  const NavigationComponent = () => {
    return (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.home()}>Home</Link>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <p>{team?.name}</p>
        </BreadcrumbItem>
      </Breadcrumb>
    );
  };

  return (
    <>
      <Helmet>
        <title>Team Parameters</title>
      </Helmet>
      <Header
        includeBorder={false}
        nav={<NavigationComponent />}
        header={
          <>
            <HeaderTitle>Team Parameters</HeaderTitle>
            <HeaderSubtitle>
              Set team-level parameters that are accessible to all workflows owned by the team.
            </HeaderSubtitle>
          </>
        }
      />
      <ParametersTable
        parameters={team.parameters ?? []}
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

export default TeamParameters;
