import React from "react";
import { useFetcher } from "react-router-dom";
import { Formik, FieldArray } from "formik";
import * as Yup from "yup";
import sortBy from "lodash/sortBy";
import { matchSorter } from "match-sorter";
import {
  Loading,
  ModalFlowForm,
  notify,
  TextInput,
  ToastNotification,
} from "@boomerang-io/carbon-addons-boomerang-react";
import { Button, Checkbox, InlineNotification, ModalBody, ModalFooter, Search } from "@carbon/react";
import { isAccessibleKeyboardEvent } from "@boomerang-io/utils";
import { AddAlt, SubtractAlt } from "@carbon/react/icons";
import { FlowWorkspace, Approver, ApproverGroup } from "Types";
import { ApproverGroupIntent } from "../../ApproverGroups";
import type { ApproverGroupsActionResult } from "../../ApproverGroups";
import { isActionError } from "Utils/actionResult";
import styles from "./createEditGroupModalContent.module.scss";

type RenderMembersListProps = {
  members: Approver[];
  approvers: Approver[];
  setFieldValue: (field: string, args: any) => void;
};

function RenderMembersList({ members, approvers, setFieldValue }: RenderMembersListProps) {
  const [searchQuery, setSearchQuery] = React.useState("");
  const filteredMembers = Boolean(searchQuery)
    ? matchSorter(members, searchQuery, {
        keys: ["name", "email"],
      })
    : members;
  const filteredMembersIds = filteredMembers.map((member: Approver) => member.id);
  const currentApproversIds = approvers.map((approver: Approver) => approver.id);
  const allMembersChecked =
    filteredMembers.length !== 0 &&
    filteredMembers.length ===
      filteredMembers.filter((member: Approver) => currentApproversIds.includes(member.id)).length;

  const handleSelectAllMembers = () => {
    if (!allMembersChecked) {
      setFieldValue("approvers", [
        ...approvers,
        ...filteredMembers.filter((member: Approver) => !currentApproversIds.includes(member.id)),
      ]);
    } else
      setFieldValue(
        "approvers",
        approvers.filter((approver: Approver) => !filteredMembersIds.includes(approver.id)),
      );
  };

  const handleSelectMember = ({ member, arrayHelpers }: { member: Approver; arrayHelpers: any }) => {
    const memberIndex = approvers.findIndex((approver) => approver.id === member.id);
    if (memberIndex >= 0) arrayHelpers.remove(memberIndex);
    else arrayHelpers.push({ ...member });
  };

  return (
    <div>
      <div className={styles.divider} />
      <Search
        labelText="member search"
        id="member-search"
        placeholder="Search for Workspace Members by name or email"
        onChange={(e: { target: HTMLInputElement; type: "change" }) => setSearchQuery(e.target.value)}
      />
      <p className={styles.selectedUsers}>{`${approvers.length} users selected`}</p>
      <ul>
        <Checkbox
          id="workspaceMembers"
          labelText="Workspace members"
          checked={allMembersChecked}
          className={styles.selectAllMembers}
          onChange={handleSelectAllMembers}
        />
        <FieldArray
          name="approvers"
          render={(arrayHelpers) =>
            filteredMembers.map((member: Approver, index: number) => (
              <li className={styles.userListCheckItem}>
                <Checkbox
                  id={member.id ?? ""}
                  labelText={member.name ?? ""}
                  checked={currentApproversIds.includes(member.id)}
                  className={styles.userName}
                  onChange={() => handleSelectMember({ member, arrayHelpers })}
                />
                <p className={styles.userEmail}>{member.email}</p>
              </li>
            ))
          }
        />
      </ul>
    </div>
  );
}

type RenderEditMembersInGroupProps = {
  members: Approver[];
  title: string;
  isRemove?: boolean;
};

function RenderEditMembersInGroup({ members, title, isRemove = false }: RenderEditMembersInGroupProps) {
  const determineMemberIndex = (userId: string) => members.findIndex((approver) => approver.id === userId);
  return (
    <div className={styles.membersContainer}>
      <p className={styles.listTitle}>{`${title} (${members.length})`}</p>
      <ul className={styles.userList}>
        {Boolean(members.length) ? (
          <FieldArray
            name="approvers"
            render={(arrayHelpers) =>
              sortBy(members, ["userName"]).map((member) => (
                <li className={styles.userListItem}>
                  <div className={styles.memberInfo}>
                    <p className={styles.userName}>{member.name}</p>
                    <p className={styles.userEmail}>{member.email}</p>
                  </div>
                  {isRemove ? (
                    <div
                      role="button"
                      onClick={() => arrayHelpers.remove(determineMemberIndex(member.id ?? ""))}
                      onKeyDown={(e: any) =>
                        isAccessibleKeyboardEvent(e) && arrayHelpers.remove(determineMemberIndex(member.id ?? ""))
                      }
                      tabIndex={0}
                    >
                      <SubtractAlt className={styles.actionIcon} />
                    </div>
                  ) : (
                    <div
                      role="button"
                      onClick={() => arrayHelpers.push(member)}
                      onKeyDown={(e: any) => isAccessibleKeyboardEvent(e) && arrayHelpers.push(member)}
                      tabIndex={0}
                    >
                      <AddAlt className={styles.actionIcon} />
                    </div>
                  )}
                </li>
              ))
            }
          />
        ) : (
          <div className={styles.noMembers}>
            <p className={styles.noMembersTitle}>{isRemove ? "No group members" : "No workspace members"}</p>
            <p className={styles.noMembersMessage}>
              {isRemove ? "Add members from the list below in order to save this group" : ""}
            </p>
          </div>
        )}
      </ul>
    </div>
  );
}

