import React, { useMemo } from "react";
import { Layer } from "@carbon/react";
import { notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { Api, Parameter } from "@carbon/icons-react";
import { Gear, PlanningAnalytics, PlayerFlow, Workflows } from "@carbon/pictograms-react";
import cx from "classnames";
import kebabcase from "lodash/kebabCase";
import sortBy from "lodash/sortBy";
import queryString from "query-string";
import { useMutation, useQueryClient } from "react-query";
import { useHistory, useLocation } from "react-router-dom";
import HomeBanner from "Components/HomeBanner";
import LearnCard from "Components/LearnCard";
import TeamCard from "Components/TeamCard";
import TeamCardCreate from "Components/TeamCardCreate";
import WorkflowTemplateHomeCard from "Components/WorkflowTemplateHomeCard";
import { useAppContext } from "Hooks";
import { resolver, serviceUrl } from "Config/servicesConfig";
import { MemberRole } from "Types";
import styles from "./home.module.scss";

export default function Home() {
  const { teams, name, user, workflowTemplates } = useAppContext();
  const queryClient = useQueryClient();
  const location = useLocation();
  const history = useHistory();
  const { action, teamName } = queryString.parse(location.search);

  const createTeamMutator = useMutation(resolver.postTeam);

  const createTeam = async (values: { name: string | undefined }, success_fn?: (...args: any) => any) => {
    try {
      await createTeamMutator.mutateAsync({
        body: {
          name: kebabcase(values.name?.replace(`'`, "-")),
          displayName: values.name,
          members: [
            {
              email: user.email,
              role: MemberRole.Owner,
            },
          ],
        },
      });
      queryClient.invalidateQueries(serviceUrl.getUserProfile());
      notify(<ToastNotification kind="success" title="Create Team" subtitle="Team created successfully" />);
      if (typeof success_fn === "function") {
        success_fn();
      }
    } catch (error) {
      const errorMessages = formatErrorMessage({
        error: error,
      });
      notify(<ToastNotification kind="error" title={"Something went wrong"} subtitle={errorMessages.message} />);
    }
  };

  // Only run this once if we have a team
  React.useEffect(function createTeamOnLoad() {
    if (action === "create-team" && typeof teamName === "string" && Boolean(teamName)) {
      async function runCreateTeam() {
        await createTeam({ name: teamName as string }, () =>
          history.replace({ pathname: location.pathname, search: "" }),
        );
      }

      runCreateTeam();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const sortedTeams = useMemo(() => sortBy(teams, ["name"]), [teams]);

  return (
    <>
      <HomeBanner name={name} />
      <div className={styles.welcome}>
        <h1>Welcome, {user.displayName ? user.displayName : user.name}</h1>
      </div>
      <div>
        <Layer>
          <Section title="Your Teams">
            <nav className={styles.sectionLinks}>
              {sortedTeams ? sortedTeams?.map((team) => <TeamCard key={team.name} team={team} />) : null}
              <TeamCardCreate
                createTeam={createTeam}
                isError={createTeamMutator.isError}
                isLoading={createTeamMutator.isLoading}
              />
            </nav>
          </Section>
        </Layer>
        <Section title="Get Started With A Template" hasBorder>
          <nav className={styles.sectionLinks}>
            {workflowTemplates
              ? workflowTemplates?.map((template) => (
                  <WorkflowTemplateHomeCard template={template} teams={sortedTeams} />
                ))
              : null}
          </nav>
        </Section>
        <Section title="Explore and learn" hasBorder>
          <nav className={styles.sectionLinks}>
            <LearnCard
              icon={<Workflows style={{ height: "1.5rem", width: "1.5rem" }} />}
              key="first-workflow"
              title="Create your first Team & Workflow"
              description="Dive into the world of automation and create your first Workflow with our drag-and-drop designer."
              link="https://useboomerang.io/docs/introduction/getting-started"
              tags={["Getting started"]}
            />
            <LearnCard
              icon={<PlanningAnalytics style={{ height: "1.5rem", width: "1.5rem" }} />}
              key="activity"
              title="Explore Workflow activity"
              description="Gain control with execution activity and empower you to monitor, analyze, and optimize with precision and authority."
              link="https://useboomerang.io/docs/fundamentals/insights"
              tags={["Getting started"]}
            />
            <LearnCard
              icon={<PlayerFlow style={{ height: "1.5rem", width: "1.5rem" }} />}
              key="actions"
              title="Your Action to-do list"
              description="Focus on the approvals and manual actions that do need the visibility or analysis of a human."
              link="https://useboomerang.io/docs/fundamentals/actions"
              tags={["Next steps"]}
            />
            <LearnCard
              icon={<Gear style={{ height: "1.5rem", width: "1.5rem" }} />}
              key="manage"
              title="Manage your Team"
              description="Everything you need to manage your team effectively. Its members, workflows, approver groups, quotas, tokens, and more."
              link="https://useboomerang.io/docs/fundamentals/manage"
              tags={["Next steps"]}
            />
            <LearnCard
              icon={<Parameter style={{ height: "1.5rem", width: "1.5rem" }} />}
              key="manage"
              title="Parameter power"
              description="Learn the power of parameters and how to use them to make your workflows dynamic."
              link="https://useboomerang.io/docs/fundamentals/parameters"
              tags={["Advanced"]}
            />
            <LearnCard
              icon={<Api style={{ height: "1.5rem", width: "1.5rem" }} />}
              key="manage"
              title="External triggers & the API"
              description="Use external triggers & events to start workflows."
              link="https://useboomerang.io/docs/architecture/eventing"
              tags={["Advanced"]}
            />
          </nav>
        </Section>
      </div>
      <Section title="Key concepts" hasBorder>
        <nav className={styles.sectionLinks}>
          <div className={styles.conceptItem}>
            <h2>Workflows</h2>
            <p>The representation of the tasks and actions to consistently automate a process.</p>
          </div>
          <div className={styles.conceptItem}>
            <h2>Actions</h2>
            <p>Manual or approval based tasks that need human interaction</p>
          </div>
          <div className={styles.conceptItem}>
            <h2>Tasks</h2>
            <p>The discrete piece of work that performs the execution or action within a workflow</p>
          </div>
          <div className={styles.conceptItem}>
            <h2>Task Manager</h2>
            <p>The centralized place to define and manage the Tasks available to Workflows.</p>
          </div>
        </nav>
      </Section>
    </>
  );
}

interface SectionProps {
  children: React.ReactNode;
  title: string;
  hasBorder?: boolean;
}

const Section: React.FC<SectionProps> = ({ children, title, hasBorder = false }) => {
  return (
    <section className={cx(styles.section, { [styles.sectionBorder]: hasBorder })}>
      <h1 className={styles.sectionTitle}>{title}</h1>
      {children}
    </section>
  );
};
