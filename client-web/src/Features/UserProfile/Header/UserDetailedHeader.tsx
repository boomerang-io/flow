import React from "react";
import moment from "moment";
import {
  Avatar,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { UserRoleCopy } from "Constants";
import { serviceUrl } from "Config/servicesConfig";
import { FlowUser } from "Types";
import { Checkmark, Close } from "@carbon/react/icons";
import styles from "./UserDetailedHeader.module.scss";

interface UserDetailedHeaderProps {
  user: FlowUser;
  userManagementEnabled?: any;
}

function UserDetailedHeader({ user, userManagementEnabled }: UserDetailedHeaderProps) {
  const isActive = user?.status === "active";

  return (
    <Header
      includeBorder
      className={styles.container}
      header={
        <div className={styles.infoContainer}>
          <div className={styles.userContainer}>
            <Avatar
              className={styles.userAvatar}
              src={serviceUrl.getUserProfileImage({ userEmail: user?.email })}
              user={user?.email}
            />
            <HeaderTitle style={{ margin: "0 1rem 0 1rem" }} title={user?.name}>
              {user?.name ?? "---"}
            </HeaderTitle>
          </div>
          {user && (
            <div className={styles.userDetailsContainer}>
              <section className={styles.subHeaderContainer}>
                <dl className={styles.detailedInfoContainer}>
                  <dt className={styles.dataTitle}>Status</dt>
                  <dd className={styles.dataValue}>
                    <div className={styles.status}>
                      {isActive ? <Checkmark style={{ fill: "#009d9a" }} /> : <Close style={{ fill: "#da1e28" }} />}
                      <p className={styles.statusText}>{isActive ? "Active" : "Inactive"}</p>
                    </div>
                  </dd>
                </dl>
                <dl className={styles.detailedInfoContainer}>
                  <dt className={styles.dataTitle}>Role</dt>
                  <dd className={styles.dataValue}>{user?.type ? UserRoleCopy[user.type] : "---"}</dd>
                </dl>
                <dl className={styles.detailedInfoContainer}>
                  <dt className={styles.dataTitle}>Date Joined</dt>
                  <dd className={styles.dataValue}>{moment(user.creationDate).format("YYYY-MM-DD")}</dd>
                </dl>
                <dl className={styles.detailedInfoContainer}>
                  <dt className={styles.dataTitle}>Last Login</dt>
                  <dd className={styles.dataValue}>{moment(user.lastLoginDate).format("YYYY-MM-DD h:mma")}</dd>
                </dl>
              </section>
              <section className={styles.actionButtonsContainer}></section>
            </div>
          )}
        </div>
      }
    ></Header>
  );
}

export default UserDetailedHeader;
