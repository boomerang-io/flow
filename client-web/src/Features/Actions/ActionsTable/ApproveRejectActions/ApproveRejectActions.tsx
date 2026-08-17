import React from "react";
import { useQueryClient, useMutation } from "react-query";
import { Formik } from "formik";
import * as Yup from "yup";
import { useAppContext, useTeamContext } from "Hooks";
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
import { resolver } from "Config/servicesConfig";
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
  queryToRefetch: string;
  type: "single" | "approve" | "reject";
};

function ApproveRejectActions({
  actions,
  isAlreadyApproved = false,
  handleCloseModal,
  modalTrigger,
  queryToRefetch,
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
          queryToRefetch={queryToRefetch}
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
  queryToRefetch: string;
  type: string;
};

function Form({
  actions,
  closeModal,
  isAlreadyApproved,
  onSuccessfulApprovalRejection,
  queryToRefetch,
  type,
}: FormProps) {
  const { user } = useAppContext();
  const { team } = useTeamContext();
  const queryClient = useQueryClient();
  const [approveLoading, setApproveLoading] = React.useState(false);
  const [rejectLoading, setRejectLoading] = React.useState(false);

  /** Update actions */
  const {
    mutateAsync: actionsMutation,
    isLoading: actionsIsLoading,
    isError: actionsPutError,
  } = useMutation(resolver.putAction);

  const handleActions =
    ({ approved, notificationSubtitle, notificationTitle, setLoading, values }: any) =>
    async () => {
      typeof setLoading === "function" && setLoading(true);
      let request: any = [];

      Object.keys(values).forEach((actionId) => {
        request.push({ ...values[actionId], id: actionId, approved });
      });

      try {
        await actionsMutation({ team: team?.name, body: request });
        typeof setLoading === "function" && setLoading(false);
        onSuccessfulApprovalRejection();
        queryClient.invalidateQueries(queryToRefetch);
        notify(<ToastNotification kind="success" subtitle={notificationSubtitle} title={notificationTitle} />);
        closeModal();
      } catch (err) {
        typeof setLoading === "function" && setLoading(false);
        // noop
      }
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
  const { id, teamName, workflowName } = action;
  const { values, touched, errors, handleChange, handleBlur } = formikBag;

  const DataSection = ({ className, label, value }: any) => (
    <dl className={className}>
      <dt className={styles.dataLabel}>{label}</dt>
      <dd className={styles.dataValue}>{value ?? "---"}</dd>
    </dl>
  );

  return (
    <section className={styles.action}>
      <DataSection className={styles.data} label="Team" value={teamName} />
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
    teamName,
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
      <DataSection className={styles.data} label="Team" value={teamName} />
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
                    {approver.actionDate ? dateHelper.humanizedSimpleTimeAgo(approver.actionDate) : "---"}
                  </StructuredListCell>
                  <StructuredListCell className={styles.approverActioned} noWrap>
                    {approver.actioned ? ApprovalStatus.Approved : ApprovalStatus.Rejected}
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
