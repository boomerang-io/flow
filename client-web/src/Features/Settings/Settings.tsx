import React, { useEffect, useRef } from "react";
import { Helmet } from "react-helmet";
import { redirect, useFetcher, useLoaderData, useParams } from "react-router-dom";
import {
  ErrorMessage,
  FeatureSideNav as SideNav,
  FeatureSideNavLink as SideNavLink,
  FeatureSideNavLinks as SideNavLinks,
  notify,
  ToastNotification,
} from "@boomerang-io/carbon-addons-boomerang-react";
import sortBy from "lodash/sortBy";
import EmptyState from "Components/EmptyState";
import { appLink } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { DataDrivenInput } from "Types";
import { actionError, isActionError, type ActionError } from "Utils/actionResult";
import SettingsSection from "./SettingsSection";
import styles from "./settings.module.scss";

export type SettingsGroup = {
  description: string;
  name: string;
  key: string;
  config: DataDrivenInput[];
};

// Route module for the Settings tab of the Manage area (path "/admin/settings/:group?"). The
// selected group is the URL, so a section is linkable and the browser's back button walks
// sections; without one the page sends the caller to the first group by name. Reads live in a
// `loader` that never throws (a failed fetch resolves with `errorLoading: true` so the tab chrome
// still renders); the one write is the `action` driven by `useFetcher()` below.
type LoaderData = {
  settings: SettingsGroup[];
  errorLoading: boolean;
};

// The group is part of the URL. Without one, or with one the settings do not hold, the loader
// redirects to the first group by name server-side, so the page never paints and then jumps.
export async function loader({ params = {}, request }: { params?: { group?: string }; request: Request }) {
  let settings: SettingsGroup[];
  try {
    settings = (await serverFetch(request).get(serviceUrl.resourceSettings())).data;
  } catch (error) {
    return { settings: [], errorLoading: true } satisfies LoaderData;
  }
  const sorted = sortBy(settings, (settingsGroup) => settingsGroup.name);
  if (sorted.length > 0 && !sorted.some((settingsGroup) => settingsGroup.key === params.group)) {
    return redirect(appLink.settingsGroup({ group: sorted[0].key }));
  }
  return { settings, errorLoading: false } satisfies LoaderData;
}

type ActionResult = Record<string, never> | ActionError;

// Only one write happens on this route today, but the action still keys off `intent` (rather
// than assuming the sole POST is always "update settings") to match the established
// one-action-per-route convention and leave room for a second write without a shape change.
export async function action({ request }: { request: Request }) {
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent !== "update") {
    return actionError({ error: { title: "Something's Wrong", message: "Unknown action" } });
  }

  const settingsGroup = JSON.parse(String(formData.get("settingsGroup")));
  try {
    await serverFetch(request).put(serviceUrl.resourceSettings(), [settingsGroup]);
    return {};
  } catch (error) {
    return actionError({ error: { title: "Something's Wrong", message: "Request to update settings failed" } });
  }
}

const Settings: React.FC = () => {
  const { settings, errorLoading } = useLoaderData() as LoaderData;
  const { group } = useParams<{ group?: string }>();
  const fetcher = useFetcher<ActionResult>();
  // onSave hands this component a Formik `setFieldError` at submit time; the fetcher settles
  // asynchronously, so the callback is stashed here and invoked from the effect below only on
  // success, re-arming "initialerror" once the update actually succeeded.
  const setFieldErrorRef = useRef<((key: string, value: string) => void) | null>(null);

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (!isActionError(fetcher.data)) {
      notify(<ToastNotification title="Update Settings" subtitle="Settings succesfully updated" kind="success" />);
      setFieldErrorRef.current?.("initialerror", "required");
      setFieldErrorRef.current = null;
    } else {
      notify(<ToastNotification title="Something's Wrong" subtitle="Request to update settings failed" kind="error" />);
    }
  }, [fetcher.state, fetcher.data]);

  const handleOnSave = (
    values: { [key: string]: any },
    settingsGroup: SettingsGroup,
    setFieldError: (key: string, value: string) => void,
  ) => {
    setFieldErrorRef.current = setFieldError;
    const newConfig = settingsGroup.config.map((input: any) => ({ ...input, value: values[input.key] }));
    const requestBody = { ...settingsGroup, config: newConfig };
    fetcher.submit({ intent: "update", settingsGroup: JSON.stringify(requestBody) }, { method: "post" });
  };

  if (errorLoading) {
    return (
      <div className={styles.container}>
        <ErrorMessage />
      </div>
    );
  }

  const sortedGroups = sortBy(settings, (settingsGroup) => settingsGroup.name);
  if (sortedGroups.length === 0) {
    return (
      <div className={styles.container}>
        <EmptyState />
      </div>
    );
  }

  // The loader already redirected any URL without a known group; the fallback keeps the page
  // whole if the two ever disagree.
  const selected = sortedGroups.find((settingsGroup) => settingsGroup.key === group) ?? sortedGroups[0];

  return (
    <div className={styles.layout}>
      <Helmet>
        <title>{`${selected.name} - Settings`}</title>
      </Helmet>
      <SideNav className={styles.nav} border="right">
        <SideNavLinks>
          {sortedGroups.map((settingsGroup) => (
            <SideNavLink key={settingsGroup.key} to={appLink.settingsGroup({ group: settingsGroup.key })}>
              {settingsGroup.name}
            </SideNavLink>
          ))}
        </SideNavLinks>
      </SideNav>
      <div className={styles.container}>
        <SettingsSection key={selected.key} onSave={handleOnSave} settingsGroup={selected} />
      </div>
    </div>
  );
};

export default Settings;
