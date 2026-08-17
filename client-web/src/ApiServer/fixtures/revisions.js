/* eslint-disable */

export default [
  {
    id: "61d620a33aa9034ded32caee",
    name: "Test Workflow",
    status: "active",
    version: 2,
    creationDate: "2022-01-05T22:50:12.204+00:00",
    annotations: {
      "boomerang.io/generation": "3",
      "boomerang.io/kind": "Workflow",
    },
    nodes: [
      {
        id: "start",
        position: {
          x: 50.0,
          y: 400.0,
        },
        data: {
          name: "start",
          params: [],
        },
        type: "start",
      },
      {
        id: "end",
        position: {
          x: 1500.0,
          y: 400.0,
        },
        data: {
          name: "end",
          params: [],
        },
        type: "end",
      },
      {
        id: "Sleep 1",
        position: {
          x: 800.0,
          y: 400.0,
        },
        data: {
          name: "Sleep 1",
          params: [
            {
              name: "duration",
              value: "30",
            },
          ],
          taskRef: "sleep",
          taskRef: 1,
          templateUpgradeAvailable: true,
        },
        type: "template",
      },
    ],
    edges: [
      {
        id: "64ad3c599d355450e1c2c530",
        source: "Sleep 1",
        target: "end",
        type: "template",
        data: null,
      },
      {
        id: "64ad3c599d355450e1c2c531",
        source: "start",
        target: "Sleep 1",
        type: "template",
        data: null,
      },
    ],
    params: [
      {
        name: "token",
        type: "string",
        description: "enter a token please",
        default: "a",
      },
      {
        name: "password",
        type: "string",
        description: "This is a secure value.",
        default: null,
      },
    ],
    tasks: [
      {
        name: "start",
        type: "start",
        taskRef: null,
        taskRef: null,
        labels: {},
        annotations: {
          "boomerang.io/position": {
            x: 300.0,
            y: 400.0,
          },
        },
        params: [],
        dependencies: [],
        results: null,
        workspaces: null,
      },
      {
        name: "end",
        type: "end",
        taskRef: null,
        taskRef: null,
        labels: {},
        annotations: {
          "boomerang.io/position": {
            x: 1000.0,
            y: 400.0,
          },
        },
        params: [],
        dependencies: [
          {
            taskRef: "Sleep 1",
            decisionCondition: "",
            executionCondition: "always",
          },
        ],
        results: null,
        workspaces: null,
      },
      {
        name: "Sleep 1",
        type: "template",
        taskRef: "sleep",
        taskRef: null,
        labels: {},
        annotations: {
          "boomerang.io/position": {
            x: 621.0036101083033,
            y: 391.5812274368231,
          },
        },
        params: [
          {
            name: "duration",
            value: "30",
          },
        ],
        dependencies: [
          {
            decisionCondition: "",
            executionCondition: "always",
          },
        ],
        results: [],
        workspaces: null,
      },
    ],
    changelog: {
      author: "61d38d133aa9034ded32cae6",
      reason: "",
      date: "2022-01-05T22:50:29.934+00:00",
    },
    icon: "bot",
    upgradesAvailable: false,
    triggers: {
      manual: {
        type: "manual",
        enabled: true,
        conditions: null,
      },
      webhook: {
        type: "webhook",
        enabled: false,
        conditions: null,
      },
      schedule: {
        type: "scheduler",
        enabled: false,
        conditions: null,
      },
      event: {
        type: "event",
        enabled: true,
        conditions: [
          { operation: "matches", field: "type", value: "io.boomerang.test" },
          { operation: "matches", field: "subject", value: "australia" },
        ],
      },
      github: {
        type: "github",
        enabled: true,
        conditions: [
          { operation: "in", field: "event", values: ["create", "delete"] },
          { operation: "in", field: "repository", values: ["australia", "img", "test"] },
        ],
      },
    },
    params: [
      {
        key: "token",
        description: "enter a token please",
        label: "Token",
        type: "text",
        minValueLength: null,
        maxValueLength: null,
        options: null,
        required: true,
        placeholder: null,
        language: null,
        disabled: null,
        default: "a",
        value: null,
        values: null,
        readOnly: false,
        hiddenValue: null,
        helperText: null,
      },
      {
        key: "password",
        description: "This is a secure value.",
        label: "Password",
        type: "password",
        minValueLength: null,
        maxValueLength: null,
        options: null,
        required: false,
        placeholder: null,
        language: null,
        disabled: null,
        default: null,
        value: null,
        values: null,
        readOnly: false,
        hiddenValue: null,
        helperText: null,
      },
    ],
  },
];
