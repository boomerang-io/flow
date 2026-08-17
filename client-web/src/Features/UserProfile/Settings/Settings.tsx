import React, { useState } from "react";
import { Tag, Button, InlineNotification } from "@carbon/react";
import { Edit, CopyFile, Close } from "@carbon/react/icons";
import {
  ConfirmModal,
  ComposedModal,
  notify,
  ToastNotification,
  TooltipHover,
} from "@boomerang-io/carbon-addons-boomerang-react";
import CopyToClipboard from "react-copy-to-clipboard";
import { Helmet } from "react-helmet";
import { useMutation } from "react-query";
import TokenSection from "Components/TokenSection";
import { TokenType } from "Constants";
import { resolver } from "Config/servicesConfig";
import type { FlowUser } from "Types";
import styles from "./Settings.module.scss";
import UpdateBasicDetails from "./UpdateBasicDetails";

interface UserSettingsProps {
  user: FlowUser;
  userManagementEnabled: any;
}

export default function Settings({ user, userManagementEnabled }: UserSettingsProps) {
  const [copyTokenText, setCopyTokenText] = useState("Copy ID");
  const canEdit = userManagementEnabled;

  const removeUserMutator = useMutation(resolver.deleteUser);

  const teamCount = user.teams.length;

  const removeTeam = async () => {
    try {
      await removeUserMutator.mutateAsync({ userId: user.id });
      notify(
        <ToastNotification title="Close Account" subtitle="Request to close your account successful" kind="success" />,
      );
    } catch (error) {
      notify(
        <ToastNotification
          title="Close Account"
          subtitle={`Unable to close the account. ${error.message}. Please contact support.`}
          kind="error"
        />,
      );
    }
  };

  return (
    <section aria-label="User Settings" className={styles.settingsContainer}>
      <Helmet>
        <title>{`Settings - ${user?.name}`}</title>
      </Helmet>
      {!canEdit ? (
        <section className={styles.readOnly}>
          <Tag className={styles.readOnlyTag}>Read-only</Tag>
          <p className={styles.readOnlyText}>
            Manage your profile, tokens, special features, or close your account. - You don’t have permission to change
            any of these settings, but you can still see what’s going on behind the scenes.
          </p>
        </section>
      ) : (
        <p className={styles.settingsDescription}>
          Manage your profile, tokens, special features, or close your account.
        </p>
      )}
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
              <button className={styles.teamEditIcon} onClick={openModal} data-testid="open-change-name-modal">
                <Edit />
              </button>
            )}
          >
            {({ closeModal }) => <UpdateBasicDetails closeModal={closeModal} user={user} />}
          </ComposedModal>
        }
      >
        <dl className={styles.detailedListContainer}>
          <div className={styles.detailedListGrid}>
            <div className={styles.detailedListGridItem}>
              <dt className={styles.detailedListTitle}>Name</dt>
              <dd className={styles.detailedListDescription}>{user.name}</dd>
            </div>
            <div className={styles.detailedListGridItem}>
              <dt className={styles.detailedListTitle}>Email</dt>
              <dd className={styles.detailedListDescription}>{user.email}</dd>
            </div>
            <div className={styles.detailedListGridItem}>
              <dt className={styles.detailedListTitle}>Preferred Display Name</dt>
              <dd className={styles.detailedListDescription}>{user.displayName ? user.displayName : "---"}</dd>
            </div>
          </div>
        </dl>
      </SettingSection>
      <SettingSection title="Tokens">
        <dl className={styles.detailedListContainer}>
          <p className={styles.detailedListParagraph}>
            Personal access tokens allow other apps to access the APIs as if they were you. All of your access will be
            shared. Be careful how you distribute these tokens!
          </p>
          <p className={styles.detailedListParagraph}>Expand the token row to view the assigned permissions.</p>
          <TokenSection type={TokenType.User} principal={user.id} />
        </dl>
      </SettingSection>
      <SettingSection title="Your ID">
        <dl className={styles.detailedListContainer}>
          <p className={styles.detailedListParagraph}>
            This is your user ID and can be used when interacting with the API.
          </p>
          <div className={styles.detailedListGrid}>
            <div className={styles.detailedListGridItem}>
              <dd className={styles.detailedListDescription}>{user.id}</dd>
            </div>
            <div className={styles.detailedListGridItem}>
              <dd className={styles.detailedListDescription}>
                <TooltipHover direction="top" content={copyTokenText} hideOnClick={false}>
                  <div>
                    <CopyToClipboard text={user.id}>
                      <button
                        className={styles.actionButton}
                        onClick={() => setCopyTokenText("Copied ID")}
                        onMouseLeave={() => setCopyTokenText("Copy ID")}
                        type="button"
                      >
                        <CopyFile fill={"#0072C3"} className={styles.actionIcon} alt="Copy ID" />
                      </button>
                    </CopyToClipboard>
                  </div>
                </TooltipHover>
              </dd>
            </div>
          </div>
        </dl>
      </SettingSection>
      <SettingSection title="Features">
        <div className={styles.detailedListContainer}>
          <p className={styles.detailedListParagraph}>There are no special features to be enabled at this time.</p>
        </div>
      </SettingSection>
      <SettingSection title="Delete Account">
        <div className={styles.detailedListContainer}>
          <p className={styles.detailedListParagraph}>
            Done with your work here? Deleting your account means you will no longer be able to access any items you
            have created. You will also no longer receive any notifications from the platform.
          </p>
          <p className={styles.detailedListParagraph}>
            This action cannot be undone. Be sure you want to permanently delete your access.
          </p>
          <ConfirmModal
            affirmativeAction={() => removeTeam()}
            affirmativeButtonProps={{ disabled: teamCount > 0, kind: "danger", "data-testid": "confirm-close-account" }}
            title="Delete Account?"
            negativeText="Cancel"
            affirmativeText="Delete"
            modalTrigger={({ openModal }) => (
              <Button
                disabled={!canEdit}
                iconDescription="Close"
                kind="danger"
                onClick={openModal}
                renderIcon={Close}
                size="md"
                data-testid="close-team"
              >
                Delete Account
              </Button>
            )}
          >
            Permanently remove your account and all of its contents. Deleting your account cannot be undone. Are you
            sure you want to do this?
            {teamCount > 0 && (
              <InlineNotification
                className={styles.inlineDelete}
                lowContrast
                kind="error"
                subtitle={`You still have access to ${teamCount} teams. You must leave or delete all of your teams before you can delete your account.`}
              />
            )}
          </ConfirmModal>
        </div>
      </SettingSection>
    </section>
  );
}

// function EditButton({ openModal }) {
//   return (
//     <Button
//       className={styles.editButton}
//       data-testid="settings-edit-button"
//       iconDescription="edit"
//       kind="ghost"
//       size="small"
//       renderIcon={Edit}
//       onClick={openModal}
//     />
//   );
// }

interface SettingsSectionProps {
  children: React.ReactNode;
  description?: string;
  editModal?: React.ReactNode;
  title: string;
}

function SettingSection({ children, description, editModal, title }: SettingsSectionProps) {
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
