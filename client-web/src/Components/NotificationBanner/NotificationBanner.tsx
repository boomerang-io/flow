//@ts-nocheck
import React, { Component } from "react";
import PropTypes from "prop-types";
import classnames from "classnames";
import { Close } from "@carbon/react/icons";
import "./styles.scss";

const SLACK_TEAM_ID = "T27TLPNS1";
const SLACK_FLOW_USERS_CHANNEL_ID = "CFKN6FJD6";

class NotificationBanner extends Component {
  static propTypes = {
    closeBanner: PropTypes.func
  };

  state = {
    isOpen: true
  };

  closeBanner = () => {
    this.setState({ isOpen: false });
    this.props.closeBanner();
  };

  render() {
    if (!this.state.isOpen) {
      return null;
    }
    return (
      <div className={classnames("b-notification-banner")}>
        <div className="b-notification-banner__text">
          Welcome to Boomerang Flow! We're excited to have you as a user and hope you enjoy using it. Please don't
          hesitate to reach out to us on Slack in{" "}
          {<a href={`slack://channel?team=${SLACK_TEAM_ID}&id=${SLACK_FLOW_USERS_CHANNEL_ID}`}>@bmrg-flow-users</a>}.
          Thanks!
        </div>
        <button className="b-notification-banner__button" onClick={this.closeBanner}>
          <Close size={32} className="b-notification-banner__close-icon" />
        </button>
      </div>
    );
  }
}

export default NotificationBanner;
