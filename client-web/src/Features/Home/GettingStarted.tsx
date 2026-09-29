import React from "react";
import { Link } from "react-router-dom";
import { Button } from "@carbon/react";
import { ArrowRight, Checkmark, Launch, Parameter } from "@carbon/react/icons";
import { Gear, PlayerFlow, Workflows } from "@carbon/pictograms-react";
import cx from "classnames";
import { appLink } from "Config/appConfig";
import styles from "./gettingStarted.module.scss";

interface GettingStartedProps {
  hasWorkspace: boolean;
  hasWorkflow: boolean;
  hasRun: boolean;
  /** Where "Build a workflow" and "Run it" send the user - the first workspace, or the busiest. */
  primaryWorkspace?: string;
  /** The create-workspace modal's trigger, rendered as step one's call to action. */
  createWorkspaceTrigger?: React.ReactNode;
}

type StepState = "done" | "active" | "locked";

interface StepProps {
  index: number;
  state: StepState;
  title: string;
  body: string;
  action?: React.ReactNode;
  hint: string;
}

function Step({ index, state, title, body, action, hint }: StepProps) {
  return (
    <li className={cx(styles.step, { [styles.stepActive]: state === "active", [styles.stepDone]: state === "done" })}>
      <div className={styles.stepHeader}>
        <span
          className={cx(styles.badge, { [styles.badgeActive]: state === "active", [styles.badgeDone]: state === "done" })}
          aria-hidden="true"
        >
          {state === "done" ? <Checkmark size={16} /> : index}
        </span>
        <h3 className={cx(styles.stepTitle, { [styles.stepTitleMuted]: state === "locked" })}>{title}</h3>
      </div>
      <p className={styles.stepBody}>{body}</p>
      <div className={styles.stepFooter}>
        {state === "active" && action ? (
          action
        ) : (
          <span className={styles.stepHint}>{state === "done" ? "Done" : hint}</span>
        )}
      </div>
    </li>
  );
}

/**
 * The three things a new account has to do once, keyed off real counts (workspaces, workflows,
 * runs). Home shows it until all three are done, then never again.
 */
export function GettingStartedSteps(props: GettingStartedProps) {
  const { hasWorkspace, hasWorkflow, hasRun, primaryWorkspace, createWorkspaceTrigger } = props;
  const stateOf = (done: boolean, unlocked: boolean): StepState => (done ? "done" : unlocked ? "active" : "locked");
  const workflowsLink = primaryWorkspace ? appLink.workflows({ workspace: primaryWorkspace }) : appLink.home();

  return (
    <ol className={styles.steps} aria-label="Get started">
      <Step
        index={1}
        state={stateOf(hasWorkspace, true)}
        title="Create a workspace"
        body="A workspace holds workflows, members, parameters and quotas. You become its owner."
        action={createWorkspaceTrigger}
        hint="Ask a workspace owner to add you"
      />
      <Step
        index={2}
        state={stateOf(hasWorkflow, hasWorkspace)}
        title="Build a workflow"
        body="Start from a template or a blank canvas. Drag tasks on, link them, set parameters."
        action={
          <Button as={Link} to={workflowsLink} renderIcon={ArrowRight} size="md">
            Go to workflows
          </Button>
        }
        hint="Unlocks after step 1"
      />
      <Step
        index={3}
        state={stateOf(hasRun, hasWorkflow)}
        title="Run it and watch Activity"
        body="Run it by hand, then add a schedule or a webhook trigger. Every run is recorded with logs."
        action={
          <Button as={Link} to={workflowsLink} renderIcon={ArrowRight} size="md">
            Run a workflow
          </Button>
        }
        hint="Unlocks after step 2"
      />
    </ol>
  );
}

const concepts = [
  {
    title: "Workflows",
    body: "A graph of tasks that automates a process the same way every time.",
    href: "https://useboomerang.io/docs/introduction/getting-started",
    Icon: Workflows,
  },
  {
    title: "Tasks",
    body: "One unit of work, run as a container. Pick from the catalogue or add your own.",
    href: "https://useboomerang.io/docs/fundamentals/tasks",
    Icon: Gear,
  },
  {
    title: "Actions",
    body: "Approvals and manual steps where a person has to decide.",
    href: "https://useboomerang.io/docs/fundamentals/actions",
    Icon: PlayerFlow,
  },
  {
    title: "Parameters",
    body: "Inputs and results that flow between tasks, workflows and workspaces.",
    href: "https://useboomerang.io/docs/fundamentals/parameters",
    Icon: Parameter,
  },
];

/** Four one-line definitions, replacing the old "Key concepts" list. External links open the docs. */
export function KeyConcepts() {
  return (
    <nav className={styles.concepts} aria-label="Key concepts">
      {concepts.map(({ title, body, href, Icon }) => (
        <a key={title} className={styles.concept} href={href} target="_blank" rel="noreferrer">
          <Icon style={{ height: "1.5rem", width: "1.5rem" }} aria-hidden="true" />
          <h3 className={styles.conceptTitle}>
            {title} <Launch size={12} aria-label="Opens the docs in a new tab" />
          </h3>
          <p className={styles.conceptBody}>{body}</p>
        </a>
      ))}
    </nav>
  );
}
