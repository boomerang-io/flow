import React, { useEffect, useRef } from "react";
import { useFetcher, useRevalidator } from "react-router-dom";
import { Formik } from "formik";
import * as Yup from "yup";
import { useAppContext } from "Hooks";
import {
  ComposedModal,
  Loading,
  ModalForm,
  notify,
  TextArea,
  ToastNotification,
} from "@boomerang-io/carbon-addons-boomerang-react";
import {
  Button,
  InlineNotification,
  ModalBody,
  ModalFooter,
  StructuredListWrapper,
  StructuredListHead,
  StructuredListBody,
  StructuredListRow,
  StructuredListCell,
} from "@carbon/react";
import type { ActionResult } from "Features/Actions/Actions";
import { Action, ApprovalStatus } from "Types";
import dateHelper from "Utils/dateHelper";
import styles from "./ApproveRejectActions.module.scss";

const ModalType = {
  Single: "single",
  Approve: "approve",
  Reject: "reject",
};

type ApproveRejectActionsProps = {
  actions: Action[];
  isAlreadyApproved?: boolean;
  handleCloseModal?: (args?: any) => any;
  modalTrigger: (args: any) => any;
  onSuccessfulApprovalRejection: () => any;
  type: "single" | "approve" | "reject";
};

function ApproveRejectActions({
  actions,
  isAlreadyApproved = false,
  handleCloseModal,
  modalTrigger,
  onSuccessfulApprovalRejection,
  type,
}: ApproveRejectActionsProps) {
  let title = "Approve selected actions";
  let subtitle = `You have selected ${actions.length} action${
    actions.length > 1 ? "s" : ""
  } to approve. Check the details are correct, add optional comments, and then click Approve.`;

  if (type === ModalType.Single) {
    title = "Action details";
    subtitle = "";
  } else if (type === ModalType.Reject) {
    title = "Reject selected actions";
    subtitle = `You have selected ${actions.length} action${
      actions.length > 1 ? "s" : ""
    } to reject. Check the details are correct, add optional comments, and then click Reject.`;
  }

  return (
    <ComposedModal
      modalTrigger={modalTrigger}
      composedModalProps={{ containerClassName: styles.modalContainer, shouldCloseOnOverlayClick: false }}
      modalHeaderProps={{ title, subtitle }}
      onCloseModal={() => {
        handleCloseModal && handleCloseModal();
      }}
    >
      {(props: any) => (
        <Form
          actions={actions}
          isAlreadyApproved={isAlreadyApproved}
          onSuccessfulApprovalRejection={onSuccessfulApprovalRejection}
          type={type}
          {...props}
        />
      )}
    </ComposedModal>
  );
}

type FormProps = {
  actions: Action[];
  closeModal: (args?: any) => void;
  isAlreadyApproved: boolean;
  onSuccessfulApprovalRejection: () => any;
  type: string;
};

