const tokenCatalog = {
  resources: [
    "system",
    "workflow",
    "workflowrun",
    "workflowtemplate",
    "taskrun",
    "task",
    "action",
    "user",
    "workspace",
    "token",
    "parameter",
    "schedule",
    "insights",
    "integration",
    "webhook",
  ],
  actions: ["read", "write", "delete", "action"],
  rolePresets: {
    owner: ["**/**"],
    editor: [
      "workflow/read",
      "workflow/write",
      "workflow/action",
      "workflowrun/read",
      "workflowrun/write",
      "workflowrun/action",
    ],
    reader: ["workflow/read", "workflowrun/read"],
  },
};

export default tokenCatalog;
