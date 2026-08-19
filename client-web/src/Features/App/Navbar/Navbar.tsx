import React from "react";
import {
  SideNav,
  SideNavDivider,
  SideNavLink,
  SideNavItems,
  SideNavMenu,
  SideNavMenuItem,
  Switcher,
  SwitcherItem,
  SwitcherDivider,
  Tag,
} from "@carbon/react";
import { FlowData, ArrowsHorizontal, Settings } from "@carbon/react/icons";
import { UIShell, HeaderMenuItem } from "@boomerang-io/carbon-addons-boomerang-react";
import { Helmet } from "react-helmet";
import { NavLink } from "react-router-dom";
import * as navigationIcons from "Utils/navigationIcons";
import { APP_ROOT } from "Config/appConfig";
import { appLink } from "Config/appConfig";
import { FlowNavigationItem, FlowNavigationItemChild, FlowUser, ContextConfig } from "Types";
import styles from "./navbar.module.scss";

const skipToContentProps = {
  href: "#content",
};
interface NavbarProps {
  handleOnTutorialClick: () => void;
  flowNavigationData: Array<FlowNavigationItem>;
  contextData: ContextConfig;
  userData: FlowUser;
}

export default function Navbar({ handleOnTutorialClick, flowNavigationData, contextData, userData }: NavbarProps) {
  const { platform } = contextData;
  const appTitle = getAppTitle(platform);
  const appName = platform.appName || "Boomerang Flow";
  const platformName = platform.platformName;

  return (
    <>
      <Helmet defaultTitle={appTitle} titleTemplate={`%s - ${appTitle}`} />
      <UIShell
        config={contextData}
        leftPanel={(args) => <AppSideNav {...args} flowNavigationData={flowNavigationData} />}
        platformName={platformName}
        productName={appName}
        skipToContentProps={skipToContentProps}
        user={userData}
        supportMenuItems={[
          <HeaderMenuItem type="button" onClick={handleOnTutorialClick} text="Tutorial" />,
          <HeaderMenuItem type="link" kind="external" href="https://www.useboomerang.io/flow" text="Docs" />,
        ]}
        profileMenuItems={[
          <HeaderMenuItem
            icon={<Settings />}
            type="link"
            kind="app"
            href={APP_ROOT + appLink.profile()}
            text="Account Settings"
          />,
        ]}
        rightPanel={{
          icon: <ArrowsHorizontal size="20" />,
          component: (
            <Switcher aria-label="Your Workspaces">
              <li className={styles.switcherInfo}>
                <span>Your Workspaces</span>
              </li>
              <SwitcherDivider />
              {(userData.teams ?? []).map((workspace) => {
                return (
                  <SwitcherItem
                    aria-label={workspace.displayName}
                    key={workspace.name}
                    href={APP_ROOT + appLink.workflows({ workspace: workspace.name })}
                  >
                    {workspace.displayName}
                  </SwitcherItem>
                );
              })}
            </Switcher>
          ),
        }}
      />
    </>
  );
}

//TODO: figure out type error bc it works. I'm getting the arg type for the leftPanel function instead of writing it again
//@ts-ignore
type AppSideNavProps = Parameters<Parameters<typeof UIShell>[0]["leftPanel"]>[0] & {
  flowNavigationData: Array<FlowNavigationItem>;
};

type SideNavElemProps =
  | { to: string; activeClassName: string; element: React.ElementType; onClick: Function }
  | { href: string };

// FlowNavigationItemChild#renderIcon is typed as SVGElement in Types (a DOM node, not
// a component) - the actual value is always an icon component, so type it correctly here.
type SideNavLinkSharedProps = Pick<FlowNavigationItemChild, "large"> & { renderIcon: React.ComponentType; key: string };

const ACTIVE_CLASS_NAME = "cds--side-nav__link--current";

function isInternalLink(navUrl?: string) {
  return navUrl?.includes(APP_ROOT);
}

function getRelativePath(navUrl: string) {
  return navUrl.substring(navUrl.indexOf(APP_ROOT) + APP_ROOT.length);
}

// SideNavLink supports rendering as a router NavLink (via the polymorphic `element`
// prop), so internal links get real client-side navigation.
function getSideNavElemProps(item: FlowNavigationItem | FlowNavigationItemChild, close: Function): SideNavElemProps {
  if (isInternalLink(item.link)) {
    return {
      to: getRelativePath(item.link),
      activeClassName: ACTIVE_CLASS_NAME,
      element: NavLink,
      onClick: close,
    };
  }

  return { href: item.link };
}

// SideNavMenuItem (submenu children) has no polymorphic `element`/`as` prop - it only
// ever renders a plain anchor, so always give it a real href (previously internal
// links got `to`/`element`/`activeClassName`, which SideNavMenuItem doesn't understand,
// leaving the anchor with no href at all).
function getSideNavMenuItemProps(item: FlowNavigationItemChild, close: () => void): { href: string; onClick: () => void } {
  return { href: item.link, onClick: close };
}

function AppSideNav(props: AppSideNavProps) {
  return (
    <SideNav
      aria-label="nav"
      expanded={props.isOpen}
      isChildOfHeader={true}
      isPersistent={false}
      onOverlayClick={props.close}
    >
      <SideNavItems>
        {props.navLinks
          ? props.navLinks.map((link) => {
              return (
                <SideNavLink large key={link.url} href={link.url} onClick={props.close}>
                  {link.name}
                </SideNavLink>
              );
            })
          : undefined}
        {props.navLinks ? <SideNavDivider key="divider" /> : null}
        {props.flowNavigationData.map((item, index) => {
          const itemIcon = item.icon ? navigationIcons[item.icon as keyof typeof navigationIcons] : FlowData;
          if (item.type === "divider") {
            return <SideNavDivider key={`divider-${index}`} />;
          }
          if (item.type === "menu" && item?.childLinks) {
            return (
              <SideNavMenu large key={item.name} title={item.name} renderIcon={itemIcon}>
                {item.childLinks.map((childItem) => {
                  if (childItem.disabled) {
                    return (
                      <SideNavMenuItem className={styles.disabledSidenavLink} key={childItem.name}>
                        {childItem.name}
                      </SideNavMenuItem>
                    );
                  }

                  const elemProps = getSideNavMenuItemProps(childItem, props.close);
                  return (
                    <SideNavMenuItem key={childItem.name} {...elemProps}>
                      {childItem.name}
                    </SideNavMenuItem>
                  );
                })}
              </SideNavMenu>
            );
          }
          if (item.type === "link") {
            const sharedProps: SideNavLinkSharedProps = {
              large: true,
              renderIcon: itemIcon,
              key: item.name,
            };

            if (item.disabled) {
              return (
                <SideNavLink className={styles.disabledSidenavLink} {...sharedProps}>
                  {item.name} {item.beta ? <Tag>beta</Tag> : ""}
                </SideNavLink>
              );
            }

            const elemProps = getSideNavElemProps(item, props.close);
            return (
              <SideNavLink {...sharedProps} {...elemProps}>
                {item.name} {item.beta ? <Tag>beta</Tag> : ""}
              </SideNavLink>
            );
          }

          return null;
        })}
      </SideNavItems>
    </SideNav>
  );
}

function getAppTitle(platformData: ContextConfig["platform"]) {
  let appTitle = platformData.platformName;

  if (platformData.appName) {
    appTitle = `${platformData.appName} - ${platformData.platformName}`;
  }

  return appTitle;
}
