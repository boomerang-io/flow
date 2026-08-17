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
import { useQueryClient, useMutation } from "react-query";
import { useTeamContext } from "Hooks";
import { Formik } from "formik";
import * as Yup from "yup";
import styles from "./taskApprovalModal.module.scss";
import { serviceUrl, resolver } from "Config/servicesConfig";

const GateStatus = {
  Approved: "APPROVED",
  Rejected: "REJECTED",
} as const;

type Props = {
  actionId?: string;
  closeModal: () => void;
  workflowRunId: string;
};

function TaskApprovalModal({ actionId, closeModal, workflowRunId }: Props) {
  const queryClient = useQueryClient();
  const { team } = useTeamContext();

  const {
    mutateAsync: approvalMutator,
    isLoading: approvalsIsLoading,
    error: approvalsError,
  } = useMutation(resolver.putAction, {
    onSuccess: () => {
      queryClient.invalidateQueries(serviceUrl.team.workflowrun.getWorkflowRun({ team: team?.name, id: workflowRunId }));
    },
  });

  const handleSubmit = async (values: { status: string; comment: string }) => {
    const body = [
      {
        id: actionId,
        approved: values.status === GateStatus.Approved,
        comments: values.comment,
      },
    ];
    try {
      await approvalMutator({ team: team?.name, body });
      notify(
        <ToastNotification
          kind="success"
          title="Manual Approval"
          subtitle={
            body[0].approved ? "Successfully submitted approval request" : "Successfully submitted denial request"
          }
        />,
      );
      closeModal();
    } catch (err) {
      // noop
    }
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
