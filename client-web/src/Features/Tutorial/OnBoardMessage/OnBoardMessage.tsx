import React from "react";
import { Button } from "@carbon/react";
import { Close } from "@carbon/react/icons";
import bkgskyline from "Assets/svg/bkg-skyline.svg";
import FadeAnimation from "Components/FadeAnimation";
import "./styles.scss";

type Props = {
  buttonsClassName?: string;
  closeModal?: () => void;
  contentClassName?: string;
  finishButton?: string;
  finishButtonClassName?: string;
  finishImgsClassName?: string;
  finishSubTitle?: string;
  finishQuestionMark?: string;
  goToScreen?: (screen: number) => void;
  homeScreen?: boolean;
  leftButton?: string;
  modalClassName?: string;
  nextScreen?: () => void;
  returnScreen?: number;
  rightButton?: string;
  subTitle?: string;
  subtitleClassName?: string;
  title?: string;
};

const OnBoardMessage = (props: Props) => {
  const {
    title,
    subTitle,
    leftButton,
    rightButton,
    finishButton,
    contentClassName,
    finishImgsClassName,
    finishButtonClassName,
    finishSubTitle,
    finishQuestionMark,
    subtitleClassName,
    buttonsClassName,
    closeModal,
    nextScreen,
    goToScreen,
    returnScreen,
  } = props;

  return (
    <FadeAnimation animationDuration={100} timeout={100} animationDelay={0} animationFunction="ease-in">
      <div className="c-onboard-wrapper">
        <section aria-label="Onboard Message Container" className="c-onboardExp">
          <button className="b-onboardExp-screen-modal-close" onClick={closeModal}>
            <Close />
          </button>
          <div className={finishImgsClassName}>
            <div className={finishButtonClassName}>
              <h1 className="b-onboardExp-finish__title">{title}</h1>
              <div className={subtitleClassName}>
                <div className="b-onboardExp-finish__subtitle">
                  {subTitle}
                  <div className="b-onboardExp-finish-oval-content">
                    <div className="b-onboardExp-finish-oval">{finishQuestionMark}</div>
                  </div>
                  {finishSubTitle}
                </div>
              </div>
              <div className={buttonsClassName}>
                <Button onClick={closeModal} size="md" style={{ marginTop: "2.6rem" }}>
                  {finishButton}
                </Button>
              </div>
            </div>
          </div>
          <section className={contentClassName}>
            <div className="b-onboardExp__title">{title}</div>
            <div className={subtitleClassName}>{subTitle}</div>
            <div className={buttonsClassName}>
              <Button size="md" onClick={() => returnScreen && goToScreen && goToScreen(returnScreen)}>
                {leftButton}
              </Button>
              <Button size="md" onClick={nextScreen} style={{ marginLeft: "1rem" }}>
                {rightButton}
              </Button>
            </div>
          </section>
          <footer className="b-onboardExp-footer">
            <img alt="" className="b-onboardExp-footer__img" src={bkgskyline} />
          </footer>
        </section>
      </div>
    </FadeAnimation>
  );
};

export default OnBoardMessage;
