//@ts-nocheck
import React from "react";
import PropTypes from "prop-types";
import cx from "classnames";
import { useAppContext } from "Hooks";
import { Close, ChevronUp } from "@carbon/react/icons";
import styles from "./welcomeBanner.module.scss";

const SLACK_TEAM_ID = "T27TLPNS1";
const SLACK_FLOW_USERS_CHANNEL_ID = "CFKN6FJD6";

WelcomeBanner.propTypes = {
  hide: PropTypes.func,
  isOpen: PropTypes.bool,
  openTutorial: PropTypes.func,
  toggleIsOpen: PropTypes.func,
};

export default function WelcomeBanner({ hide, isOpen, openTutorial, toggleIsOpen }) {
  const { communityUrl } = useAppContext();

  return (
    <div className={cx(styles.container, { [styles.collapsed]: !isOpen })}>
      <section className={styles.closeHideButtons}>
        <button className={styles.closeButton} onClick={hide}>
          <Close size={32} aria-label="close" className={styles.closeIcon} />
          <p className={styles.closeText}>Don't show again</p>
        </button>
        <button className={styles.collapseButton} onClick={toggleIsOpen}>
          <p className={styles.collapseText}>{isOpen ? "Collapse" : "Welcome to Flow"}</p>
          <ChevronUp size={32} aria-label="collapse" className={cx(styles.collapseIcon, { [styles.closed]: !isOpen })} />
        </button>
      </section>
      <div className={cx(styles.content, { [styles.closed]: !isOpen })}>
        <h1 className={styles.title}>Welcome to your Workflows</h1>
        <p className={styles.subtitle}>Turn ideation into Automation</p>
        <div className={cx(styles.buttons, { [styles.closed]: !isOpen })}>
          <button className={styles.button} disabled={!isOpen} onClick={openTutorial}>
            <p className={styles.buttonText}>Learn the basics</p>
          </button>
          {/* <button className={`${styles.button} ${styles.buttonMiddle}`}>
            <p className={styles.buttonText}>Jump in</p>
            <p className={styles.buttonSubtext}>with a sample workflow</p>
          </button> */}
          <a
            className={styles.button}
            href={
              communityUrl.includes("ibm.com")
                ? `slack://channel?team=${SLACK_TEAM_ID}&id=${SLACK_FLOW_USERS_CHANNEL_ID}`
                : "https://join.slack.com/t/boomerang-io/shared_invite/zt-pxo2yw2o-c3~6YvWkKNrKIwhIBAKhaw"
            }
            tabIndex={isOpen ? 0 : -1}
          >
            <p className={styles.buttonText}>Slack us feedback</p>
          </a>
        </div>
      </div>
    </div>
  );
}
