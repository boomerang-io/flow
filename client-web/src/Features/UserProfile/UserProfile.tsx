import React from "react";
import { useFeature } from "flagged";
import { useAppContext } from "Hooks";
import Header from "./Header";
import Settings from "./Settings";
import { FeatureFlag } from "Config/appConfig";
import styles from "./UserProfile.module.scss";

function UserProfile() {
  const userManagementEnabled = useFeature(FeatureFlag.UserManagementEnabled);
  const { user } = useAppContext();

  return (
    <div className={styles.container}>
      <Header user={user} userManagementEnabled={userManagementEnabled} />
      <Settings user={user} userManagementEnabled={userManagementEnabled} />
    </div>
  );
}

export default UserProfile;
