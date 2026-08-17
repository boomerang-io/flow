import React, { useState, useEffect } from "react";
import useAxios from "axios-hooks";
import queryString from "query-string";
import { useMutation, useQueryClient } from "react-query";
import { Button, InlineNotification, ModalBody, ModalFooter, Search } from "@carbon/react";
import { Error, Loading, ModalForm, notify, ToastNotification } from "@boomerang-io/carbon-addons-boomerang-react";
import { resolver, serviceUrl } from "Config/servicesConfig";
import { FlowUser } from "Types";
import MemberBar from "./MemberBar";
import styles from "./AddMemberContent.module.scss";

interface AddMemberContentProps {
  closeModal: Function;
  memberList: FlowUser[];
  memberIdList: string[];
  teamName: string;
}
const AddMemberContent: React.FC<AddMemberContentProps> = ({ closeModal, memberList, memberIdList, teamName }) => {
  const [{ data: usersList, error }, fetchUsersList] = useAxios({ method: "get" }, { manual: true });
  const [selectedUsers, setSelectedUsers] = useState<FlowUser[]>([]);
  const [usersListOpen, setUsersListOpen] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const queryClient = useQueryClient();

  const {
    mutateAsync: addMemberMutator,
    isLoading: addMemberisLoading,
    error: addMemberError,
  } = useMutation(resolver.patchTeam, {
    onSuccess: () => queryClient.invalidateQueries(serviceUrl.resourceTeam({ team: teamName })),
  });

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

  const handleSearchChange = (e: React.SyntheticEvent<HTMLButtonElement>) => {
    const searchQuery = e.currentTarget?.value;

    if (searchQuery) {
      const queryStr = queryString.stringify({ page: 0, size: 20, query: searchQuery });

      fetchUsersList({ url: serviceUrl.getUsers({ query: queryStr }) });
      setSearchQuery(searchQuery);
      setUsersListOpen(true);
    } else {
      setSearchQuery("");
      setUsersListOpen(false);
    }
  };

  const addUser = (id: string) => {
    const user = usersList.records.find((user: FlowUser) => user.id === id);
    setSelectedUsers(selectedUsers.concat(user));
    setUsersListOpen(false);
  };

  const removeUser = (id: string) => {
    const users = [...selectedUsers];
    const userIndex = users.findIndex((user: FlowUser) => user.id === id);
    users.splice(userIndex, 1);
    setSelectedUsers(users);
  };

  const handleSubmit = async (e: React.SyntheticEvent<HTMLButtonElement>) => {
    e.preventDefault();

    const addUserRequestData = memberIdList.concat(selectedUsers.map((user) => user.id));

    try {
      await addMemberMutator({ team: teamName, body: addUserRequestData });
      selectedUsers.forEach((user) => {
        return notify(
          <ToastNotification
            title="Add User"
            subtitle={`Request to add ${user.name} to ${teamName} submitted`}
            kind="success"
          />
        );
      });
      closeModal();
    } catch (error) {
      // noop
    }
  };

  if (error) {
    return <Error />;
  }

  let usersListRecords: FlowUser[] = [];
  if (usersList && usersList.records?.length > 0) {
    usersListRecords = usersList.records.filter((record: FlowUser) => {
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
            <MemberBar addUser={addUser} id={user.id} name={user.name} email={user.email} removeUser={null} />
          ))}
        </ul>
      );
    }

    return null;
  };

  return (
    <ModalForm onSubmit={handleSubmit}>
      <ModalBody>
        {addMemberisLoading && <Loading />}
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
          {usersList && usersListOpen && <UsersInfo />}
        </div>
        <p className={styles.sectionTitle}>{`${selectedUsers.length} users selected`}</p>
        {selectedUsers.length > 0 && (
          <ul className={styles.selectedUsers}>
            {selectedUsers.map((user) => (
              <MemberBar addUser={null} id={user.id} email={user.email} name={user.name} removeUser={removeUser} />
            ))}
          </ul>
        )}
        {addMemberError && (
          <InlineNotification
            lowContrast
            kind="error"
            title="Crikey, that didn’t work!"
            subtitle="Give it another go or try again later."
          />
        )}
      </ModalBody>
      <ModalFooter>
        <Button onClick={closeModal} kind="secondary" type="button">
          Cancel
        </Button>
        <Button disabled={selectedUsers.length === 0 || addMemberisLoading} type="submit">
          {addMemberisLoading ? "Adding..." : addMemberError ? "Try Again" : "Add to team"}
        </Button>
      </ModalFooter>
    </ModalForm>
  );
};

export default AddMemberContent;
