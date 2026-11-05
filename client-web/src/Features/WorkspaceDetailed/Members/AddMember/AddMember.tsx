import React, { useState } from "react";
import * as Yup from "yup";
import { Button, ModalBody, ModalFooter, InlineNotification, Select, SelectItem, TextInput } from "@carbon/react";
import { ComposedModal, ModalForm, Loading } from "@boomerang-io/carbon-addons-boomerang-react";
import { Formik } from "formik";
import capitalize from "lodash/capitalize";
import { Add } from "@carbon/react/icons";
import MemberCard from "Components/MemberCard";
import { Member, MemberRole } from "Types";
import styles from "./AddMember.module.scss";

interface AddMemberProps {
  memberList: Array<Member>;
  handleSubmit: Function;
  isSubmitting: boolean;
  error: any;
}

/** A member staged for submission - unlike `Member`, email/role are always present once added to the list. */
type NewMember = { email: string; role: MemberRole };

function AddMember({ memberList, handleSubmit, isSubmitting, error }: AddMemberProps) {
  const [members, setMembers] = useState<Array<NewMember>>([]);

  const handleAdd = async (values: NewMember) => {
    const newMembers = [...members, values];
    setMembers(newMembers);
  };

  const handleRemove = (email: string) => {
    const newMembers = members.filter((member) => member.email !== email);
    setMembers(newMembers);
  };

  // The submit is a route-action fetcher now (see ../Members), which settles asynchronously - so
  // `closeModal` is handed over rather than called here, and Members invokes it once the add
  // actually succeeds. Previously the mutation swallowed its own errors, so this closed the modal
  // either way; a failed add now keeps it open and shows the inline error below.
  const handleInternalSubmit = (e: React.SyntheticEvent<HTMLButtonElement>, closeModal: Function) => {
    e.preventDefault();
    const addMemberRequestData: Array<NewMember> = members.map((user) => ({
      email: user.email,
      role: user.role,
    }));

    handleSubmit(addMemberRequestData, closeModal);
  };

  const memberEmailList = memberList?.map((member) => member.email);

  return (
    <ComposedModal
      modalTrigger={({ openModal }: { openModal: Function }) => (
        <Button
          renderIcon={Add}
          onClick={() => {
            openModal();
          }}
          iconDescription="Add members"
          size="md"
          data-testid="add-members-button"
        >
          Add members
        </Button>
      )}
      composedModalProps={{ containerClassName: styles.modal }}
      modalHeaderProps={{
        title: "Add members",
        subtitle: `Add members to this workspace`,
      }}
    >
      {({ closeModal }: { closeModal: Function }) => (
        <ModalForm onSubmit={(e: React.SyntheticEvent<HTMLButtonElement>) => handleInternalSubmit(e, closeModal)}>
          <ModalBody>
            {isSubmitting && <Loading />}
            <div className={styles.addMemberContainer}>
              <Formik
                initialValues={{ email: "", role: MemberRole.Editor }}
                onSubmit={(values: any) => handleAdd(values)}
                validateOnMount
                validationSchema={Yup.object().shape({
                  email: Yup.string()
                    .email("Invalid email address")
                    .notOneOf(memberEmailList)
                    .required("Email is required"),
                  role: Yup.string().oneOf(Object.values(MemberRole)).required("Role is required"),
                })}
              >
                {({ errors, isValid, touched, handleBlur, handleChange, setFieldValue, values, handleSubmit }) => {
                  const emailIsInvalid = Boolean(errors.email && touched.email);
                  return (
                    <>
                      <TextInput
                        id="email"
                        invalid={emailIsInvalid}
                        invalidText={typeof errors.email === "string" ? errors.email : undefined}
                        labelText="Email"
                        onBlur={handleBlur}
                        onChange={handleChange}
                        placeholder="mary.jackson@test.com"
                        value={values.email}
                      />
                      {/* A native select: Carbon's Dropdown smooth-scrolls each option the pointer
                          crosses into view, which inside this scrolling modal body moves the list
                          under the pointer and never settles. */}
                      <Select
                        className={styles.roleSelect}
                        id="role"
                        labelText="Role"
                        onChange={(event: React.ChangeEvent<HTMLSelectElement>) => setFieldValue("role", event.target.value)}
                        value={values.role}
                      >
                        {Object.values(MemberRole).map((role) => (
                          <SelectItem key={role} text={capitalize(role)} value={role} />
                        ))}
                      </Select>
                      <Button
                        kind="tertiary"
                        disabled={!isValid}
                        onClick={() => handleSubmit()}
                        renderIcon={Add}
                        size="md"
                        style={{
                          alignSelf: emailIsInvalid ? "center" : "flex-end",
                          marginTop: emailIsInvalid ? "0.5rem" : 0,
                        }}
                      >
                        Add
                      </Button>
                    </>
                  );
                }}
              </Formik>
            </div>
            <div className={styles.divider} />
            <div className={styles.tileContainer}>
              {members.map((member) => (
                <MemberCard
                  email={member.email}
                  role={member.role}
                  handleRemove={() => handleRemove(member.email)}
                  isRemoving={false}
                />
              ))}
            </div>
            {error && (
              <InlineNotification
                lowContrast
                kind="error"
                title="Crikey, that didn’t work!"
                subtitle="Give it another go or try again later."
              />
            )}
          </ModalBody>
          <ModalFooter>
            <Button onClick={() => closeModal()} kind="secondary" type="button">
              Cancel
            </Button>
            <Button disabled={members.length === 0 || isSubmitting} type="submit">
              {isSubmitting ? "Adding..." : error ? "Try Again" : "Add member"}
            </Button>
          </ModalFooter>
        </ModalForm>
      )}
    </ComposedModal>
  );
}

export default AddMember;
