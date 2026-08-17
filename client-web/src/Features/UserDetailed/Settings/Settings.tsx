import React from "react";
import { Helmet } from "react-helmet";
import { Edit } from "@carbon/react/icons";
import { Tag, Button } from "@carbon/react";
import { FlowUser } from "Types";
import styles from "./Settings.module.scss";

interface UserSettingsProps {
  user: FlowUser;
  userManagementEnabled: any;
}

export default function Settings({ user, userManagementEnabled }: UserSettingsProps) {
  const canEdit = userManagementEnabled;

  return (
    <section aria-label="User Settings" className={styles.settingsContainer}>
      <Helmet>
        <title>{`Settings - ${user?.name}`}</title>
      </Helmet>
      {!canEdit ? (
        <section className={styles.readOnly}>
          <Tag className={styles.readOnlyTag}>Read-only</Tag>
          <p className={styles.readOnlyText}>
            You don’t have permission to change any of these settings, but you can still see what’s going on behind the
            scenes.
          </p>
        </section>
      ) : (
        <p className={styles.settingsDescription}>
          These are configurable settings for this User – only Admins have the ability to make changes here
        </p>
      )}
      <SettingSection title="Basic details">
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
          </div>
          {/* <dt className={styles.detailedListTitle}>Team Description</dt>
          {team.description ? (
            <dd className={styles.detailedListDescription}>{team.description}</dd>
          ) : (
            <dd className={styles.detailedListDescriptionEmpty}>
              No description. Add a brief description about this Team, which will appear in the header of this team
              page.
            </dd>
          )}
          <dt className={styles.detailedListTitle}>Logo</dt>
          {team.logo?.id && showLogo ? (
            <>
              <dd className={styles.detailedListDescription} style={{ marginBottom: "1rem" }}>
                {team.logo?.name ?? "---"}
              </dd>
              {teamLogo}
            </>
          ) : (
            <dd className={styles.detailedListDescriptionEmpty}>
              No logo. Upload a small image that will appear in the header of this team page. Useful if this team is
              specific to a client or sector, to help team members recognize the Team alignment at a glance.
            </dd>
          )} */}
        </dl>
      </SettingSection>
    </section>
  );
}

// function EditButton({ openModal }: any) {
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

function SettingSection({ children, description, editModal, title }: any) {
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
