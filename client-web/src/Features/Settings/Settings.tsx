import React from "react";
import { Helmet } from "react-helmet";
import { useQuery, useMutation, useQueryClient } from "react-query";
import { Box } from "reflexbox";
import { Accordion, SkeletonPlaceholder } from "@carbon/react";
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
import { serviceUrl, resolver } from "Config/servicesConfig";
import { DataDrivenInput } from "Types";
import styles from "./settings.module.scss";

export type SettingsGroup = {
  description: string;
  name: string;
  key: string;
  config: DataDrivenInput[];
};

const platformSettingsUrl = serviceUrl.resourceSettings();

const FeatureLayout: React.FC = ({ children }) => {
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
  const queryClient = useQueryClient();

  const { data, error, isLoading } = useQuery({
    queryKey: platformSettingsUrl,
    queryFn: resolver.query(platformSettingsUrl),
  });

  const { mutateAsync: updateSettingMutator } = useMutation(resolver.putPlatformSettings, {
    onSuccess: () => queryClient.invalidateQueries(platformSettingsUrl),
  });

  const handleOnSave = async (
    values: { [key: string]: any },
    settingsGroup: SettingsGroup,
    setFieldError: (key: string, value: string) => void
  ) => {
    const newConfig = settingsGroup.config.map((input: any) => ({ ...input, value: values[input.key] }));
    const requestBody = [{ ...settingsGroup, config: newConfig }];
    try {
      await updateSettingMutator({ body: requestBody });
      notify(<ToastNotification title="Update Settings" subtitle="Settings succesfully updated" kind="success" />);
      setFieldError("initialerror", "required");
    } catch (e) {
      notify(<ToastNotification title="Something's Wrong" subtitle="Request to update settings failed" kind="error" />);
    }
  };

  if (isLoading) {
    return (
      <FeatureLayout>
        <Box className={styles.skeletonPlacholderContainer}>
          <SkeletonPlaceholder />
          <SkeletonPlaceholder />
          <SkeletonPlaceholder />
          <SkeletonPlaceholder />
          <SkeletonPlaceholder />
        </Box>
      </FeatureLayout>
    );
  }

  if (error) {
    return (
      <FeatureLayout>
        <ErrorMessage />
      </FeatureLayout>
    );
  }

  const sortedPlatformSettings = sortBy(data, (settingObj) => settingObj.name);
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
