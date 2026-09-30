import {
  Avatar,
  ComposedModal,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureNavTab as Tab,
  FeatureNavTabs as Tabs,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Breadcrumb, BreadcrumbItem, Button } from "@carbon/react";
import { CheckmarkFilled, Misuse } from "@carbon/react/icons";
import React from "react";
import { Link, useLocation } from "react-router-dom";
import moment from "moment";
import { emailIsValid } from "Utils";
import ChangeRole from "./ChangeRole";
import styles from "./UserDetailedHeader.module.scss";
import { appLink } from "Config/appConfig";
import { UserRole, UserRoleCopy } from "Constants";
import { FlowUser, UserRoleType } from "Types";

interface UserDetailedHeaderProps {
  isError?: boolean;
  isLoading?: boolean;
  user?: FlowUser;
  userManagementEnabled?: any;
  workspaceCount?: number;
}

const manageableUserRoles: string[] = Object.values(UserRole);

/** Not every PlatformRole (e.g. sponsor, partner) has a UserRoleCopy label. */
function hasRoleLabel(role: string): role is UserRoleType {
  return manageableUserRoles.includes(role);
}

function UserDetailedHeader({ isError, isLoading, user, userManagementEnabled, workspaceCount }: UserDetailedHeaderProps) {
  const location: any = useLocation();

  const backToWorkspace = location?.state?.fromWorkspace;

  const isActive = user?.status === "active";

  const NavigationComponent = () => {
    return Boolean(backToWorkspace) ? (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.workspaceList()}>Workspaces</Link>
        </BreadcrumbItem>
        <BreadcrumbItem>
          <Link to={appLink.manageWorkspace({ workspace: backToWorkspace })}>{backToWorkspace}</Link>
        </BreadcrumbItem>
      </Breadcrumb>
    ) : (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.home()}>Home</Link>
        </BreadcrumbItem>
        <BreadcrumbItem>
          <p>Administer</p>
        </BreadcrumbItem>
        <BreadcrumbItem>
          <Link to={appLink.userList()}>Users</Link>
        </BreadcrumbItem>
      </Breadcrumb>
    );
  };

  const role = user?.type && hasRoleLabel(user.type) ? UserRoleCopy[user.type] : (user?.type ?? "---");

  // The one page header: the person as the title, their facts on the right with Change role after them,
  // and the user's pages as tabs.
  return (
    <Header
      actions={
        !isError &&
        user && (
          <div className={styles.facts}>
            <dl>
              <div>
                <dt>Role</dt>
                <dd>{role}</dd>
              </div>
              <div>
                <dt>Status</dt>
                <dd className={styles.status} data-status={user.status}>
                  {isActive ? <CheckmarkFilled aria-hidden="true" /> : <Misuse aria-hidden="true" />}
                  {isActive ? "Active" : "Inactive"}
                </dd>
              </div>
              <div>
                <dt>Joined</dt>
                <dd>{moment(user.creationDate).format("D MMM YYYY")}</dd>
              </div>
              <div>
                <dt>Last sign-in</dt>
                <dd>{user.lastLoginDate ? moment(user.lastLoginDate).format("D MMM YYYY, h:mm A") : "---"}</dd>
              </div>
            </dl>
            <ComposedModal
              composedModalProps={{ shouldCloseOnOverlayClick: true }}
              modalTrigger={({ openModal }: any) => (
                <Button disabled={!userManagementEnabled} kind="tertiary" onClick={openModal} size="md">
                  Change role
                </Button>
              )}
              modalHeaderProps={{
                title: "User Role",
                subtitle: `Set ${user?.name ?? "user"}'s role in Flow. Admins can do more things.`,
              }}
            >
              {({ closeModal }) => {
                return <ChangeRole closeModal={closeModal} user={user} />;
              }}
            </ComposedModal>
          </div>
        )
      }
      includeBorder
      isLoading={isLoading}
      className={styles.container}
      nav={<NavigationComponent />}
      header={
        <div className={styles.titleRow}>
          {/* src="" on purpose: the old value pointed at GET /api/users/image/{email}, a v3-era
              users-service endpoint that no longer exists in service-core. A falsy src makes Avatar
              render its default user icon with no request at all. */}
          <Avatar size="medium" src="" userName={user?.email} />
          <HeaderTitle className={styles.title} title={user?.name}>
            {user?.name ?? "---"}
          </HeaderTitle>
          {user && emailIsValid(user.email) && <span className={styles.email}>{user.email}</span>}
        </div>
      }
      footer={
        !isError && (
          <section className={styles.headerActions}>
            <Tabs ariaLabel="User pages">
              <Tab
                end
                label={workspaceCount === undefined ? "Workspaces" : `Workspaces (${workspaceCount})`}
                to={appLink.user({ userId: user?.id ?? "" })}
                state={location.state}
              />
              <Tab end label="Labels" to={appLink.userLabels({ userId: user?.id ?? "" })} state={location.state} />
              <Tab end label="Settings" to={appLink.userSettings({ userId: user?.id ?? "" })} state={location.state} />
            </Tabs>
          </section>
        )
      }
    ></Header>
  );
}

export default UserDetailedHeader;
