import {
  DecisionButtons,
  Loading,
  ModalForm,
  notify,
  TextArea,
  ToastNotification,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, InlineNotification, ModalBody, ModalFooter } from "@carbon/react";
import { ThumbsUp, ThumbsDown } from "@carbon/react/icons";
import React from "react";
import { useFetcher } from "react-router-dom";
import { Formik } from "formik";
import * as Yup from "yup";
import type { ActionResult } from "Features/WorkflowRun/WorkflowRun";
import { isActionError } from "Utils/actionResult";
import styles from "./taskApprovalModal.module.scss";

const GateStatus = {
  Approved: "APPROVED",
  Rejected: "REJECTED",
} as const;

type Props = {
  actionId?: string;
  closeModal: () => void;
};

function TaskApprovalModal({ actionId, closeModal }: Props) {
  // Submits to the run route's `action` (WorkflowRun.tsx), which revalidates the route loader
  // on completion - that replaces the invalidateQueries(getWorkflowRun) this used to do, and is
  // what refreshes the task list behind the modal once the approval is recorded.
  const fetcher = useFetcher<ActionResult>();
  const approvalsIsLoading = fetcher.state !== "idle";
  const approvalsError = Boolean(fetcher.data && isActionError(fetcher.data));
  const approvedRef = React.useRef(false);

  // The fetcher settles asynchronously, so the modal stays open with its spinner and closes only
  // once the submission actually succeeds - the same behaviour the awaited mutateAsync had.
  React.useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data || isActionError(fetcher.data)) {
      return;
    }
    notify(
      <ToastNotification
        kind="success"
        title="Manual Approval"
        subtitle={
          approvedRef.current ? "Successfully submitted approval request" : "Successfully submitted denial request"
        }
      />,
    );
    closeModal();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetcher.state, fetcher.data]);

  const handleSubmit = (values: { status: string; comment: string }) => {
    const approved = values.status === GateStatus.Approved;
    approvedRef.current = approved;
    fetcher.submit(
      { intent: "action", actionId: actionId ?? "", approved: String(approved), comments: values.comment ?? "" },
      { method: "post" },
    );
  };

  const buttons = [
    { icon: ThumbsDown, label: "Reject", type: "negative", value: GateStatus.Rejected },
    { icon: ThumbsUp, label: "Approve", type: "positive", value: GateStatus.Approved },
  ];

  return (
    <Formik
      enableReinitialize
      initialValues={{ comment: "", status: "" }}
      onSubmit={handleSubmit}
      validationSchema={Yup.object().shape({
        comment: Yup.string().nullable().max(200, "The comment must not have more than 200 characters"),
        status: Yup.string().nullable(),
      })}
    >
      {(props) => {
        const { values, handleSubmit, isValid, errors, touched, handleChange, setFieldValue, handleBlur } = props;

        const isApprovalApprovedOrRejected = Boolean(values.status);

        return (
          <ModalForm onSubmit={handleSubmit}>
            <ModalBody className={styles.modalBody}>
              {approvalsIsLoading ? (
                <Loading />
              ) : (
                <section className={styles.approval}>
                  <div className={styles.inputs}>
                    <div className={styles.comment}>
                      <TextArea
                        enableCounter
                        id={`comment`}
                        className={styles.commentArea}
                        labelText="Comments (optional)"
                        placeholder="Add some reasoning for your decision"
                        value={values?.comment}
                        onChange={handleChange}
                        onBlur={handleBlur}
                        invalid={Boolean(errors?.comment && touched?.comment)}
                        invalidText={errors?.comment}
                        maxCount={200}
                      />
                    </div>
                    <DecisionButtons
                      canUncheck
                      className={styles.decisionButtons}
                      items={buttons}
                      name={"decision-buttons"}
                      onChange={(value: string) => setFieldValue("status", value)}
                      selectedItem={values?.status}
                    />
                  </div>
                </section>
              )}
              {Boolean(approvalsError) && (
                <InlineNotification
                  style={{ marginBottom: "0.5rem" }}
                  lowContrast
                  kind="error"
                  title={"Manual Approval Failed"}
                  subtitle={"Something's Wrong"}
                />
              )}
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" type="button" onClick={closeModal}>
                Cancel
              </Button>
              <Button disabled={!isValid || approvalsIsLoading || !isApprovalApprovedOrRejected} type="submit">
                {!approvalsIsLoading ? "Submit" : "Submitting..."}
              </Button>
            </ModalFooter>
          </ModalForm>
        );
      }}
    </Formik>
  );
}

export default TaskApprovalModal;
