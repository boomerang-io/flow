import React from "react";
import { useHistory } from "react-router-dom";
import { useQuery } from "react-query";
import { Breadcrumb, BreadcrumbItem, Link } from "@carbon/react";
import {
  FeatureHeader as Header,
  FeatureHeaderTitle as HeaderTitle,
  FeatureHeaderSubtitle as HeaderSubtitle,
  Error,
} from "@boomerang-io/carbon-addons-boomerang-react";
import IntegrationCard from "Components/IntegrationCard";
import { IntegrationCardSkeleton } from "Components/IntegrationCard";
import { useTeamContext } from "Hooks";
import { appLink } from "Config/appConfig";
import { serviceUrl, resolver } from "Config/servicesConfig";
import { FlowTeam, Integration } from "Types";
import styles from "./integrations.module.scss";

export default function Integrations() {
  const { team } = useTeamContext();
  const history = useHistory();

  const getIntegrationsUrl = serviceUrl.getIntegrations({ team: team?.name });
  const integrationsQuery = useQuery<Array<Integration>, string>({
    queryKey: getIntegrationsUrl,
    queryFn: resolver.query(getIntegrationsUrl),
  });

  // TODO: make this smarter bc we shouldn't get to the route without an active team
  if (!team) {
    return history.push(appLink.home());
  }

  if (integrationsQuery.isLoading) {
    return (
      <Layout team={team}>
        <IntegrationCardSkeleton />
      </Layout>
    );
  }

  if (integrationsQuery.error) {
    return (
      <Layout team={team}>
        <Error />
      </Layout>
    );
  }

  if (integrationsQuery.data) {
    return (
      <Layout team={team}>
        <div className={styles.workflows}>
          {integrationsQuery.data.map((template: Integration) => (
            <IntegrationCard key={template.id} teamName={team.name} data={template} url={getIntegrationsUrl} />
          ))}
        </div>
      </Layout>
    );
  }
  return null;
}

interface LayoutProps {
  team: FlowTeam;
  children: React.ReactNode;
}

function Layout(props: LayoutProps) {
  const NavigationComponent = () => {
    return (
      <Breadcrumb noTrailingSlash>
        <BreadcrumbItem>
          <Link to={appLink.home()}>Home</Link>
        </BreadcrumbItem>
        <BreadcrumbItem isCurrentPage>
          <p>{props.team.name}</p>
        </BreadcrumbItem>
      </Breadcrumb>
    );
  };

  return (
    <div className={styles.container}>
      <Header
        className={styles.header}
        includeBorder={false}
        nav={<NavigationComponent />}
        header={
          <>
            <HeaderTitle>Integrations</HeaderTitle>
            <HeaderSubtitle>Extend your Workflows by using integrations for your favorite tools.</HeaderSubtitle>
          </>
        }
      />
      <div aria-label="My Integrations" className={styles.content} role="region" id="my-integrations">
        <section className={styles.sectionContainer}>{props.children}</section>
      </div>
    </div>
  );
}
