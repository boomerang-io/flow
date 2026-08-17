export const defaultScreens = {
  DEFAULT: 0,
};

export const homeScreens = {
  WELCOME: 0,
  TEAMS: 1,
  SEARCH_FILTER: 2,
  SIDENAV: 3,
  DONE: 4,
  RETURN: 5,
};

export const designerScreens = {
  WELCOME: 0,
  WORKFLOW: 1,
  PROPERTIES: 2,
  SETTINGS: 3,
  CHANGE_LOG: 4,
  DONE: 5,
  RETURN: 6,
};

export const activityScreens = {
  WELCOME: 0,
  ACTIVITY: 1,
  ACTIVITY_FILTERS: 2,
  ACTIVITY_NUMBERS: 3,
  DONE: 4,
  RETURN: 5,
};

export const executionScreens = {
  WELCOME: 0,
  SIDE_INFO: 1,
  DIAGRAM: 2,
  DONE: 3,
  RETURN: 4,
};

export const insightsScreens = {
  WELCOME: 0,
  INSIGHTS_FILTERS: 1,
  INSIGHTS_GRAPHS: 2,
  DONE: 3,
  RETURN: 4,
};

export const messageConfig = {
  welcomeHome: {
    title: "Welcome to your Workflows!",
    subTitle: "Want us to show you around?",
    leftButton: "No, thanks",
    rightButton: "Yes, please!",
    finishButton: "",
    modalClassName: "b-onboardExp-screen--hidden",
    contentClassName: "b-onboardExp-content",
    finishImgsClassName: "b-onboardExp-screen--hidden",
    finishButtonClassName: "b-onboardExp-screen--hidden",
    finishSubTitle: "",
    finishQuestionMark: "",
    subtitleClassName: "b-onboardExp__subtitle",
    buttonsClassName: "b-onboardExp__buttons",
  },
  welcomeDesigner: {
    title: "Welcome to Editor!",
    subTitle: "Want us to show you around?",
    leftButton: "No, thanks",
    rightButton: "Yes, please!",
    finishButton: "",
    modalClassName: "b-onboardExp-screen--hidden",
    contentClassName: "b-onboardExp-content",
    finishImgsClassName: "b-onboardExp-screen--hidden",
    finishButtonClassName: "b-onboardExp-screen--hidden",
    finishSubTitle: "",
    finishQuestionMark: "",
    subtitleClassName: "b-onboardExp__subtitle",
    buttonsClassName: "b-onboardExp__buttons",
  },
  welcomeActivity: {
    title: "Welcome to the Activity!",
    subTitle: "Want us to show you around?",
    leftButton: "No, thanks",
    rightButton: "Yes, please!",
    finishButton: "",
    modalClassName: "b-onboardExp-screen--hidden",
    contentClassName: "b-onboardExp-content",
    finishImgsClassName: "b-onboardExp-screen--hidden",
    finishButtonClassName: "b-onboardExp-screen--hidden",
    finishSubTitle: "",
    finishQuestionMark: "",
    subtitleClassName: "b-onboardExp__subtitle",
    buttonsClassName: "b-onboardExp__buttons",
  },
  welcomeExecution: {
    title: "Welcome to Execution!",
    subTitle: "Want us to show you around?",
    leftButton: "No, thanks",
    rightButton: "Yes, please!",
    finishButton: "",
    modalClassName: "b-onboardExp-screen--hidden",
    contentClassName: "b-onboardExp-content",
    finishImgsClassName: "b-onboardExp-screen--hidden",
    finishButtonClassName: "b-onboardExp-screen--hidden",
    finishSubTitle: "",
    finishQuestionMark: "",
    subtitleClassName: "b-onboardExp__subtitle",
    buttonsClassName: "b-onboardExp__buttons",
  },
  welcomeInsights: {
    title: "Welcome to Insights!",
    subTitle: "Want us to show you around?",
    leftButton: "No, thanks",
    rightButton: "Yes, please!",
    finishButton: "",
    modalClassName: "b-onboardExp-screen--hidden",
    contentClassName: "b-onboardExp-content",
    finishImgsClassName: "b-onboardExp-screen--hidden",
    finishButtonClassName: "b-onboardExp-screen--hidden",
    finishSubTitle: "",
    finishQuestionMark: "",
    subtitleClassName: "b-onboardExp__subtitle",
    buttonsClassName: "b-onboardExp__buttons",
  },
  done: {
    title: "Great! Now let’s get to work.",
    subTitle: "Click ",
    leftButton: "",
    rightButton: "",
    finishButton: "Sounds good",
    modalClassName: "b-onboardExp-screen--hidden",
    contentClassName: "b-onboardExp-screen--hidden",
    finishImgsClassName: "b-onboardExp-finish",
    finishImgClassName: "b-onboardExp-screen--hidden",
    finishButtonClassName: "b-onboardExp-content",
    finishSubTitle: " in the navigation bar to return to this tutorial",
    finishQuestionMark: "?",
    subtitleClassName: "b-onboardExp-screen",
    buttonsClassName: "b-onboardExp-finish__buttons",
  },
  return: {
    title: "No worries, maybe next time.",
    subTitle: "Click ",
    leftButton: "",
    rightButton: "",
    finishButton: "Sounds good",
    modalClassName: "b-onboardExp-screen--hidden",
    contentClassName: "b-onboardExp-screen--hidden",
    finishImgsClassName: "b-onboardExp-finish",
    finishImgClassName: "b-onboardExp-screen--hidden",
    finishButtonClassName: "b-onboardExp-content",
    finishSubTitle: " in the navigation bar to return to this tutorial",
    finishQuestionMark: "?",
    subtitleClassName: "b-onboardExp-screen",
    buttonsClassName: "b-onboardExp-finish__buttons",
  },
  default: {
    title: "Sorry, no tutorial on this page",
    subTitle: "Click ",
    leftButton: "",
    rightButton: "",
    finishButton: "Sounds good",
    modalClassName: "b-onboardExp-screen--hidden",
    contentClassName: "b-onboardExp-screen--hidden",
    finishImgsClassName: "b-onboardExp-finish",
    finishImgClassName: "b-onboardExp-screen--hidden",
    finishButtonClassName: "b-onboardExp-content",
    finishSubTitle: " in the navigation bar on another page",
    finishQuestionMark: "?",
    subtitleClassName: "b-onboardExp-screen",
    buttonsClassName: "b-onboardExp-finish__buttons",
  },
};

