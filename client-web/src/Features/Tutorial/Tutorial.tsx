import React, { useState } from "react";
import { useLocation } from "react-router-dom";
import {
  defaultScreens,
  homeScreens,
  designerScreens,
  activityScreens,
  executionScreens,
  insightsScreens,
  messageConfig,
  homeGuideConfig,
  designerGuideConfig,
  activityGuideConfig,
  executionGuideConfig,
  insightsGuideConfig,
} from "./constants";
import OnBoardGuideContainer from "./OnBoardGuideContainer";
import OnBoardMessage from "./OnBoardMessage";
import "./styles.scss";

type Props = {
  isTutorialActive: boolean;
  setIsTutorialActive: (prop: boolean) => void;
};

export default function OnBoardExpContainer({ isTutorialActive, setIsTutorialActive }: Props) {
  const [screen, setScreen] = useState(0);
  const location = useLocation();

  const nextScreen = () => {
    setScreen((prevCount) => prevCount + 1);
  };

  const previousScreen = () => {
    setScreen((prevCount) => prevCount - 1);
  };

  const goToScreen = (newScreen: any) => {
    setScreen(newScreen);
  };

  const closeModal = () => {
    setScreen(0);
    setIsTutorialActive(false);
  };

  if (!isTutorialActive) {
    return null;
  }

  const index = screen;
  const path = location.pathname;
  let screens: any = {};
  let guideConfig : any= {};
  let message: any = {};

  if (path.includes("/workflows")) {
    screens = homeScreens;
    guideConfig = homeGuideConfig;
    message = messageConfig.welcomeHome;
  } else if (path.includes("/editor")) {
    screens = designerScreens;
    guideConfig = designerGuideConfig;
    message = messageConfig.welcomeDesigner;
  } else if (path.includes("/activity") && !path.includes("/execution")) {
    screens = activityScreens;
    guideConfig = activityGuideConfig;
    message = messageConfig.welcomeActivity;
  } else if (path.includes("/execution")) {
    screens = executionScreens;
    guideConfig = executionGuideConfig;
    message = messageConfig.welcomeExecution;
  } else if (path.includes("/insights")) {
    screens = insightsScreens;
    guideConfig = insightsGuideConfig;
    message = messageConfig.welcomeInsights;
  } else {
    screens = defaultScreens;
  }

  if (index === screens.DEFAULT) {
    return (
      <div className="c-onboard-wrapper">
        <OnBoardMessage closeModal={closeModal} {...messageConfig.default} />
      </div>
    );
  }

  if (index === screens.WELCOME) {
    return (
      <div className="c-onboard-wrapper">
        <OnBoardMessage
          nextScreen={nextScreen}
          closeModal={closeModal}
          goToScreen={goToScreen}
          returnScreen={screens.RETURN}
          {...message}
        />
      </div>
    );
  }

  if (index === screens.DONE) {
    return (
      <div className="c-onboard-wrapper">
        <OnBoardMessage nextScreen={nextScreen} closeModal={closeModal} {...messageConfig.done} />
      </div>
    );
  }

  if (index === screens.RETURN) {
    return (
      <div className="c-onboard-wrapper">
        <OnBoardMessage closeModal={closeModal} {...messageConfig.return} />
      </div>
    );
  }

  return (
    <div className="c-onboard-wrapper c-onboard-wrapper--transparent">
      <OnBoardGuideContainer
        index={index}
        nextScreen={nextScreen}
        previousScreen={previousScreen}
        closeModal={closeModal}
        screens={screens}
        guideConfig={guideConfig}
      />
    </div>
  );
}
