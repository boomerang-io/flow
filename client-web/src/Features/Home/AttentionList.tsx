import React, { useEffect } from "react";
import { Link, useFetcher } from "react-router-dom";
import { Button, InlineLoading, Tag } from "@carbon/react";
import { notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment";
import { appLink } from "Config/appConfig";
import { ActionType } from "Constants";
import { isActionError, type ActionError } from "Utils/actionResult";
import { HomeAction } from "./homeLoader";
import styles from "./attentionList.module.scss";

interface AttentionListProps {
  items: Array<HomeAction>;
  total: number;
}

// Submits to Home's `action` (Features/Home/Home.tsx, intent "putAction") - the same PUT the
// Actions page's ApproveRejectActions sends, minus the comment: a quick decision from the landing
// page. Anyone who wants to leave a comment goes through "View run" or the Actions page.
type PutActionResult = { intent: "putAction" } | ({ intent: "putAction" } & ActionError);

export default function AttentionList({ items, total }: AttentionListProps) {
  const fetcher = useFetcher<PutActionResult>();
  const pendingId = fetcher.state !== "idle" ? String(fetcher.formData?.get("actionId") ?? "") : "";

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || fetcher.data.intent !== "putAction") {
      return;
    }
    if (!isActionError(fetcher.data)) {
      notify(<ToastNotification kind="success" title="Action" subtitle="Your decision was recorded" />);
    } else {
      notify(<ToastNotification kind="error" title={fetcher.data.error.title} subtitle={fetcher.data.error.message} />);
    }
  }, [fetcher.state, fetcher.data]);

  const decide = (action: HomeAction, approved: boolean) => {
    fetcher.submit(
      {
        intent: "putAction",
        workspace: action.workspace,
        actionId: action.id,
        body: JSON.stringify([{ id: action.id, approved, comments: "" }]),
      },
      { method: "post" },
    );
  };

  return (
    <ul className={styles.list} aria-label="Actions waiting on you">
      {items.map((action) => {
        const isApproval = action.type === ActionType.Approval;
        const isPending = pendingId === action.id;
        const title = action.taskName || action.workflowName;
        const meta = [
          action.taskName ? action.workflowName : null,
          action.workspaceDisplayName,
          isApproval && action.approvalsRequired > 1 ? `${action.numberOfApprovals}/${action.approvalsRequired} approvals` : null,
          moment(action.creationDate).fromNow(),
        ]
          .filter(Boolean)
          .join(" · ");
        return (
          <li key={action.id} className={styles.row} data-testid="home-attention-row">
            <Tag type={isApproval ? "purple" : "teal"} size="sm">
              {isApproval ? "Approval" : "Manual task"}
            </Tag>
            <div className={styles.body}>
              <p className={styles.title} title={title}>
                {title}
              </p>
              <p className={styles.meta}>{meta}</p>
            </div>
            <div className={styles.controls}>
              {isPending ? (
                <InlineLoading description="Saving decision" />
              ) : isApproval ? (
                <>
                  <Link
                    className={styles.link}
                    to={appLink.execution({ workspace: action.workspace, runId: action.workflowRunRef })}
                  >
                    View run
                  </Link>
                  <Button
                    kind="danger--tertiary"
                    size="sm"
                    disabled={fetcher.state !== "idle"}
                    onClick={() => decide(action, false)}
                  >
                    Reject
                  </Button>
                  <Button size="sm" disabled={fetcher.state !== "idle"} onClick={() => decide(action, true)}>
                    Approve
                  </Button>
                </>
              ) : (
                <Button as={Link} kind="tertiary" size="sm" to={appLink.actionsManual({ workspace: action.workspace })}>
                  Open in Actions
                </Button>
              )}
            </div>
          </li>
        );
      })}
      {total > items.length ? (
        <li className={styles.meta}>
          Showing {items.length} of {total}.
        </li>
      ) : null}
    </ul>
  );
}
