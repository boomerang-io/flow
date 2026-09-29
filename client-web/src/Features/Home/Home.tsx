import React, { useEffect, useMemo, useRef } from "react";
import { Button, InlineNotification } from "@carbon/react";
import { notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { formatErrorMessage } from "@boomerang-io/utils";
import { Add, Api, ArrowRight, Parameter } from "@carbon/react/icons";
import { PlayerFlow, Workflows } from "@carbon/pictograms-react";
import { useFeature } from "flagged";
import kebabcase from "lodash/kebabCase";
import sortBy from "lodash/sortBy";
import queryString from "query-string";
import { Link, useFetcher, useLoaderData, useLocation, useNavigate } from "react-router-dom";
import HomeBanner from "Components/HomeBanner";
import LearnCard from "Components/LearnCard";
import WorkspaceCard from "Components/WorkspaceCard";
import WorkspaceCardCreate from "Components/WorkspaceCardCreate";
import WorkflowTemplateHomeCard from "Components/WorkflowTemplateHomeCard";
import templateStyles from "Components/WorkflowTemplateHomeCard/workflowTemplateHomeCard.module.scss";
import { useAppContext } from "Hooks";
import { appLink, FeatureFlag } from "Config/appConfig";
import { serviceUrl } from "Config/servicesConfig";
import { serverFetch } from "Config/serverFetch";
import { HttpMethod } from "Constants";
import { MemberRole, ModalTriggerProps } from "Types";
import { actionError, isActionError, type ActionError } from "Utils/actionResult";
import AttentionList from "./AttentionList";
import { daySummary } from "./copy";
import { GettingStartedSteps, KeyConcepts } from "./GettingStarted";
import { HomeLoaderData } from "./homeLoader";
import PulseTiles from "./PulseTiles";
import RecentActivity from "./RecentActivity";
import styles from "./home.module.scss";

export { loader } from "./homeLoader";

// Home's read is the rollup in ./homeLoader.ts; the workspace list, user and templates still
// come from useAppContext(), which App.tsx feeds from the root loader. This route module's
// `action` is keyed by `intent` and covers the four mutations that live under this route: the
// one owned directly by this component (create-workspace) plus the three owned by components
// only ever rendered inside Home - WorkspaceCard (leave a workspace), WorkflowTemplateHomeCard
// (create a workflow from a template) and AttentionList (approve or reject an action). Those
// components' own `useFetcher()` calls submit here by default without an explicit `action`
// target. React Router revalidates every matched loader once a fetcher action settles, so the
// numbers refresh with no explicit call.
type ActionResult =
  | { intent: "create-workspace"; displayName: string }
  | ({ intent: "create-workspace"; displayName: string } & ActionError)
  | { intent: "leave-workspace"; displayName: string }
  | ({ intent: "leave-workspace"; displayName: string } & ActionError)
  | { intent: "create-workflow-from-template"; workspace: string; workflow?: { name: string } }
  | ({ intent: "create-workflow-from-template"; workspace: string } & ActionError)
  | { intent: "putAction" }
  | ({ intent: "putAction" } & ActionError);

export async function action({ request }: { request: Request }) {
  const formData = await request.formData();
  const intent = String(formData.get("intent"));

  if (intent === "leave-workspace") {
    const workspace = String(formData.get("workspace"));
    const displayName = String(formData.get("displayName"));
    try {
      await serverFetch(request).delete(serviceUrl.workspace.leaveWorkspace({ workspace }));
      return { intent: "leave-workspace" as const, displayName };
    } catch (error) {
      return actionError({
        intent: "leave-workspace" as const,
        displayName,
        error: { title: "Something's Wrong", message: "Request to leave workspace failed" },
      });
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
      return { intent: "create-workflow-from-template" as const, workspace, workflow: response.data };
    } catch (error) {
      return actionError({
        intent: "create-workflow-from-template" as const,
        workspace,
        error: { title: "Something's Wrong", message: "Request to create workflow from template failed" },
      });
    }
  }

  // The same PUT the Actions page sends (Features/Actions/Actions.tsx, intent "putAction"),
  // reached from Home's "Needs your attention" list. The workspace comes with the form because
  // this route has no `:workspace` segment.
  if (intent === "putAction") {
    const workspace = String(formData.get("workspace"));
    const body = JSON.parse(String(formData.get("body")));
    try {
      await serverFetch(request)({
        url: serviceUrl.workspace.action.putAction({ workspace }),
        data: body,
        method: HttpMethod.Put,
      });
      return { intent: "putAction" as const };
    } catch (error) {
      return actionError({
        intent: "putAction" as const,
        error: formatErrorMessage({ error, defaultMessage: "Request to action failed" }),
      });
    }
  }

  // Default / "create-workspace"
  const name = String(formData.get("name"));
  const displayName = String(formData.get("displayName"));
  const email = String(formData.get("email") ?? "");
  // No creator email (the security-off virtual user has none) means no owner member - sending a
  // placeholder string would register a garbage user record backend-side.
  const members = email && email !== "null" && email !== "undefined" ? [{ email, role: MemberRole.Owner }] : [];
  try {
    await serverFetch(request)({
      url: serviceUrl.postWorkspace(),
      data: {
        name: kebabcase(name.replace(`'`, "-")),
        displayName,
        members,
      },
      method: HttpMethod.Post,
    });
    return { intent: "create-workspace" as const, displayName };
  } catch (error) {
    return actionError({
      intent: "create-workspace" as const,
      displayName,
      error: formatErrorMessage({ error, defaultMessage: "Something went wrong" }),
    });
  }
}

const learnItems = [
  {
    key: "first-workflow",
    icon: <Workflows style={{ height: "1.5rem", width: "1.5rem" }} />,
    title: "Build your first workflow",
    description: "Tasks, links and the drag-and-drop designer.",
    link: "https://useboomerang.io/docs/introduction/getting-started",
    tags: ["Getting started"],
  },
  {
    key: "actions",
    icon: <PlayerFlow style={{ height: "1.5rem", width: "1.5rem" }} />,
    title: "Approvals and manual actions",
    description: "Put a person in the loop where it matters.",
    link: "https://useboomerang.io/docs/fundamentals/actions",
    tags: ["Next steps"],
  },
  {
    key: "parameters",
    icon: <Parameter style={{ height: "1.5rem", width: "1.5rem" }} />,
    title: "Parameters and results",
    description: "Make workflows dynamic and chain task outputs.",
    link: "https://useboomerang.io/docs/fundamentals/parameters",
    tags: ["Advanced"],
  },
  {
    key: "triggers",
    icon: <Api style={{ height: "1.5rem", width: "1.5rem" }} />,
    title: "Triggers, webhooks and the API",
    description: "Start runs from outside the product.",
    link: "https://useboomerang.io/docs/architecture/eventing",
    tags: ["Advanced"],
  },
];

export default function Home() {
  const { workspaces, name, user, workflowTemplates } = useAppContext();
  const data = useLoaderData() as HomeLoaderData;
  const singleWorkspaceEnabled = Boolean(useFeature(FeatureFlag.SingleWorkspaceEnabled));
  const activityEnabled = Boolean(useFeature(FeatureFlag.ActivityEnabled));
  const schedulesEnabled = Boolean(useFeature(FeatureFlag.SchedulesEnabled));
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
    if (!isActionError(result)) {
      notify(<ToastNotification kind="success" title="Create Workspace" subtitle="Workspace created successfully" />);
      successFnRef.current?.();
      successFnRef.current = null;
    } else {
      notify(<ToastNotification kind="error" title={"Something went wrong"} subtitle={result.error.message} />);
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

  const sortedWorkspaces = useMemo(() => sortBy(workspaces ?? [], ["name"]), [workspaces]);

  const isCreateWorkspaceError = Boolean(createWorkspaceFetcher.data && isActionError(createWorkspaceFetcher.data));
  const isCreateWorkspaceLoading = createWorkspaceFetcher.state !== "idle";

  const hasWorkspaces = sortedWorkspaces.length > 0;
  const workflowCount = sortedWorkspaces.reduce((sum, workspace) => sum + (workspace.insights?.workflows ?? 0), 0);
  const memberCount = sortedWorkspaces.reduce((sum, workspace) => sum + (workspace.insights?.members ?? 0), 0);
  // Where the hero's "Create workflow" and the tiles go when nothing more specific applies: the
  // workspace that ran most recently, else the first by name.
  const primaryWorkspace = data.recentRuns[0]?.workspace ?? sortedWorkspaces[0]?.name ?? "";
  const displayName = user.displayName || user.name;
  const showSteps = !hasWorkspaces || workflowCount === 0 || data.totalRuns === 0;

  // The create-workspace modal, mounted behind whichever trigger a spot needs. A single
  // workspace deployment has no create affordance anywhere.
  const createWorkspaceModal = (modalTrigger: (args: ModalTriggerProps) => React.ReactNode) =>
    singleWorkspaceEnabled ? null : (
      <WorkspaceCardCreate
        createWorkspace={createWorkspace}
        isError={isCreateWorkspaceError}
        isLoading={isCreateWorkspaceLoading}
        modalTrigger={modalTrigger}
      />
    );

  return (
    <div className={styles.page}>
      <HomeBanner
        eyebrow={`${data.dateLabel} · ${name}`}
        title={hasWorkspaces ? `Welcome back, ${displayName}.` : `Hi ${displayName}. Let's get your first automation running.`}
        message={
          hasWorkspaces
            ? daySummary({
                runsToday: data.runsToday.all,
                workspaces: sortedWorkspaces.length,
                attentionTotal: data.attentionTotal,
                attentionApprovals: data.attentionApprovals,
                attentionManual: data.attentionManual,
              })
            : "Workflows are graphs of tasks that run as containers. You need a workspace to hold them, then a workflow to run. Three steps, about ten minutes."
        }
        actions={
          hasWorkspaces ? (
            <>
              {createWorkspaceModal(({ openModal }) => (
                <Button kind="tertiary" renderIcon={Add} onClick={openModal} data-testid="home-create-workspace">
                  New workspace
                </Button>
              ))}
              <Button as={Link} to={appLink.workflows({ workspace: primaryWorkspace })} renderIcon={ArrowRight}>
                Create workflow
              </Button>
            </>
          ) : (
            createWorkspaceModal(({ openModal }) => (
              <Button renderIcon={ArrowRight} onClick={openModal} data-testid="home-create-workspace">
                Create your first workspace
              </Button>
            ))
          )
        }
      />

      {data.degraded ? (
        <div className={styles.degraded}>
          <InlineNotification
            kind="warning"
            lowContrast
            hideCloseButton
            title="Some numbers could not be loaded."
            subtitle="Parts of this page show zeros. Refresh to try again."
          />
        </div>
      ) : null}

      {hasWorkspaces ? (
        <div className={styles.pulse}>
          <PulseTiles
            data={data}
            primaryWorkspace={primaryWorkspace}
            workspaceCount={sortedWorkspaces.length}
            workflowCount={workflowCount}
            memberCount={memberCount}
            activityEnabled={activityEnabled}
            schedulesEnabled={schedulesEnabled}
          />
        </div>
      ) : null}

      {showSteps ? (
        <section className={styles.steps} aria-labelledby="get-started-title">
          <div className={styles.section}>
            <div className={styles.sectionHeader}>
              <h2 id="get-started-title" className={styles.sectionTitle}>
                Get started
              </h2>
            </div>
            <GettingStartedSteps
              hasWorkspace={hasWorkspaces}
              hasWorkflow={workflowCount > 0}
              hasRun={data.totalRuns > 0}
              primaryWorkspace={primaryWorkspace || undefined}
              createWorkspaceTrigger={createWorkspaceModal(({ openModal }) => (
                <Button renderIcon={Add} size="md" onClick={openModal}>
                  Create workspace
                </Button>
              ))}
            />
          </div>
        </section>
      ) : null}

      {hasWorkspaces && data.attention.length > 0 ? (
        <div className={styles.attention}>
          <Section
            id="needs-attention"
            title="Needs your attention"
            link={{ to: appLink.actions({ workspace: data.attention[0].workspace }), text: "All actions" }}
          >
            <AttentionList items={data.attention} total={data.attentionTotal} />
          </Section>
        </div>
      ) : null}

      {hasWorkspaces ? (
        <div className={styles.columns}>
          <div className={styles.column}>
            <Section id="your-workspaces" title="Your workspaces">
              <div className={styles.cardGrid}>
                {sortedWorkspaces.map((workspace) => {
                  const stats = data.stats[workspace.name];
                  return (
                    <WorkspaceCard
                      key={workspace.name}
                      workspace={workspace}
                      stats={stats ? { runsToday: stats.runsToday.all, lastRun: stats.lastRun } : undefined}
                    />
                  );
                })}
                {singleWorkspaceEnabled ? null : (
                  <WorkspaceCardCreate
                    createWorkspace={createWorkspace}
                    isError={isCreateWorkspaceError}
                    isLoading={isCreateWorkspaceLoading}
                  />
                )}
              </div>
            </Section>
            <Section
              id="recent-activity"
              title="Recent activity"
              link={activityEnabled ? { to: appLink.activity({ workspace: primaryWorkspace }), text: "All activity" } : undefined}
            >
              <RecentActivity runs={data.recentRuns} />
            </Section>
          </div>
          <div className={styles.column}>
            <Section id="start-a-workflow" title="Start a workflow">
              <div className={styles.list}>
                <Link
                  to={appLink.workflows({ workspace: primaryWorkspace })}
                  className={templateStyles.row}
                  data-testid="home-blank-workflow"
                >
                  <span className={templateStyles.icon} aria-hidden="true">
                    <Add />
                  </span>
                  <span className={templateStyles.body}>
                    <span className={templateStyles.name}>Blank workflow</span>
                    <span className={templateStyles.description}>Start from an empty canvas</span>
                  </span>
                </Link>
                {workflowTemplates?.map((template) => (
                  <WorkflowTemplateHomeCard key={template.name} template={template} workspaces={sortedWorkspaces} />
                ))}
              </div>
            </Section>
            <Section id="learn" title="Learn" link={{ href: "https://useboomerang.io/docs", text: "Docs" }}>
              <div className={styles.panel}>
                {learnItems.map(({ key, ...item }) => (
                  <LearnCard key={key} {...item} />
                ))}
              </div>
            </Section>
          </div>
        </div>
      ) : (
        <div className={styles.concepts}>
          <Section id="key-concepts" title="Four things to know" link={{ href: "https://useboomerang.io/docs", text: "Docs" }}>
            <KeyConcepts />
          </Section>
        </div>
      )}
    </div>
  );
}

interface SectionProps {
  id: string;
  title: string;
  link?: { to?: string; href?: string; text: string };
  children: React.ReactNode;
}

function Section({ id, title, link, children }: SectionProps) {
  return (
    <section id={id} className={styles.section} aria-labelledby={`${id}-title`}>
      <div className={styles.sectionHeader}>
        <h2 id={`${id}-title`} className={styles.sectionTitle}>
          {title}
        </h2>
        {link?.href ? (
          <a className={styles.sectionLink} href={link.href} target="_blank" rel="noreferrer">
            {link.text}
          </a>
        ) : link?.to ? (
          <Link className={styles.sectionLink} to={link.to}>
            {link.text}
          </Link>
        ) : null}
      </div>
      {children}
    </section>
  );
}
