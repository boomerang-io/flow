import React from "react";
import OnBoardGuide from "./OnBoardGuide";

type Props = {
  closeModal: () => void;
  index: number;
  guideConfig: any;
  nextScreen: () => void;
  previousScreen: () => void;
  screens: any;
};

const OnBoardGuideContainer = ({ index, nextScreen, previousScreen, closeModal, screens, guideConfig }: Props) => {
  const basicConfig = { index, nextScreen, previousScreen, closeModal, screens, guideConfig };

  const determineProps = () => {
    switch (index) {
      case screens.TEAMS:
        return {
          index,
          nextScreen,
          closeModal,
          screens,
          guideConfig,
          ...guideConfig.teams,
        };
      case screens.SEARCH_FILTER:
        return { ...basicConfig, ...guideConfig.search_filter };
      case screens.SIDENAV:
        return { ...basicConfig, ...guideConfig.sidenav };
      case screens.WORKFLOW:
        return {
          index,
          nextScreen,
          closeModal,
          screens,
          guideConfig,
          ...guideConfig.workflow,
        };
      case screens.PROPERTIES:
        return { ...basicConfig, ...guideConfig.properties };
      case screens.CHANGE_LOG:
        return { ...basicConfig, ...guideConfig.change_log };
      case screens.SETTINGS:
        return { ...basicConfig, ...guideConfig.settings };
      case screens.ACTIVITY:
        return { ...basicConfig, ...guideConfig.activity };
      case screens.ACTIVITY_FILTERS:
        return { ...basicConfig, ...guideConfig.activity_filters };
      case screens.ACTIVITY_NUMBERS:
        return { ...basicConfig, ...guideConfig.activity_numbers };
      case screens.SIDE_INFO:
        return { ...basicConfig, ...guideConfig.side_info };
      case screens.DIAGRAM:
        return { ...basicConfig, ...guideConfig.diagram };
      case screens.INSIGHTS_FILTERS:
        return { ...basicConfig, ...guideConfig.filters };
      case screens.INSIGHTS_GRAPHS:
        return { ...basicConfig, ...guideConfig.graphs };
      default:
        return { ...basicConfig };
    }
  };

  const props = determineProps();
  return <OnBoardGuide {...props} />;
};

export default OnBoardGuideContainer;
