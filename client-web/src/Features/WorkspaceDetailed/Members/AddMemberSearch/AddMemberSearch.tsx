import React, { useState, useEffect } from "react";
import { ComposedModal } from "@boomerang-io/carbon-addons-boomerang-react";
import { Add } from "@carbon/react/icons";
import { Button, InlineNotification, ModalBody, ModalFooter, Search } from "@carbon/react";
import { Error, Loading, ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import { resourceRoute } from "Config/resourceRoutes";
import { Member, MemberRole, PaginatedUserResponse } from "Types";
import type { ResUsersResult } from "../../../../../app/routes/resUsers";
import MemberBar from "./MemberBar";
import styles from "./AddMemberSearch.module.scss";
import { Locked } from "Utils/navigationIcons";

interface AddMemberSearchProps {
  memberList: Array<Member>;
  handleSubmit: Function;
  isSubmitting: boolean;
  error: any;
}

function AddMemberSearch({ memberList, handleSubmit, isSubmitting, error }: AddMemberSearchProps) {
  return (
    <ComposedModal
      modalTrigger={({ openModal }: { openModal: Function }) => (
        <Button
          renderIcon={Locked}
          kind="ghost"
          onClick={() => {
            openModal();
          }}
          iconDescription="Add members"
          size="md"
          data-testid="add-members-button"
        >
          Add Existing Members
        </Button>
      )}
      composedModalProps={{ containerClassName: styles.modal }}
      modalHeaderProps={{
        title: "Add existing members",
        subtitle: `Search for existing members to add to this workspace`,
      }}
    >
      {({ closeModal }: { closeModal: Function }) => (
        <AddMemberContent
          closeModal={closeModal}
          memberList={memberList}
          handleSubmit={handleSubmit}
          isSubmitting={isSubmitting}
          errorSubmit={error}
        />
      )}
    </ComposedModal>
  );
}

interface AddMemberContentProps {
  closeModal: Function;
  memberList: Array<Member>;
  handleSubmit: Function;
  isSubmitting: boolean;
  errorSubmit: any;
}

function AddMemberContent({ closeModal, memberList, handleSubmit, isSubmitting, errorSubmit }: AddMemberContentProps) {
  const [userList, setUserList] = useState<Member[]>([]);
  const [selectedUsers, setSelectedUsers] = useState<Member[]>([]);
  const [usersListOpen, setUsersListOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  // One on-mount read of the user list through the /res/users BFF resource route (see
  // app/routes/resUsers.tsx) - this content only mounts when the modal opens, so the read stays
  // on-demand exactly as the old react-query call was. The search box filters the fetched page
  // client-side; it never re-queries the server (unchanged behaviour).
  const [userQuery, setUserQuery] = useState<
    { status: "loading" } | { status: "error" } | { status: "ok"; data: PaginatedUserResponse }
  >({ status: "loading" });
  useEffect(() => {
    let cancelled = false;
    fetch(resourceRoute.users(), { credentials: "same-origin" })
      .then((response) => (response.ok ? (response.json() as Promise<ResUsersResult>) : Promise.reject()))
      .then((result) => {
        if (!cancelled) {
          setUserQuery(result.ok ? { status: "ok", data: result.users } : { status: "error" });
        }
      })
      .catch(() => {
        if (!cancelled) {
          setUserQuery({ status: "error" });
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const searchRef = React.useRef<HTMLDivElement | null>();

  const handleClickOutsideSearch = (e: MouseEvent) => {
    if (searchRef.current && !searchRef.current.contains(e.target as Node)) {
      setUsersListOpen(false);
    }
  };

  useEffect(() => {
    document.addEventListener("mousedown", handleClickOutsideSearch);
    return () => {
      document.removeEventListener("mousedown", handleClickOutsideSearch);
    };
  });

  const handleSearchChange = (e: { target: HTMLInputElement; type: "change" }) => {
    const searchQuery = e.target?.value;

    if (searchQuery) {
      setUserList(userQuery.status === "ok" ? userQuery.data.content || [] : []);
      setSearchQuery(searchQuery);
      setUsersListOpen(true);
    } else {
      setSearchQuery("");
      setUsersListOpen(false);
    }
  };

  const addUser = (id: string) => {
    const user = userList.find((user: Member) => user.id === id);
    if (user) {
      setSelectedUsers(selectedUsers.concat(user));
    }
    setUsersListOpen(false);
  };

  const removeUser = (id: string) => {
    const users = [...selectedUsers];
    const userIndex = users.findIndex((user: Member) => user.id === id);
    users.splice(userIndex, 1);
    setSelectedUsers(users);
  };

  // See AddMember.tsx: `closeModal` is handed to the route-action fetcher in ../Members and
  // invoked once the add succeeds, rather than being called eagerly here.
  const handleInternalSubmit = (e: React.SyntheticEvent<HTMLButtonElement>) => {
    e.preventDefault();
    const addMemberRequestData: Array<Member> = selectedUsers.map((user) => ({
      id: user.id,
      email: user.email,
      role: MemberRole.Editor,
    }));

    handleSubmit(addMemberRequestData, closeModal);
  };

  if (userQuery.status === "error") {
    return <Error />;
  }

  let usersListRecords: Member[] = [];
  if (userList && userList?.length > 0) {
    usersListRecords = userList.filter((record: Member) => {
      return (
        !memberList?.some((member) => member.id === record.id) &&
        !selectedUsers?.some((selectedUser) => selectedUser.id === record.id)
      );
    });
  }

  const UsersInfo = () => {
    if (usersListRecords.length > 0) {
      return (
        <ul className={styles.selectUsers}>
          {usersListRecords.map((user) => (
            <MemberBar
              addUser={addUser}
              id={user.id ?? ""}
              name={user.name ?? ""}
              email={user.email ?? ""}
              removeUser={null}
            />
          ))}
        </ul>
      );
    }

    return null;
  };

  return (
    <ModalForm onSubmit={handleInternalSubmit}>
      <ModalBody>
        {isSubmitting && <Loading />}
        <div ref={searchRef as React.RefObject<HTMLDivElement>} className={styles.search}>
          <Search
            autoComplete="off"
            id="add-members-modal-search"
            labelText=""
            placeholder="Search for a user"
            onChange={handleSearchChange}
            value={searchQuery}
            onClick={() => setUsersListOpen(!usersListOpen)}
          />
          {userList && usersListOpen && <UsersInfo />}
        </div>
        <p className={styles.sectionTitle}>{`${selectedUsers.length} users selected`}</p>
        {selectedUsers.length > 0 && (
          <ul className={styles.selectedUsers}>
            {selectedUsers.map((user) => (
              <MemberBar
                addUser={null}
                id={user.id ?? ""}
                email={user.email ?? ""}
                name={user.name ?? ""}
                removeUser={removeUser}
              />
            ))}
          </ul>
        )}
        {errorSubmit && (
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
        <Button disabled={selectedUsers.length === 0 || isSubmitting} type="submit">
          {isSubmitting ? "Adding..." : errorSubmit ? "Try Again" : "Add to workspace"}
        </Button>
      </ModalFooter>
    </ModalForm>
  );
}

export default AddMemberSearch;
