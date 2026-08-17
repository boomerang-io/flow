import React, { useState } from "react";
import { Helmet } from "react-helmet";
import { useMutation, useQueryClient } from "react-query";
import { useHistory } from "react-router-dom";
import { InlineNotification, Button } from "@carbon/react";
import {
  ConfirmModal,
  ComposedModal,
  notify,
  ToastNotification,
  TooltipHover,
} from "@boomerang-io/carbon-addons-boomerang-react";
import UpdateTeamName from "./UpdateTeamName";
import {
  StructuredListWrapper,
  StructuredListHead,
  StructuredListRow,
  StructuredListCell,
  StructuredListBody,
} from "@carbon/react";
import { Edit, Close, TrashCan, Add, Copy } from "@carbon/react/icons";
import CopyToClipboard from "react-copy-to-clipboard";
import sortBy from "lodash/sortBy";
import { FlowTeam } from "Types";
import LabelModal from "Components/LabelModal";
import { appLink } from "Config/appConfig";
import styles from "./Settings.module.scss";
import { resolver, serviceUrl } from "Config/servicesConfig";

interface Label {
  key: string;
  value: string;
}

export default function Settings({ team, canEdit }: { team: FlowTeam; canEdit: boolean }) {
  const [copyTokenText, setCopyTokenText] = useState("Copy");
  const queryClient = useQueryClient();
  const history = useHistory();

  const patchTeamMutator = useMutation(resolver.patchUpdateTeam); 
  const deleteTeamMutator = useMutation(resolver.deleteTeam); 

  const handleDeleteTeam = async () => {
    try {
      await deleteTeamMutator.mutateAsync({ team: team.name });
      queryClient.invalidateQueries(serviceUrl.getUserProfile());
      history.push(appLink.home());
      notify(
        <ToastNotification
          title="Delete Team"
          subtitle={`Request to delete '${team.displayName}' was successful`}
          kind="success"
        />,
      );
    } catch (error) {
      // noop
    }
  };

  const handleAddLabel = async (value: Label) => {
    const newLabels = [...teamLabels, value];
    const newLabelsRecord = newLabels.reduce(
      (acc, label) => {
        acc[label.key] = label.value;
        return acc;
      },
      {} as Record<string, string>,
    );

    try {
      await patchTeamMutator.mutateAsync({ team: team.name, body: { labels: newLabelsRecord } });
      queryClient.invalidateQueries(serviceUrl.resourceTeam({ team: team.name }));
      notify(
        <ToastNotification
          title="Add Label"
          subtitle={`Added label to ${team.displayName} successfully`}
          kind="success"
        />,
      );
    } catch (error) {
      // noop
    }
  };

  const handleRemoveLabel = async (value: Label) => {
    const newLabels = teamLabels.filter((label) => label.key !== value.key);
    const newLabelsRecord = newLabels.reduce(
      (acc, label) => {
        acc[label.key] = label.value;
        return acc;
      },
      {} as Record<string, string>,
    );

    try {
      await patchTeamMutator.mutateAsync({ team: team.name, body: { labels: newLabelsRecord } });
      queryClient.invalidateQueries(serviceUrl.resourceTeam({ team: team.name }));
      notify(
        <ToastNotification
          title="Remove Team"
          subtitle={`Request to close ${team.displayName} successful`}
          kind="success"
        />,
      );
    } catch (error) {
      // noop
    }
  };

  // Convert Record/Map of Labels to Array of Label Object
  const teamLabels = team.labels ? Object.entries(team.labels).map(([key, value]) => ({ key, value })) : [];
  const labelsKeys = team.labels ? Object.keys(team.labels) : [];

  return (
    <section aria-label="Team Settings" className={styles.settingsContainer}>
      <Helmet>
        <title>{`Settings - ${team.displayName}`}</title>
      </Helmet>
      {!canEdit ? (
        <section className={styles.notificationsContainer}>
          <InlineNotification
            lowContrast
            hideCloseButton={true}
            kind="info"
            title="Read-only"
            subtitle="The team may be inactive or you don’t have the necessary permissions. You can still see what’s going on behind the
            scenes."
          />
        </section>
      ) : null}
      <p className={styles.settingsDescription}>
        Configurable settings for this Team.
      </p>
      <SettingSection
        title="Basic details"
        editModal={
          <ComposedModal
            composedModalProps={{
              containerClassName: styles.teamNameModalContainer,
            }}
            modalHeaderProps={{
              title: "Change team name",
              //   subtitle:
              //     "Try to keep it concise to avoid truncation in the sidebar. You must make sure the name is valid before it can be updated.",
            }}
            modalTrigger={({ openModal }) => (
              <button
                disabled={!canEdit}
                className={styles.teamEditIcon}
                onClick={openModal}
                data-testid="open-change-name-modal"
              >
                <Edit />
              </button>
            )}
          >
            {({ closeModal }) => <UpdateTeamName closeModal={closeModal} team={team} />}
          </ComposedModal>
        }
      >
        <dl className={styles.detailedListContainer}>
          <div className={styles.detailedListGrid}>
            <div className={styles.detailedListGridItem}>
              <dt className={styles.detailedListTitle}>Display Name</dt>
              <dd className={styles.detailedListDescription}>{team.displayName}</dd>
            </div>
            <div className={styles.detailedListGridItem}>
              <dt className={styles.detailedListTitle}>Unique Identifier Name</dt>
              <dd className={styles.detailedListDescription}>
                {team.name}
                <TooltipHover direction="top" content={copyTokenText} hideOnClick={false}>
                  <button
                    className={styles.copyButton}
                    onClick={() => setCopyTokenText("Copied")}
                    onMouseLeave={() => setCopyTokenText("Copy")}
                    type="button"
                  >
                    <CopyToClipboard text={team.name}>
                      <Copy fill={"#0072C3"} className={styles.actionIcon} alt="Copy" />
                    </CopyToClipboard>
                  </button>
                </TooltipHover>
              </dd>
            </div>
          </div>
        </dl>
      </SettingSection>
      <SettingSection title="Labels">
        <dl className={styles.detailedListContainer}>
          <p className={styles.detailedListParagraph}>Create custom labels can be useful when querying the API.</p>
          <StructuredListWrapper
            className={styles.structuredListWrapper}
            ariaLabel="Structured list"
            isCondensed={true}
          >
            <StructuredListHead>
              <StructuredListRow head>
                <StructuredListCell head>Key</StructuredListCell>
                <StructuredListCell head>Value</StructuredListCell>
                <StructuredListCell head />
              </StructuredListRow>
            </StructuredListHead>
            <StructuredListBody>
              {sortBy(teamLabels, "key").map((label: Label) => {
                //const labelIndex = teamLabels.findIndex((labelFromList) => labelFromList.key === label.key);
                return (
                  <StructuredListRow key={label.key}>
                    <StructuredListCell className={styles.labelKeyCell}>{label.key}</StructuredListCell>
                    <StructuredListCell>{label.value}</StructuredListCell>
                    {canEdit && (
                      <>
                        <StructuredListCell>
                          <LabelModal
                            action={handleAddLabel}
                            isEdit
                            labelsKeys={labelsKeys.filter((labelKey) => labelKey !== label.key)}
                            selectedLabel={label}
                            modalTrigger={({ openModal }: { openModal: Function }) => (
                              <Button
                                kind="ghost"
                                iconDescription="edit label"
                                renderIcon={Edit}
                                size="sm"
                                onClick={openModal}
                              >
                                Edit
                              </Button>
                            )}
                          />
                          <ConfirmModal
                            modalTrigger={({ openModal }) => (
                              <Button
                                kind="danger--ghost"
                                iconDescription="delete label"
                                renderIcon={TrashCan}
                                size="sm"
                                onClick={openModal}
                                data-testid={`delete-token-button-${label}`}
                              />
                            )}
                            affirmativeAction={() => handleRemoveLabel(label)}
                            affirmativeButtonProps={{ kind: "danger" }}
                            affirmativeText="Yes"
                            negativeText="No"
                            title={`Are you sure?`}
                          >
                            Delete
                          </ConfirmModal>
                        </StructuredListCell>
                      </>
                    )}
                  </StructuredListRow>
                );
              })}
              <LabelModal
                action={handleAddLabel}
                labelsKeys={labelsKeys}
                modalTrigger={({ openModal }: { openModal: Function }) => (
                  <Button kind="ghost" iconDescription="add a new label" renderIcon={Add} size="md" onClick={openModal} disabled={!canEdit}>
                    Add a new label
                  </Button>
                )}
              />
            </StructuredListBody>
          </StructuredListWrapper>
        </dl>
        {/* <CreateToken getTokensUrl={getTokensUrl} principal={user.id} type="user" /> */}
      </SettingSection>
      <SettingSection title="Delete Team">
        <div className={styles.buttonWithMessageContainer}>
          <p className={styles.buttonHelperText}>
            Done with your work here? Deleting this team will permanently remove the team, including its Workflows, Task Templates, Runs, and Tokens. Its members will no longer be able to access this team. This action is irreversible - continue with caution.
          </p>
          <ConfirmModal
            affirmativeAction={() => handleDeleteTeam()}
            affirmativeButtonProps={{ kind: "danger", "data-testid": "confirm-close-team" }}
            title={`Delete ${team.displayName}?`}
            negativeText="Cancel"
            affirmativeText="Delete Team"
            modalTrigger={({ openModal }) => (
              <Button
                disabled={!canEdit}
                iconDescription="Close"
                kind="danger--ghost"
                onClick={openModal}
                renderIcon={Close}
                size="md"
                data-testid="close-team"
              >
                Delete Team
              </Button>
            )}
          >
            This team will be permanently deleted, along with all of its Workflows, Task Templates, Runs, and Tokens. This action is irreversible - are you sure you want to do this?
          </ConfirmModal>
        </div>
      </SettingSection>
    </section>
  );
}

function SettingSection({ children, description, editModal, title }) {
  return (
    <section className={styles.sectionContainer}>
      <div className={styles.sectionHeader}>
        <h1 className={styles.sectionTitle}>{title}</h1>
        {editModal}
      </div>
      {description ? <p className={styles.sectionDescription}>{description}</p> : null}
      {children}
    </section>
  );
}
