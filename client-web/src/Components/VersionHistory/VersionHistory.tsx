import React from "react";
import {
  ModalBody,
  StructuredListWrapper,
  StructuredListHead,
  StructuredListBody,
  StructuredListRow,
  StructuredListCell,
} from "@carbon/react";
import { ComposedModal, TooltipHover } from "@boomerang-io/carbon-addons-boomerang-react";
import moment from "moment";
import { Catalog } from "@carbon/react/icons";
import { ChangeLog, ModalTriggerProps } from "Types";
import styles from "./versionHistory.module.scss";
import { DATETIME_LOCAL_INPUT_FORMAT } from "Utils/dateHelper";

interface VersionHistoryProps {
  changelog: ChangeLog;
}

const VersionHistory: React.FC<VersionHistoryProps> = ({ changelog }) => {
  return (
    <ComposedModal
      composedModalProps={{ shouldCloseOnOverlayClick: true }}
      modalHeaderProps={{
        title: "Version History",
      }}
      modalTrigger={({ openModal }: ModalTriggerProps) => (
        <TooltipHover
          direction="right"
          content={
            <div className={styles.tooltipContainer}>
              <strong>Version History</strong>
              <p style={{ marginTop: "0.5rem" }}>View the version changelog in a modal.</p>
            </div>
          }
        >
          <button className={styles.button} onClick={openModal}>
            <Catalog fill="#0072C3" />
          </button>
        </TooltipHover>
      )}
    >
      {() => <VersionHistoryModal changelog={changelog} />}
    </ComposedModal>
  );
};

const VersionHistoryModal: React.FC<VersionHistoryProps> = ({ changelog }) => {
  return (
    <ModalBody>
      <StructuredListWrapper>
        <StructuredListHead>
          <StructuredListRow head>
            <StructuredListCell head>Version</StructuredListCell>
            <StructuredListCell head>Author</StructuredListCell>
            <StructuredListCell head>Comments</StructuredListCell>
            <StructuredListCell head>Date</StructuredListCell>
          </StructuredListRow>
        </StructuredListHead>
        <StructuredListBody>
          {changelog.map((log, index) => (
            <StructuredListRow key={index}>
              <StructuredListCell>{log?.version ?? "---"}</StructuredListCell>
              <StructuredListCell noWrap>{log?.author ?? "---"}</StructuredListCell>
              <StructuredListCell>{log?.reason?.length ? log.reason : "---"}</StructuredListCell>
              <StructuredListCell noWrap>{log?.date ? moment(log.date).format(DATETIME_LOCAL_INPUT_FORMAT) : "---"}</StructuredListCell>
            </StructuredListRow>
          ))}
        </StructuredListBody>
      </StructuredListWrapper>
    </ModalBody>
  );
};

export default VersionHistory;
