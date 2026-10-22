import { ModalForm } from "@boomerang-io/carbon-addons-boomerang-react";
import { ModalBody } from "@carbon/react";
import React from "react";
import ReactMarkdown from "react-markdown";
import "Styles/markdown.css";
import styles from "./errorModal.module.scss";

interface ErrorModalProps {
  errorCode: string;
  errorMessage: string;
}

function ErrorModal({ errorCode, errorMessage }: ErrorModalProps) {
  return (
    <ModalForm>
      <ModalBody>
        <section className={styles.detailedSection}>
          <span className={styles.sectionHeader}>Error Code</span>
          <p className={styles.sectionDetail}>{errorCode}</p>
        </section>
        <section className={styles.detailedSection}>
          <span className={styles.sectionHeader}>Error Message</span>
          <ReactMarkdown className="markdown-body" children={errorMessage} />
        </section>
      </ModalBody>
    </ModalForm>
  );
}

export default ErrorModal;