export const homeGuideConfig = {
  teams: {
    containerClassName: "c-onboard-screen--teams",
    title: "Teams' workflows",
    message: "See all of your workflows by team and execute them.",
  },
  search_filter: {
    containerClassName: "c-onboard-screen--search_filter",
    title: "Search and Filter",
    message: "Search a workflow's name or filter workflows by team.",
  },
  sidenav: {
    containerClassName: "c-onboard-screen--sidenav",
    title: "Sidenav",
    message: "Access the different features of Boomerang Flow.",
  },
};

export const designerGuideConfig = {
  workflow: {
    containerClassName: "c-onboard-screen--workflow",
    title: "Workflow",
    message: "Create your workflow using tasks.",
  },
  properties: {
    containerClassName: "c-onboard-screen--properties",
    title: "Parameters",
    message: "Edit and delete workflow parameters that can be used by the workflow as it executes.",
  },

  settings: {
    containerClassName: "c-onboard-screen--settings",
    title: "Configure",
    message: "View and update general information about the workflow, set triggers, and configure options.",
  },
  change_log: {
    containerClassName: "c-onboard-screen--change_log",
    title: "Change Log",
    message: "See a version change log of your workflow.",
  },
};

export const activityGuideConfig = {
  activity: {
    containerClassName: "c-onboard-screen--activity",
    title: "Workflow Activity",
    message: "See all of your workflow activity. Select an activity to see its execution.",
  },
  activity_filters: {
    containerClassName: "c-onboard-screen--activity_filters",
    title: "Filters",
    message: "Filter activity by status, team, workflow, trigger and date.",
  },
  activity_numbers: {
    containerClassName: "c-onboard-screen--activity_numbers",
    title: "Today's numbers",
    message: "See activity data from the last 24 hours.",
  },
};

export const executionGuideConfig = {
  side_info: {
    containerClassName: "c-onboard-screen--side_info",
    title: "Side Info",
    message: "See workflow execution status, its duration and information about the tasks.",
  },
  diagram: {
    containerClassName: "c-onboard-screen--diagram",
    title: "Workflow Diagram",
    message: "See your workflow execution in action.",
  },
};

export const insightsGuideConfig = {
  filters: {
    containerClassName: "c-onboard-screen--side_info",
    title: "Filters",
    message: "View what you want",
  },
  graphs: {
    containerClassName: "c-onboard-screen--diagram",
    title: "Graphs",
    message: "Nice graphs here",
  },
};