function Form({ actions, closeModal, isAlreadyApproved, onSuccessfulApprovalRejection, type }: FormProps) {
  const { user } = useAppContext();
  const revalidator = useRevalidator();
  const fetcher = useFetcher<ActionResult>();
  const [approveLoading, setApproveLoading] = React.useState(false);
  const [rejectLoading, setRejectLoading] = React.useState(false);
  // handleActions hands this a { notificationTitle, notificationSubtitle } pair at submit time;
  // the fetcher settles asynchronously, so it's stashed here and consumed from the effect below
  // once the PUT actually resolves - see GlobalParameters.tsx for the identical pattern. Refresh
  // via useRevalidator().revalidate() rather than react-query's queryClient.invalidateQueries -
  // once the read is loader-driven, invalidateQueries is an inert no-op (see CLAUDE.md).
  const pendingNotificationRef = useRef<{ title: string; subtitle: string } | null>(null);

  useEffect(() => {
    if (fetcher.state !== "idle" || !fetcher.data) {
      return;
    }
    setApproveLoading(false);
    setRejectLoading(false);
    if (fetcher.data.ok) {
      onSuccessfulApprovalRejection();
      revalidator.revalidate();
      if (pendingNotificationRef.current) {
        notify(
          <ToastNotification
            kind="success"
            subtitle={pendingNotificationRef.current.subtitle}
            title={pendingNotificationRef.current.title}
          />,
        );
      }
      pendingNotificationRef.current = null;
      closeModal();
    }
    // failures leave the modal open - the InlineNotification below (driven off
    // actionsPutError/fetcher.data) surfaces the error inline, matching the previous behaviour.
  }, [fetcher.state, fetcher.data]);

  const actionsIsLoading = fetcher.state !== "idle";
  const actionsPutError = Boolean(fetcher.data && !fetcher.data.ok);

  const handleActions =
    ({ approved, notificationSubtitle, notificationTitle, setLoading, values }: any) =>
    () => {
      typeof setLoading === "function" && setLoading(true);
      let request: any = [];

      Object.keys(values).forEach((actionId) => {
        request.push({ ...values[actionId], id: actionId, approved });
      });

      pendingNotificationRef.current = { title: notificationTitle, subtitle: notificationSubtitle };
      fetcher.submit({ intent: "putAction", body: JSON.stringify(request) }, { method: "post" });
    };

  let initialValues: any = {};
  let validationSchema: any = {};
  actions.forEach((action) => {
    initialValues[action.id] = { comments: "" };
    validationSchema[action.id] = Yup.object().shape({
      comments: Yup.string().nullable().max(200, "The comment must not have more than 200 characters"),
    });
  });

  return (
    <Formik
      enableReinitialize
      initialValues={initialValues}
      onSubmit={() => {}}
      validationSchema={Yup.object().shape(validationSchema)}
    >
      {(props) => {
        const { isValid, values } = props;

        return (
          <ModalForm>
            <ModalBody className={styles.modalBody}>
              {actionsIsLoading ? <Loading /> : null}
              {type === ModalType.Single ? (
                <SingleActionSection
                  action={actions[0]}
                  formikBag={props}
                  isAlreadyApproved={isAlreadyApproved}
                  user={user}
                />
              ) : (
                actions.map((action: any) => <ActionSection key={action.id} action={action} formikBag={props} />)
              )}
              {actionsPutError && (
                <InlineNotification
                  style={{ marginBottom: "0.5rem" }}
                  kind="error"
                  title={"Crikey! That didn't work."}
                  subtitle={"Try again"}
                />
              )}
            </ModalBody>
            {type === ModalType.Single ? (
              <ModalFooter className={styles.threeOptionFooter}>
                <Button className={styles.threeOptionFooterCancel} kind="secondary" type="button" onClick={closeModal}>
                  Cancel
                </Button>
                <Button
                  disabled={!isValid || actionsIsLoading || isAlreadyApproved}
                  kind="danger"
                  onClick={handleActions({
                    approved: false,
                    notificationTitle: "Success!",
                    notificationSubtitle: "Request to reject action submitted",
                    setLoading: setRejectLoading,
                    values,
                  })}
                >
                  {!rejectLoading ? "Reject" : "Rejecting..."}
                </Button>
                <Button
                  disabled={!isValid || actionsIsLoading || isAlreadyApproved}
                  onClick={handleActions({
                    approved: true,
                    notificationTitle: "Success!",
                    notificationSubtitle: "Request to approve action submitted",
                    setLoading: setApproveLoading,
                    values,
                  })}
                >
                  {!approveLoading ? "Approve" : "Approving..."}
                </Button>
              </ModalFooter>
            ) : type === ModalType.Approve ? (
              <ModalFooter>
                <Button kind="secondary" type="button" onClick={closeModal}>
                  Cancel
                </Button>
                <Button
                  disabled={!isValid || actionsIsLoading}
                  onClick={handleActions({
                    approved: true,
                    notificationTitle: "Success!",
                    notificationSubtitle: `Request to approve ${actions.length} actions submitted.`,
                    values,
                  })}
                >
                  {!actionsIsLoading ? "Approve" : "Approving..."}
                </Button>
              </ModalFooter>
            ) : type === ModalType.Reject ? (
              <ModalFooter>
                <Button kind="secondary" type="button" onClick={closeModal}>
                  Cancel
                </Button>
                <Button
                  disabled={!isValid || actionsIsLoading}
                  kind="danger"
                  onClick={handleActions({
                    approved: false,
                    notificationTitle: "Success!",
                    notificationSubtitle: `Request to reject ${actions.length} actions submitted.`,
                    values,
                  })}
                >
                  {!actionsIsLoading ? "Reject" : "Rejecting..."}
                </Button>
              </ModalFooter>
            ) : null}
          </ModalForm>
        );
      }}
    </Formik>
  );
}

interface ActionSectionProps {
  action: Action;
  formikBag: any;
}

