const workflowTemplates = {
  content: [
    {
      name: "mongodb-email-query-results",
      displayName: "MongoDB email query results",
      version: 1,
      creationDate: "2022-05-06T16:43:09.659Z",
      icon: "bot",
      description:
        "This is a workflow example that runs a MongoDB query and then simultaneously sends the results as an attached file on an email and prints out the results for an easy verification.",
      shortDescription: "Sending the mongodb query results as an attachement of an email.",
      labels: {},
      annotations: {
        "io#boomerang/kind": "WorkflowTemplate",
        "io#boomerang/generation": "3",
      },
      changelog: {
        reason: "",
        date: "2022-05-06T16:43:09.659Z",
        author: "608fb6fb70bfa94ac91f90cb",
      },
      tasks: [
        {
          name: "start",
          type: "start",
          dependencies: [],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 16.0845697329377,
              y: 286.488872403561,
            },
          },
        },
        {
          name: "end",
          type: "end",
          dependencies: [
            {
              executionCondition: "always",
              metadata: {
                points: [
                  {
                    id: "910630b3-87b2-4e99-8c42-33dfb5a768f0",
                    selected: false,
                    x: 959.0166015625,
                    y: 327.083312988281,
                  },
                  {
                    id: "11036535-695c-4bcb-8465-072151940e62",
                    selected: false,
                    x: 1166.91662597656,
                    y: 329.88330078125,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "Send the results as attachemnt",
            },
            {
              executionCondition: "always",
              metadata: {
                points: [
                  {
                    id: "8be9e699-e740-4ef2-baa8-43473e7f4eb1",
                    selected: false,
                    x: 960.666625976562,
                    y: 154.883331298828,
                  },
                  {
                    id: "6420d585-21ec-485c-b021-4a27a9058b1f",
                    selected: false,
                    x: 1166.91662597656,
                    y: 329.88330078125,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "Display the results",
            },
            {
              executionCondition: "always",
              metadata: {
                points: [
                  {
                    id: "490ab0f5-5ec0-43b9-880d-98e1722585d6",
                    selected: false,
                    x: 967.349975585938,
                    y: 497.566650390625,
                  },
                  {
                    id: "3330daec-a73f-4941-8caf-b5a084dec312",
                    selected: false,
                    x: 1166.91662597656,
                    y: 329.88330078125,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "Failed to run the query email",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 1182.91952522255,
              y: 291.886840751731,
            },
          },
        },
        {
          name: "MongoDB Execute Query",
          taskRef: "mongodb-query-execution",
          results: [],
          params: [
            {
              name: "database",
              value: "db",
            },
            {
              name: "password",
              value: "pass",
            },
            {
              name: "port",
              value: "27017",
            },
            {
              name: "query",
              value: "$(params.query)",
            },
            {
              name: "host",
              value: "mongodb-host",
            },
            {
              name: "tlsenabled",
              value: "false",
            },
            {
              name: "authenticationMechanism",
              value: "SCRAM-SHA-1",
            },
            {
              name: "ouputfile",
              value: "/tekton/results/$(params.workflow-activity-id)/response.json",
            },
            {
              name: "username",
              value: "username",
            },
          ],
          type: "template",
          dependencies: [
            {
              executionCondition: "always",
              metadata: {
                points: [
                  {
                    id: "94f5450f-267a-4f69-9b5f-93e21983e734",
                    selected: false,
                    x: 176.083312988281,
                    y: 324.483337402344,
                  },
                  {
                    id: "8ed5a8c8-aa5f-4d79-a6c1-b1536aecff8c",
                    selected: false,
                    x: 294.583312988281,
                    y: 324.400024414062,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: null,
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 310.591246290801,
              y: 284.396142433234,
            },
          },
        },
        {
          name: "Display the results",
          taskRef: "execute-shell",
          results: [],
          params: [
            {
              name: "path",
              value: "",
            },
            {
              name: "shell",
              value: "",
            },
            {
              name: "script",
              value: "cat /tekton/results/$(params.workflow-activity-id)/response.json",
            },
          ],
          type: "script",
          dependencies: [
            {
              executionCondition: "success",
              metadata: {
                points: [
                  {
                    id: "25043fd8-7282-4140-a6af-621e8b977ece",
                    selected: false,
                    x: 546.583312988281,
                    y: 324.400024414062,
                  },
                  {
                    id: "0c978744-836d-42bf-82d0-ce8d9091cecc",
                    selected: false,
                    x: 708.666687011719,
                    y: 154.883331298828,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "MongoDB Execute Query",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 724.670308605341,
              y: 114.881440158259,
            },
          },
        },
        {
          name: "Send the results as attachemnt",
          taskRef: "send-email-with-sendgrid",
          results: [],
          params: [
            {
              name: "cc",
              value: "",
            },
            {
              name: "bcc",
              value: "",
            },
            {
              name: "attachments",
              value: "/tekton/results/$(params.workflow-activity-id)/response.json",
            },
            {
              name: "apiKey",
              value: "SG.example",
            },
            {
              name: "subject",
              value: "Here are the results of the query",
            },
            {
              name: "replyTo",
              value: "",
            },
            {
              name: "from",
              value: "from@ibm.com",
            },
            {
              name: "bodyContent",
              value:
                "You asked for the results of the mongo DB query.\nThe executed query was:\n$(params.query)\n\nThe results can be found in the attachement.\n\nRegards, \nThe bot",
            },
            {
              name: "to",
              value: "to@ibm.com",
            },
            {
              name: "contentType",
              value: "Text",
            },
          ],
          type: "template",
          dependencies: [
            {
              executionCondition: "success",
              metadata: {
                points: [
                  {
                    id: "32a7b041-c193-44d8-b86b-d71ac7d7aa66",
                    selected: false,
                    x: 546.583312988281,
                    y: 324.400024414062,
                  },
                  {
                    id: "1023272d-3705-4c34-bfce-2ea51c769a34",
                    selected: false,
                    x: 707.016662597656,
                    y: 327.083312988281,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "MongoDB Execute Query",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 723.022255192878,
              y: 287.083086053413,
            },
          },
        },
        {
          name: "Failed to run the query email",
          taskRef: "send-email-with-sendgrid",
          results: [],
          params: [
            {
              name: "cc",
              value: "",
            },
            {
              name: "bcc",
              value: "",
            },
            {
              name: "attachments",
              value: "",
            },
            {
              name: "apiKey",
              value: "SG.example",
            },
            {
              name: "subject",
              value: "Failed to execute the MongoDB query",
            },
            {
              name: "replyTo",
              value: "",
            },
            {
              name: "from",
              value: "from@ibm.com",
            },
            {
              name: "bodyContent",
              value:
                "Hi there, \n  \n  Sorry to let you know, the MongoDB query execution failed. \n  We can not upload the results, please forward the executed query to the proper authorities and ask them to look into it.\n  \n  Executed MongoDB query:\n$(params.query)\n\nRegards, \nThe bot",
            },
            {
              name: "to",
              value: "to@ibm.com",
            },
            {
              name: "contentType",
              value: "Text",
            },
          ],
          type: "template",
          dependencies: [
            {
              executionCondition: "failure",
              metadata: {
                points: [
                  {
                    id: "ce65f0be-6e71-4ffa-8468-5e59f994257d",
                    selected: false,
                    x: 546.583312988281,
                    y: 324.400024414062,
                  },
                  {
                    id: "66424896-cba5-4387-babd-95269a8eef53",
                    selected: false,
                    x: 715.349975585938,
                    y: 497.566650390625,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "MongoDB Execute Query",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 731.346439169139,
              y: 457.570474777448,
            },
          },
        },
      ],
      workspaces: [
        {
          name: "workflowrun",
          type: "workflowrun",
          optional: false,
          spec: {
            size: 1,
            mountPath: "/tekton/results",
          },
        },
      ],
      config: [
        {
          description: "",
          key: "query",
          label: "query",
          type: "textarea",
          required: true,
          defaultValue:
            "DBQuery.shellBatchSize = 1000;db.getCollection('my_collection').find({'timestamp':{$gt: ISODate('2022-01-01 00:00:00.000Z')}}).batchSize(1000)",
          readOnly: false,
        },
      ],
      params: [
        {
          name: "query",
          type: "string",
          description: "",
          defaultValue:
            "DBQuery.shellBatchSize = 1000;db.getCollection('my_collection').find({'timestamp':{$gt: ISODate('2022-01-01 00:00:00.000Z')}}).batchSize(1000)",
        },
      ],
    },
    {
      name: "looking-through-planets-with-http-call-",
      displayName: "Looking through planets with HTTP Call ",
      creationDate: "2022-05-06T21:09:13.633Z",
      icon: "bot",
      description:
        "Workflow example that uses HTTP Call to look for Star Wars planets, their characteristics and the movies where they appeared. ",
      shortDescription: "Searching for Star Wars planets",
      labels: {},
      annotations: {
        "io#boomerang/kind": "WorkflowTemplate",
        "io#boomerang/generation": "3",
      },
      version: 1,
      changelog: {
        reason: "",
        date: "2022-05-06T21:09:13.633Z",
        author: "608fb6fb70bfa94ac91f90cb",
      },
      tasks: [
        {
          name: "start",
          type: "start",
          dependencies: [],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: -155.322475246813,
              y: 388.299674271785,
            },
          },
        },
        {
          name: "end",
          type: "end",
          dependencies: [
            {
              executionCondition: "always",
              metadata: {
                points: [
                  {
                    id: "0638c381-a406-455a-8d9a-1284b0461cbe",
                    selected: false,
                    x: 2306.10234708248,
                    y: 449.264098306084,
                  },
                  {
                    id: "cd06359f-14ea-4379-9fa1-8442aee40985",
                    selected: false,
                    x: 2433.84564371986,
                    y: 442.264840145847,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "Destroying the planet",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 2449.84061477032,
              y: 404.268753835939,
            },
          },
        },
        {
          name: "Search through planets",
          taskRef: "execute-basic-http-call",
          results: [],
          params: [
            {
              name: "allowUntrustedCerts",
              value: "",
            },
            {
              name: "method",
              value: "GET",
            },
            {
              name: "header",
              value: "Accept: application/json",
            },
            {
              name: "body",
              value: "",
            },
            {
              name: "contentType",
              value: "application/json",
            },
            {
              name: "url",
              value: "https://swapi.dev/api/planets",
            },
            {
              name: "outputFilePath",
              value: "/tekton/results/planets.json",
            },
          ],
          type: "template",
          dependencies: [
            {
              executionCondition: "always",
              metadata: {
                points: [
                  {
                    id: "03b34e11-bddf-4ef7-8c6b-7db9d9ee2854",
                    selected: false,
                    x: 4.69139130815908,
                    y: 426.290092853127,
                  },
                  {
                    id: "98bfbf11-fa81-49b3-8128-bee570c0ecf0",
                    selected: false,
                    x: 72.0489480219538,
                    y: 340.103082221057,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: null,
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 88.0561022826839,
              y: 300.122021922991,
            },
          },
        },
        {
          name: "Get the planet",
          taskRef: "file-jsonpath-to-parameter",
          results: [],
          params: [
            {
              name: "filePath",
              value: "/tekton/results/planets.json",
            },
            {
              name: "query",
              value: "$.results[?(@.name=='$(params.planet)')].url",
            },
          ],
          type: "template",
          dependencies: [
            {
              executionCondition: "success",
              metadata: {
                points: [
                  {
                    id: "0cc2abd5-948d-4908-a884-2134e572bd4b",
                    selected: false,
                    x: 324.049683160527,
                    y: 340.103082221057,
                  },
                  {
                    id: "285225d4-7111-42d0-8bb6-e3d43d136ffe",
                    selected: false,
                    x: 351.113508184869,
                    y: 460.408008518842,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "Search through planets",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 367.093581060401,
              y: 420.420333120955,
            },
          },
        },
        {
          name: "Scan the planet",
          taskRef: "execute-advanced-http-call",
          results: [],
          params: [
            {
              name: "errorcodes",
              value: "5xx",
            },
            {
              name: "allowUntrustedCerts",
              value: "",
            },
            {
              name: "method",
              value: "GET",
            },
            {
              name: "body",
              value: "",
            },
            {
              name: "url",
              value: "$(tasks.Get the planet.results.evaluation)",
            },
            {
              name: "outputFilePath",
              value: "/tekton/results/planetdetails.json",
            },
            {
              name: "retrynumber",
              value: "",
            },
            {
              name: "retrydelay",
              value: "",
            },
            {
              name: "header",
              value: "Accept: application/json",
            },
            {
              name: "retrycodes",
              value: "",
            },
            {
              name: "successcodes",
              value: "",
            },
            {
              name: "contentType",
              value: "application/json",
            },
          ],
          type: "template",
          dependencies: [
            {
              executionCondition: "success",
              metadata: {
                points: [
                  {
                    id: "6d0cade9-5fd6-436b-804b-dff5af60cdf8",
                    selected: false,
                    x: 603.086801953415,
                    y: 460.408008518842,
                  },
                  {
                    id: "43b6a4a5-900c-4e18-a888-23589dac67bf",
                    selected: false,
                    x: 652.438447403271,
                    y: 330.715872020325,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "Get the planet",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 668.443132341039,
              y: 290.709858684681,
            },
          },
        },
        {
          name: "Track the film",
          taskRef: "file-jsonpath-to-parameter",
          results: [],
          params: [
            {
              name: "filePath",
              value: "/tekton/results/planetdetails.json",
            },
            {
              name: "query",
              value: "$.films[0]",
            },
          ],
          type: "template",
          dependencies: [
            {
              executionCondition: "success",
              metadata: {
                points: [
                  {
                    id: "35400cd0-dfff-4cac-b098-523704190680",
                    selected: false,
                    x: 904.439182541844,
                    y: 330.715872020325,
                  },
                  {
                    id: "50f2572c-a43b-4b39-8d45-052fb942853e",
                    selected: false,
                    x: 930.322727101493,
                    y: 458.431777546597,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "Scan the planet",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 946.318983169204,
              y: 418.430346798634,
            },
          },
        },
        {
          name: "Scan the film",
          taskRef: "execute-advanced-http-call",
          results: [],
          params: [
            {
              name: "errorcodes",
              value: "",
            },
            {
              name: "allowUntrustedCerts",
              value: "",
            },
            {
              name: "method",
              value: "GET",
            },
            {
              name: "body",
              value: "",
            },
            {
              name: "url",
              value: "$(tasks.Track the film.results.evaluation)",
            },
            {
              name: "outputFilePath",
              value: "/tekton/results/thefilm.json",
            },
            {
              name: "retrynumber",
              value: "",
            },
            {
              name: "retrydelay",
              value: "",
            },
            {
              name: "header",
              value: "Accept: application/json",
            },
            {
              name: "retrycodes",
              value: "",
            },
            {
              name: "successcodes",
              value: "",
            },
            {
              name: "contentType",
              value: "application/json",
            },
          ],
          type: "template",
          dependencies: [
            {
              executionCondition: "success",
              metadata: {
                points: [
                  {
                    id: "54e1784a-73cf-48a2-8ef8-e827e4592e71",
                    selected: false,
                    x: 1182.32336172223,
                    y: 458.431777546597,
                  },
                  {
                    id: "c0445931-db85-4a65-9e0b-659586541255",
                    selected: false,
                    x: 1218.91165405636,
                    y: 325.089034173892,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "Track the film",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 1234.91246290801,
              y: 285.086053412463,
            },
          },
        },
        {
          name: "Destroying the planet",
          taskRef: "manual-task",
          results: [],
          params: [
            {
              name: "instructions",
              value:
                'Congratulations, \n\nThe resistance we found, right there it was in the "$(tasks.Find Resistance leader.results.evaluation)" movie.\n\n\n"$(tasks.Find Resistance troops.results.evaluation)"\n\nGather the troops, young padawan! ',
            },
          ],
          type: "manual",
          dependencies: [
            {
              executionCondition: "success",
              metadata: {
                points: [
                  {
                    id: "5d432288-af57-44aa-a5d0-4b42278e0c2b",
                    selected: false,
                    x: 2026.48885900118,
                    y: 576.403534026103,
                  },
                  {
                    id: "94c273d6-22d2-44cb-b635-e81cddf9e536",
                    selected: false,
                    x: 2054.10171246175,
                    y: 449.264098306084,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "Find Resistance leader",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 2070.08679525223,
              y: 409.259643916914,
            },
          },
        },
        {
          name: "Find Resistance troops",
          taskRef: "file-jsonpath-to-parameter",
          results: [],
          params: [
            {
              name: "filePath",
              value: "/tekton/results/thefilm.json",
            },
            {
              name: "query",
              value: "$.opening_crawl",
            },
          ],
          type: "template",
          dependencies: [
            {
              executionCondition: "success",
              metadata: {
                points: [
                  {
                    id: "b593f083-bfc1-4e7b-9c09-1cf503501f3d",
                    selected: false,
                    x: 1470.91248971277,
                    y: 325.089034173892,
                  },
                  {
                    id: "55ff943d-aa3c-483e-b722-579adde795c3",
                    selected: false,
                    x: 1489.57704411628,
                    y: 460.737355218089,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "Scan the film",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 1505.56231454006,
              y: 420.750741839763,
            },
          },
        },
        {
          name: "Find Resistance leader",
          taskRef: "file-jsonpath-to-parameter",
          results: [],
          params: [
            {
              name: "filePath",
              value: "/tekton/results/thefilm.json",
            },
            {
              name: "query",
              value: "$.title",
            },
          ],
          type: "template",
          dependencies: [
            {
              executionCondition: "success",
              metadata: {
                points: [
                  {
                    id: "f0ffeb3c-f329-4499-99cd-657d5e11473b",
                    selected: false,
                    x: 1741.5778797727,
                    y: 460.737355218089,
                  },
                  {
                    id: "3383f240-8a1a-465c-ba39-3e7b45531164",
                    selected: false,
                    x: 1774.48812386261,
                    y: 576.403534026103,
                  },
                ],
              },
              decisionCondition: "",
              taskRef: "Find Resistance troops",
            },
          ],
          labels: {},
          annotations: {
            "io#boomerang/position": {
              x: 1790.50445103858,
              y: 536.409495548961,
            },
          },
        },
      ],
      workspaces: [
        {
          name: "workflowrun",
          type: "workflowrun",
          optional: false,
          spec: {
            size: 1,
            mountPath: "/tekton/results",
          },
        },
      ],
      config: [
        {
          description: "",
          key: "planet",
          label: "Planet name",
          type: "text",
          required: true,
          defaultValue: "Kamino",
          readOnly: false,
        },
      ],
      params: [
        {
          name: "planet",
          type: "string",
          description: "",
          defaultValue: "Kamino",
        },
      ],
    },
  ],
  number: 0,
  size: 2,
  totalElements: 2,
  pageable: "INSTANCE",
  last: true,
  totalPages: 1,
  sort: {
    sorted: false,
    empty: true,
    unsorted: true,
  },
  first: true,
  numberOfElements: 2,
  empty: false,
};

export default workflowTemplates;
