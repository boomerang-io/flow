import React, { useState } from "react";
//@ts-ignore
import { Button, InlineLoading, OverflowMenu, OverflowMenuItem } from "@carbon/react";
import { Run, Bee, CircleFill, InformationFilled, Template, Add } from "@carbon/react/icons";
import { ComposedModal, ToastNotification, notify } from "@boomerang-io/carbon-addons-boomerang-react";
import workflowIcons from "Assets/workflowIcons";
import { useMutation, useQueryClient } from "react-query";
import { Link, useHistory } from "react-router-dom";
import { appLink, FeatureFlag } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { FlowTeam, ModalTriggerProps, WorkflowTemplate } from "Types";
import CreateWorkflowContent from "./CreateWorkflowContent";
import styles from "./workflowTemplateHomeCard.module.scss";

interface WorkflowTemplateCardProps {
  template: WorkflowTemplate;
  teams: Array<FlowTeam>;
}

const WorkflowTemplateCard: React.FC<WorkflowTemplateCardProps> = ({ template, teams }) => {
  const history = useHistory();

  const {
    mutateAsync: createTemplateWorkflowMutator,
    isLoading: createTemplateWorkflowIsLoading,
    error: createTemplateWorkflowError,
  } = useMutation(resolver.postCreateWorkflow);

  const handleCreateWorkflow = async (
    team: string,
    requestBody: { name: string; description: string; icon: string },
  ) => {
    try {
      const data = { ...template, ...requestBody };
      const { data: workflow } = await createTemplateWorkflowMutator({
        team: team,
        body: data,
      });
      history.push(appLink.editorCanvas({ team: team, workflow: workflow.name }));
      notify(
        <ToastNotification
          kind="success"
          title="Create Workflow"
          subtitle="Successfully created workflow from template"
        />,
      );
      return;
    } catch (e) {
      console.log(e);
      return;
      //no-op
    }
  };
  const isLoading = createTemplateWorkflowIsLoading;
  const { name, Icon = Bee } = workflowIcons.find((icon) => icon.name === template.icon) ?? {};

  let loadingText = "";

  return (
    <div className={styles.container}>
      <section className={styles.details}>
        <div className={styles.iconContainer}>
          <Icon className={styles.icon} alt={`${name}`} />
        </div>
        <div className={styles.descriptionContainer}>
          <h1 title={template.name} className={styles.name} data-testid="workflow-card-title">
            {template.name}
          </h1>
          <p title={template.description} className={styles.description}>
            {template.description}
          </p>
        </div>
      </section>
      <section className={styles.launch}>
        <ComposedModal
          modalHeaderProps={{
            title: "Create Workflow from Template",
            subtitle: "Get started by leveraging this template",
          }}
          modalTrigger={({ openModal }: ModalTriggerProps) => (
            <Button iconDescription={`Create from Template`} renderIcon={Template} size="md" onClick={openModal}>
              Create from template
            </Button>
          )}
        >
          {({ closeModal }) => (
            <CreateWorkflowContent
              template={template}
              createWorkflow={handleCreateWorkflow}
              createError={createTemplateWorkflowError}
              isLoading={isLoading}
              teams={teams}
            />
          )}
        </ComposedModal>
      </section>
      {isLoading ? (
        <InlineLoading
          description={loadingText}
          style={{ position: "absolute", left: "0.5rem", top: "0", width: "fit-content" }}
        />
      ) : null}
    </div>
  );
};

export default WorkflowTemplateCard;
