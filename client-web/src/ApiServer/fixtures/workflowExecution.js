const workflowExecution = {
  creationDate: "2020-10-19T22:57:50.228+00:00",
  duration: 31795,
  id: "5f8e19ee8f268161b4beb242",
  status: "completed",
  workflowId: "5e877d1f4bbc6e0001c51e12",
  workflowRevisionid: "5f84af7eb871567a65532cf8",
  trigger: "webhook",
  properties: [
    {
      key: "teamName",
      value: "AutoJoinTool",
    },
    {
      key: "requesterEmail",
      value: "mdroy@us.ibm.com",
    },
    {
      key: "purpose",
      value: "Delivery",
    },
    {
      key: "serviceName",
      value: "Output Demo Flow",
    },
    {
      key: "setting",
      value: "asfa",
    },
    {
      key: "requesterTeamRole",
      value: "owner",
    },
    {
      key: "box.id",
      value: "ad",
    },
    {
      key: "sys_id",
      value: "12345",
    },
    {
      key: "requesterName",
      value: "Marcus Roy",
    },
    {
      key: "teamId",
      value: "5efe1c7bdf010247e71272b8",
    },
    {
      key: "organization",
      value: null,
    },
    {
      key: "name",
      value: "AutoJoinTool",
    },
    {
      key: "createdGroupId",
      value: "5efe1c7bdf010247e71272b8",
    },
  ],
  outputProperties: [
    {
      key: "box.id",
      value: "Sincere@april.biz",
    },
  ],
  steps: [
    {
      activityId: "5f8e19ee8f268161b4beb242",
      duration: 12860,
      flowTaskStatus: "completed",
      id: "5f8e19ee8f268161b4beb243",
      order: 1,
      startTime: "2020-10-19T22:57:50.333+00:00",
      taskId: "fe6a167c-4c2b-429d-ae68-9d1e3d030b37",
      taskName: "Execute Shell 1",
      taskType: "template",
      preApproved: false,
      error: {
        code: "001",
        message: "This is a task level special error",
      },
      results: [
        {
          name: "organizations",
          description: "List of the organizations",
          value: "Testing value",
        },
        {
          name: "test",
          description: null,
          value: "Testing value",
        },
        {
          name: "test again",
          description: "Test description",
          value: null,
        },
        {
          name: "test one more result",
          description: null,
          value: null,
        },
      ],
    },
    {
      activityId: "5f8e19ee8f268161b4beb242",
      duration: 14503,
      flowTaskStatus: "completed",
      id: "5f8e19ee8f268161b4beb244",
      order: 2,
      startTime: "2020-10-19T22:58:03.285+00:00",
      taskId: "ea1b88d6-2c85-416f-b45e-50f4f5fa3a94",
      taskName: "Execute HTTP Call 1",
      taskType: "template",
      preApproved: false,
      results: {
        "#Mon Oct 19 22:58:16 GMT 2020": "",
        response:
          '{"id"\\:1,"name"\\:"Leanne Graham","username"\\:"Bret","email"\\:"Sincere@april.biz","address"\\:{"street"\\:"Kulas Light","suite"\\:"Apt. 556","city"\\:"Gwenborough","zipcode"\\:"92998-3874","geo"\\:{"lat"\\:"-37.3159","lng"\\:"81.1496"}},"phone"\\:"1-770-736-8031 x56442","website"\\:"hildegard.org","company"\\:{"name"\\:"Romaguera-Crona","catchPhrase"\\:"Multi-layered client-server neural-net","bs"\\:"harness real-time e-markets"}}',
      },
    },
    {
      activityId: "5f8e19ee8f268161b4beb242",
      duration: 3984,
      flowTaskStatus: "completed",
      id: "5f8e19ee8f268161b4beb245",
      order: 3,
      startTime: "2020-10-19T22:58:17.854+00:00",
      taskId: "225e86b9-e187-4d09-9e5f-a6f4d5778868",
      taskName: "Json Path To Property 1",
      taskType: "template",
      preApproved: false,
      results: {
        "#Mon Oct 19 22:58:20 GMT 2020": "",
        email: "Sincere@april.biz",
      },
    },
    {
      activityId: "5f8e19ee8f268161b4beb242",
      duration: 63,
      flowTaskStatus: "completed",
      id: "5f8e19ee8f268161b4beb246",
      order: 4,
      startTime: "2020-10-19T22:58:21.883+00:00",
      taskId: "ecececb3-5334-41d1-bd02-449733f5d8f3",
      taskName: "Set Output Property 1",
      taskType: "setwfproperty",
      preApproved: false,
    },
  ],
  teamName: "AMHTest2",
  awaitingApproval: false,
  error: {
    code: "002",
    message: "This is a top level special error",
  },
};

export default workflowExecution;