function ActionSection({ formikBag, action }: ActionSectionProps) {
  const { id, workspaceName, workflowName } = action;
  const { values, touched, errors, handleChange, handleBlur } = formikBag;

  const DataSection = ({ className, label, value }: any) => (
    <dl className={className}>
      <dt className={styles.dataLabel}>{label}</dt>
      <dd className={styles.dataValue}>{value ?? "---"}</dd>
    </dl>
  );

  return (
    <section className={styles.action}>
      <DataSection className={styles.data} label="Workspace" value={workspaceName} />
      <DataSection className={styles.data} label="Workflow" value={workflowName} />
      <div className={styles.comment}>
        <TextArea
          enableCounter
          id={`${id}.comments`}
          className={styles.commentArea}
          labelText="Comments (optional)"
          placeholder="Add some reasoning for your decision"
          value={values[id]?.comments}
          onChange={handleChange}
          onBlur={handleBlur}
          invalid={Boolean(errors[id]?.comments && touched[id]?.comments)}
          invalidText={errors[id]?.comments}
          maxCount={200}
        />
      </div>
    </section>
  );
}

interface SingleActionSectionProps {
  action: Action;
  formikBag: any;
  isAlreadyApproved: boolean;
  user: any;
}

function SingleActionSection({ formikBag, action, isAlreadyApproved, user }: SingleActionSectionProps) {
  const {
    numberOfApprovals = 0,
    approvalsRequired = 0,
    creationDate,
    id,
    status,
    workflowName,
    workspaceName,
    actioners = [],
  } = action;
  const { values, touched, errors, handleChange, handleBlur } = formikBag;

  const DataSection = ({ className, label, value }: any) => (
    <dl className={className}>
      <dt className={styles.dataLabel}>{label}</dt>
      <dd className={styles.dataValue}>{value ?? "---"}</dd>
    </dl>
  );

  return (
    <section className={styles.action}>
      <DataSection className={styles.data} label="Workspace" value={workspaceName} />
      <DataSection className={styles.data} label="Workflow" value={workflowName} />
      <span className={styles.creationDate}>{`Submitted ${dateHelper.humanizedSimpleTimeAgo(creationDate)}`}</span>
      {!isAlreadyApproved ? (
        <>
          <div className={styles.approvals}>
            <span className={styles.singleLabel}>Approvals</span>
            <span className={styles.approvalsRatio}>{`${numberOfApprovals}/${approvalsRequired} approvals`}</span>
            <span className={styles.singleHelperText}>
              Number of required approvals that have been received in order for this component to proceed.
            </span>
          </div>
          <div className={styles.comment}>
            <TextArea
              enableCounter
              id={`${id}.comments`}
              className={styles.commentArea}
              labelText="Comments (optional)"
              placeholder="Add some reasoning for your decision"
              value={values[id]?.comments}
              onChange={handleChange}
              onBlur={handleBlur}
              invalid={Boolean(errors[id]?.comments && touched[id]?.comments)}
              invalidText={errors[id]?.comments}
              maxCount={200}
            />
          </div>
        </>
      ) : (
        <div className={styles.yourInput}>
          <span className={styles.singleLabel}>Your input</span>
          <span className={styles.singleHelperText}>
            {status !== ApprovalStatus.Submitted
              ? `This action is already ${status}`
              : "You already submitted your response for this action."}
          </span>
        </div>
      )}
      {Array.isArray(actioners) && actioners.length && (
        <div className={styles.approvers}>
          <span className={styles.singleLabel}>Approvers who submitted</span>
          <StructuredListWrapper>
            <StructuredListHead>
              <StructuredListRow head>
                <StructuredListCell head>Name</StructuredListCell>
                <StructuredListCell head>Email</StructuredListCell>
                <StructuredListCell className={styles.approverCommentHead} head>
                  Comment
                </StructuredListCell>
                <StructuredListCell head>Time Submitted</StructuredListCell>
                <StructuredListCell head>Input</StructuredListCell>
              </StructuredListRow>
            </StructuredListHead>
            <StructuredListBody>
              {actioners.map((approver, index) => (
                <StructuredListRow key={`${approver.approverId}-${index}`}>
                  <StructuredListCell noWrap>{`${approver.approverName}${` ${
                    approver.approverId === user.id ? "(you!)" : ""
                  }`}`}</StructuredListCell>
                  <StructuredListCell noWrap>{approver.approverEmail}</StructuredListCell>
                  <StructuredListCell>
                    <p className={styles.approverComment}>{approver.comments ?? "---"}</p>
                  </StructuredListCell>
                  <StructuredListCell noWrap>
                    {approver.date ? dateHelper.humanizedSimpleTimeAgo(approver.date) : "---"}
                  </StructuredListCell>
                  <StructuredListCell className={styles.approverActioned} noWrap>
                    {approver.approved ? ApprovalStatus.Approved : ApprovalStatus.Rejected}
                  </StructuredListCell>
                </StructuredListRow>
              ))}
            </StructuredListBody>
          </StructuredListWrapper>
        </div>
      )}
    </section>
  );
}

export default ApproveRejectActions;
