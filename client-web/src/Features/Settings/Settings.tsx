import React, { useEffect, useRef } from "react";
import { Helmet } from "react-helmet";
import { useFetcher, useLoaderData, useRevalidator } from "react-router-dom";
import { Box } from "reflexbox";
import { Accordion } from "@carbon/react";
import {
  ErrorMessage,
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
  notify,
  ToastNotification,
} from "@boomerang-io/carbon-addons-boomerang-react";
import SettingsSection from "./SettingsSection";
import sortBy from "lodash/sortBy";
import EmptyState from "Components/EmptyState";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { DataDrivenInput } from "Types";
import styles from "./settings.module.scss";

export type SettingsGroup = {
  description: string;
  name: string;
  key: string;
  config: DataDrivenInput[];
};

// Server loader/action, same shift as Features/Parameters/GlobalParameters/GlobalParameters.tsx
// (the reference conversion): reads move to a `loader` that never throws (a failed fetch resolves
// with `errorLoading: true` so the route chrome still renders), writes move to a single `action`
// driven by `useFetcher()` below.
type LoaderData = {
  settings: SettingsGroup[];
  errorLoading: boolean;
};

export async function loader({ request }: { request: Request }): Promise<LoaderData> {
  try {
    const response = await serverFetch(request).get(serviceUrl.resourceSettings());
    return { settings: response.data, errorLoading: false };
  } catch (error) {
    return { settings: [], errorLoading: true };
  }
}

type ActionResult = {
  ok: boolean;
};

// Only one write happens on this route today, but the action still keys off `intent` (rather
// than assuming the sole POST is always "update settings") to match the established
// one-action-per-route convention and leave room for a second write without a shape change.
export async function action({ request }: { request: Request }): Promise<ActionResult> {
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent !== "update") {
    return { ok: false };
  }

  const settingsGroup = JSON.parse(String(formData.get("settingsGroup")));
  try {
    await serverFetch(request).put(serviceUrl.resourceSettings(), [settingsGroup]);
    return { ok: true };
  } catch (error) {
    return { ok: false };
  }
}

const FeatureLayout: React.FC<React.PropsWithChildren> = ({ children }) => {
  return (
    <>
      <Header
        className={styles.header}
        includeBorder={false}
        header={
          <>
            <HeaderTitle className={styles.headerTitle}>Settings</HeaderTitle>
            <HeaderSubtitle>Adjust Flow settings</HeaderSubtitle>
          </>
        }
      />
      <Box p="2rem" overflowY="auto" className={styles.container}>
        {children}
      </Box>
    </>
  );
};

const Settings: React.FC = () => {
  const { settings, errorLoading } = useLoaderData() as LoaderData;
  const fetcher = useFetcher<ActionResult>();
  const revalidator = useRevalidator();
  // onSave hands this component a Formik `setFieldError` at submit time; the fetcher settles
  // asynchronously, so - same as GlobalParameters.tsx's closeModalRef - the callback is stashed
  // here and invoked from the effect below only on success, matching the previous
  // mutateAsync-then behaviour of re-arming "initialerror" once the update actually succeeded.
  const setFieldErrorRef = useRef<((key: string, value: string) => void) | null>(null);

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    if (fetcher.data.ok) {
      notify(<ToastNotification title="Update Settings" subtitle="Settings succesfully updated" kind="success" />);
      revalidator.revalidate();
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
      <FeatureLayout>
        <ErrorMessage />
      </FeatureLayout>
    );
  }

  const sortedPlatformSettings = sortBy(settings, (settingObj) => settingObj.name);
  return (
    <FeatureLayout>
      <Helmet>
        <title>Settings</title>
      </Helmet>
      {!sortedPlatformSettings.length ? (
        <EmptyState />
      ) : (
        <Accordion>
          {sortedPlatformSettings.map((settingsGroup, index) => (
            <SettingsSection index={index} key={index} onSave={handleOnSave} settingsGroup={settingsGroup} />
          ))}
        </Accordion>
      )}
    </FeatureLayout>
  );
};

export default Settings;
