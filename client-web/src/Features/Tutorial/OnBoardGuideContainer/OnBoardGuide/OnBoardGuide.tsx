import React from "react";
import { Button } from "@carbon/react";
import { ArrowRight, ArrowLeft, Close } from "@carbon/react/icons";
import "./styles.scss";

type Props = {
  closeModal: () => void;
  containerClassName: string;
  index: number;
  guideConfig: any;
  message: string;
  nextScreen: () => void;
  previousScreen: () => void;
  screens: any;
  title: string;
};

const OnBoardGuide = (props: Props) => {
  const {
    index,
    containerClassName,
    closeModal,
    title,
    message,
    nextScreen,
    previousScreen,
    screens,
    guideConfig,
  } = props;

  const guideCount = Object.keys(guideConfig);
  const circles = guideCount.map((key, i) => (
    <div key={i} className={`b-onboard-screen-circle ${index === i + 1 ? "b-onboard-screen-circle--filled" : ""}`} />
  ));

  const lastScreen =
    index === screens.SIDENAV ||
    index === screens.CHANGE_LOG ||
    index === screens.ACTIVITY_NUMBERS ||
    index === screens.DIAGRAM ||
    index === screens.INSIGHTS_GRAPHS;

  const leftAction =
    index === 1 ? null : (
      <Button
        hasIconOnly
        className="b-onboard-screen-arrow"
        data-testid="onboardArrowLeft"
        iconDescription="back"
        onClick={previousScreen}
        renderIcon={ArrowLeft}
        size="sm"
        style={{ width: "2rem", height: "1rem", marginRight: "1rem" }}
        tooltipAlignment="center"
        tooltipPosition="bottom"
      />
    );

  const rightAction = lastScreen ? (
    <Button
      kind="primary"
      onClick={nextScreen}
      size="sm"
      style={{ width: "1rem" }} //will have to change
    >
      Done
    </Button>
  ) : (
    <Button
      hasIconOnly
      className="b-onboard-screen-arrow"
      iconDescription="forward"
      onClick={nextScreen}
      renderIcon={ArrowRight}
      size="sm"
      style={{ width: "2rem", height: "1rem" }}
      tooltipAlignment="center"
      tooltipPosition="bottom"
    />
  );

  return (
    <section aria-label="Onboard" className={`c-onboard-screen ${containerClassName}`}>
      <button className="b-onboard-screen-exit-button" onClick={closeModal}>
        <Close />
      </button>
      <h1 className="b-onboard-screen-title">{title}</h1>
      <p className="b-onboard-screen-content">{message}</p>
      <footer className="b-onboard-screen-bottom">
        <div className="b-onboard-screen-bottom--left">{circles}</div>
        <div className="b-onboard-screen-bottom--right">
          {leftAction}
          {rightAction}
        </div>
      </footer>
    </section>
  );
};

export default OnBoardGuide;
