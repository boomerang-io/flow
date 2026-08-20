import React, { useEffect, useMemo, useRef } from "react";
import { Layer } from "@carbon/react";
import { notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { Api, Parameter } from "@carbon/icons-react";
import { Gear, PlanningAnalytics, PlayerFlow, Workflows } from "@carbon/pictograms-react";
import cx from "classnames";
import kebabcase from "lodash/kebabCase";
import sortBy from "lodash/sortBy";
import queryString from "query-string";
import { useFetcher, useNavigate, useLocation, useRevalidator } from "react-router-dom";
import HomeBanner from "Components/HomeBanner";
import LearnCard from "Components/LearnCard";
import WorkspaceCard from "Components/WorkspaceCard";
import WorkspaceCardCreate from "Components/WorkspaceCardCreate";
import WorkflowTemplateHomeCard from "Components/WorkflowTemplateHomeCard";
import { useAppContext } from "Hooks";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { HttpMethod } from "Constants";
import { MemberRole } from "Types";
import styles from "./home.module.scss";

// Home has no read of its own - workspaces/user/workflowTemplates come from useAppContext(),
// which App.tsx feeds (that layout route's own loader conversion is a separate, in-flight
// change; left untouched here). This route module is therefore write-only: one `action`,
// keyed by `intent`, covering the three mutations that live under this route - the one owned
// directly by this component (create-workspace) plus the two owned by WorkspaceCard (leave a
// workspace) and WorkflowTemplateHomeCard (create a workflow from a template). Those two
// components are only ever rendered inside Home (no route boundary between them and this file),
// so their own `useFetcher()` calls submit here by default without needing an explicit `action`
// target.
type ActionResult =
  | { ok: boolean; intent: "create-workspace"; displayName: string; errorMessage?: { title: string; message: string } }
  | { ok: boolean; intent: "leave-workspace"; displayName: string }
  | { ok: boolean; intent: "create-workflow-from-template"; workspace: string; workflow?: { name: string } };

export async function action({ request }: { request: Request }): Promise<ActionResult> {
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "leave-workspace") {
    const workspace = String(formData.get("workspace"));
    const displayName = String(formData.get("displayName"));
    try {
      await serverFetch(request).delete(serviceUrl.workspace.leaveWorkspace({ workspace }));
      return { ok: true, intent: "leave-workspace", displayName };
    } catch (error) {
      return { ok: false, intent: "leave-workspace", displayName };
    }
  }

  if (intent === "create-workflow-from-template") {
    const workspace = String(formData.get("workspace"));
    const body = JSON.parse(String(formData.get("body")));
    try {
      const response = await serverFetch(request)({
        url: serviceUrl.workspace.workflow.postCreateWorkflow({ workspace }),
        data: body,
        method: HttpMethod.Post,
      });
      return { ok: true, intent: "create-workflow-from-template", workspace, workflow: response.data };
    } catch (error) {
      return { ok: false, intent: "create-workflow-from-template", workspace };
    }
  }

  // Default / "create-workspace"
  const name = String(formData.get("name"));
  const displayName = String(formData.get("displayName"));
  const email = String(formData.get("email"));
  try {
    await serverFetch(request)({
      url: serviceUrl.postWorkspace(),
      data: {
        name: kebabcase(name.replace(`'`, "-")),
        displayName,
        members: [{ email, role: MemberRole.Owner }],
      },
      method: HttpMethod.Post,
    });
    return { ok: true, intent: "create-workspace", displayName };
  } catch (error) {
    return {
      ok: false,
      intent: "create-workspace",
      displayName,
      errorMessage: formatErrorMessage({ error, defaultMessage: "Something went wrong" }),
    };
  }
}

