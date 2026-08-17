import React from "react";
import {
  notify,
  ToastNotification,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { Helmet } from "react-helmet";
import { useQuery, useQueryClient, useMutation } from "react-query";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { DataDrivenInput } from "Types";
import ParametersTable from "../ParametersTable";
import styles from "./globalParameters.module.scss";

function GlobalParameters() {
  const queryClient = useQueryClient();

  /** Get Parameters */
  const parametersUrl = serviceUrl.getGlobalParameters();
  const parametersQuery = useQuery(parametersUrl, resolver.query(parametersUrl));

  /** Add / Update / Delete parameter */
  const addParameterMutation = useMutation(resolver.postGlobalParameter);
  const updateParameterMutation = useMutation(resolver.patchGlobalParameter);
  const deleteParameterMutation = useMutation(resolver.deleteGlobalParameter);

  const handleSubmit = async (isEdit: boolean, parameter: DataDrivenInput, closeModal: () => void) => {
    if (isEdit) {
      try {
        const response = await updateParameterMutation.mutateAsync({
          key: parameter.key,
          body: parameter,
        });
        queryClient.invalidateQueries([parametersUrl]);
        notify(
          <ToastNotification
            kind="success"
            title={"Parameter Updated"}
            subtitle={`Request to update ${response.data.label} succeeded`}
            data-testid="create-update-parameter-notification"
          />,
        );
      } catch (err) {}
    } else {
      try {
        const response = await addParameterMutation.mutateAsync({ body: parameter });
        queryClient.invalidateQueries([parametersUrl]);
        notify(
          <ToastNotification
            kind="success"
            title={"Parameter Created"}
            subtitle={`Request to create ${response.data.label} succeeded`}
            data-testid="create-update-parameter-notification"
          />,
        );
      } catch (err) {
        //no-op
      }
      closeModal();
    }
  };

  const handleDelete = async (parameter: DataDrivenInput) => {
    try {
      await deleteParameterMutation.mutateAsync({ name: parameter.name });
      queryClient.invalidateQueries([parametersUrl]);
      notify(
        <ToastNotification
          kind="success"
          title={"Parameter Deleted"}
          subtitle={`Successfully deleted ${parameter.label}`}
          data-testid="delete-parameter-notification"
        />,
      );
    } catch (err) {
      const errorMessages = formatErrorMessage({ error: err, defaultMessage: "Delete Parameter Failed" });
      notify(
        <ToastNotification
          kind="error"
          title={errorMessages.title}
          subtitle={errorMessages.message}
          data-testid="delete-parameter-notification"
        />,
      );
    }
  };

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
        parameters={parametersQuery.data ?? []}
        isLoading={parametersQuery.isLoading}
        isSubmitting={updateParameterMutation.isLoading}
        errorLoading={parametersQuery.isError}
        errorSubmitting={updateParameterMutation.isError}
        handleDelete={handleDelete}
        handleSubmit={handleSubmit}
      />
    </div>
  );
}

export default GlobalParameters;