type Props = {
  closeModal: () => void;
  isEdit?: boolean;
  approverGroup?: ApproverGroup;
  approverGroups: string[];
  workspace?: FlowWorkspace | null;
};

function CreateEditGroupModalContent({
  closeModal,
  isEdit = false,
  approverGroup,
  approverGroups,
  workspace,
}: Props) {
  const workspaceMembers = workspace?.members;
  // Posts to the Approver Groups route's `action` (see ../../ApproverGroups) - no explicit action
  // path needed, a fetcher resolves to the nearest matched route. Its completion revalidates the
  // parent layout loader that supplies the group list, so no manual invalidation here.
  const fetcher = useFetcher<ApproverGroupsActionResult>();
  const isSubmitting = fetcher.state !== "idle";
  const failed = Boolean(fetcher.data && isActionError(fetcher.data) && fetcher.data.intent === ApproverGroupIntent.Save);

  const { title, message: subtitle } =
    failed && fetcher.data && isActionError(fetcher.data)
      ? (fetcher.data.error ?? { title: "Something's Wrong", message: undefined })
      : { title: "", message: undefined };

  React.useEffect(() => {
    if (
      fetcher.state !== "idle" ||
      !fetcher.data ||
      fetcher.data.intent !== ApproverGroupIntent.Save ||
      isActionError(fetcher.data)
    ) {
      return;
    }
    notify(
      <ToastNotification
        kind="success"
        title={fetcher.data.isEdit ? "Approver Group Updated" : "Approver Group Created"}
        subtitle={`Request to ${fetcher.data.isEdit ? "update" : "create"} ${fetcher.data.name} succeeded`}
        data-testid="create-update-approver-group-notification"
      />,
    );
    closeModal();
    // Failures leave the modal open and surface inline below, matching the previous
    // mutation.isError behaviour.
  }, [fetcher.state, fetcher.data, closeModal]);

  const handleSubmit = (values: any) => {
    fetcher.submit(
      {
        intent: ApproverGroupIntent.Save,
        isEdit: String(Boolean(isEdit)),
        groupId: isEdit ? (approverGroup?.id ?? "") : "",
        name: values.groupName,
        approvers: JSON.stringify(values.approvers.map((approver: any) => approver.id)),
      },
      { method: "post" },
    );
  };

  const loadingText = isEdit ? "Saving..." : "Creating...";
  const normalText = isEdit ? "Save" : "Create group";
  const buttonText = isSubmitting ? loadingText : normalText;

  return (
    <Formik
      initialValues={{
        groupName: approverGroup && approverGroup.name ? approverGroup.name : "",
        approvers: approverGroup && approverGroup.approvers ? sortBy(approverGroup.approvers, ["name"]) : [],
      }}
      onSubmit={handleSubmit}
      validationSchema={Yup.object().shape({
        groupName: Yup.string()
          .lowercase()
          .required("Enter a group name")
          .notOneOf(approverGroups, "Group name must be unique within the Workspace"),
        approvers: Yup.array().min(1, "Groups should have at least 1 member"),
      })}
    >
      {(props) => {
        const { dirty, values, touched, errors, isValid, handleChange, handleBlur, handleSubmit, setFieldValue } =
          props;
        const currentGroupMembersIds = values.approvers.map((approver) => approver.id);
        const sortedWorkspaceMembers = sortBy(workspaceMembers, ["name"]);
        const eligibleMembers = workspaceMembers
          ? sortedWorkspaceMembers.filter((workspaceMember) => !currentGroupMembersIds.includes(workspaceMember.id))
          : [];

        return (
          <ModalFlowForm onSubmit={handleSubmit}>
            {isSubmitting && <Loading />}
            <ModalBody className={styles.formBody}>
              <div className={styles.input}>
                <TextInput
                  id="groupName"
                  labelText="Group name"
                  placeholder="i.e. Senior level approvers"
                  name="groupName"
                  helperText="Must be unique within the Workspace"
                  value={values.groupName}
                  onBlur={handleBlur}
                  onChange={handleChange}
                  invalid={Boolean(errors.groupName && touched.groupName)}
                  invalidText={errors.groupName}
                />
              </div>
              {isEdit ? (
                <>
                  <RenderEditMembersInGroup title="Group members" members={values.approvers} isRemove />
                  <RenderEditMembersInGroup title="Workspace members not in this group" members={eligibleMembers} />
                </>
              ) : (
                <RenderMembersList
                  members={sortedWorkspaceMembers ?? []}
                  approvers={values.approvers}
                  setFieldValue={setFieldValue}
                />
              )}
              {failed && <InlineNotification lowContrast kind="error" subtitle={subtitle} title={title} />}
            </ModalBody>
            <ModalFooter>
              <Button kind="secondary" type="button" onClick={closeModal}>
                Cancel
              </Button>
              <Button type="submit" disabled={!isValid || isSubmitting || !dirty}>
                {failed ? "Try Again" : buttonText}
              </Button>
            </ModalFooter>
          </ModalFlowForm>
        );
      }}
    </Formik>
  );
}

export default CreateEditGroupModalContent;