export default function Home() {
  const { workspaces, name, user, workflowTemplates } = useAppContext();
  // See the comment on `action` above: no loader lives here today, but revalidate() is the
  // loader-era refresh primitive regardless - once App.tsx's own loader conversion lands, this
  // starts re-running it for free. Never queryClient.invalidateQueries here (dead once a read is
  // loader-driven; see UserLabels/ChangeRole for the bug this already caused).
  const revalidator = useRevalidator();
  const location = useLocation();
  const navigate = useNavigate();
  const { action: queryAction, workspaceName } = queryString.parse(location.search);

  const createWorkspaceFetcher = useFetcher<ActionResult>();
  // handleSubmit hands this a `success_fn` at submit time (see WorkspaceCreateContent); the
  // fetcher settles asynchronously, so the callback is stashed here and invoked from the effect
  // below once creation actually succeeds - same pattern as GlobalParameters' closeModalRef.
  const successFnRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (createWorkspaceFetcher.state !== "idle" || !createWorkspaceFetcher.data) {
      return;
    }
    const result = createWorkspaceFetcher.data;
    if (result.intent !== "create-workspace") {
      return;
    }
    if (result.ok) {
      revalidator.revalidate();
      notify(<ToastNotification kind="success" title="Create Workspace" subtitle="Workspace created successfully" />);
      successFnRef.current?.();
      successFnRef.current = null;
    } else {
      notify(
        <ToastNotification kind="error" title={"Something went wrong"} subtitle={result.errorMessage?.message} />,
      );
    }
  }, [createWorkspaceFetcher.state, createWorkspaceFetcher.data]);

  const createWorkspace = (values: { name: string | undefined }, success_fn?: (...args: any) => any) => {
    successFnRef.current = typeof success_fn === "function" ? success_fn : null;
    createWorkspaceFetcher.submit(
      { intent: "create-workspace", name: values.name ?? "", displayName: values.name ?? "", email: user.email },
      { method: "post" },
    );
  };

  // Only run this once if we have a workspace
  useEffect(function createWorkspaceOnLoad() {
    if (queryAction === "create-workspace" && typeof workspaceName === "string" && Boolean(workspaceName)) {
      createWorkspace({ name: workspaceName as string }, () =>
        navigate({ pathname: location.pathname, search: "" }, { replace: true }),
      );
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const sortedWorkspaces = useMemo(() => sortBy(workspaces, ["name"]), [workspaces]);

  const isCreateWorkspaceError = Boolean(createWorkspaceFetcher.data && !createWorkspaceFetcher.data.ok);
  const isCreateWorkspaceLoading = createWorkspaceFetcher.state !== "idle";

  return (
    <>
      <HomeBanner name={name} />
      <div className={styles.welcome}>
        <h1>Welcome, {user.displayName ? user.displayName : user.name}</h1>
      </div>
      <div>
        <Layer>
          <Section title="Your Workspaces">
            <nav className={styles.sectionLinks}>
              {sortedWorkspaces ? sortedWorkspaces?.map((workspace) => <WorkspaceCard key={workspace.name} workspace={workspace} />) : null}
              <WorkspaceCardCreate
                createWorkspace={createWorkspace}
                isError={isCreateWorkspaceError}
                isLoading={isCreateWorkspaceLoading}
              />
            </nav>
          </Section>
        </Layer>
        <Section title="Get Started With A Template" hasBorder>
          <nav className={styles.sectionLinks}>
            {workflowTemplates
              ? workflowTemplates?.map((template) => (
                  <WorkflowTemplateHomeCard template={template} workspaces={sortedWorkspaces} />
                ))
              : null}
          </nav>
        </Section>
        <Section title="Explore and learn" hasBorder>
          <nav className={styles.sectionLinks}>
            <LearnCard
              icon={<Workflows style={{ height: "1.5rem", width: "1.5rem" }} />}
              key="first-workflow"
              title="Create your first Workspace & Workflow"
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
              title="Manage your Workspace"
              description="Everything you need to manage your workspace effectively. Its members, workflows, approver groups, quotas, tokens, and more."
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
