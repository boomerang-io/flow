const platformNavigation = {
  platform: {
    version: "7.1.0",
    name: "Boomerang Flow",
    signOutUrl: "https://launch.boomerangplatform.net/oauth/sign_out?rd=/oauth/sign_out",
    platformName: "Boomerang",
    appName: "Flow",
    displayLogo: false,
    privateTeams: false,
    sendMail: true,
  },
  features: { "notifications.enabled": false, "support.enabled": true, "docs.enabled": true },
  navigation: [
    { name: "Launchpad", url: "https://launch.boomerangplatform.net/launchpad" },
    { name: "Catalog", url: "https://launch.boomerangplatform.net/catalog" },
    { name: "Status", url: "https://launch.boomerangplatform.net/status" },
    { name: "Docs", url: "https://launch.boomerangplatform.net/docs" },
    { name: "Admin", url: "https://launch.boomerangplatform.net/admin" },
  ],
  featuredServices: [
    {
      name: "Boomerang CICD",
      url: "https://launch.boomerangplatform.net/cicd/apps",
      templateId: "5994b744189c33ed8433d9f6",
      imageId: "5e7010f75d65820001ca7b88",
    },
    {
      name: "Boomerang Flow",
      url: "https://launch.boomerangplatform.net/flow/apps/flow",
      templateId: "5c467857f32aa30001e9e2b8",
      imageId: "5e3456a13382ee000188b443",
    },
  ],
  platformMessage: null,
};

export default platformNavigation;
